package com.relayer.gasghosts.service;

import com.relayer.gasghosts.model.RelayRequest;
import com.relayer.gasghosts.model.RelayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;
import com.relayer.gasghosts.model.RelayTask;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.web3j.abi.TypeReference;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import com.relayer.gasghosts.config.Web3jProvider;

@Service
public class RelayerService {

    private static final Logger log = LoggerFactory.getLogger(RelayerService.class);

    private final Web3jProvider web3jProvider;
    private final Credentials credentials;
    private final RelayerNonceManager nonceManager;
    private final RelayTaskRepository taskRepository;

    @Value("${relayer.forwarder.address}")
    private String forwarderAddress;

    @Value("${relayer.whitelisted-targets}")
    private List<String> whitelistedTargets;

    @Value("${relayer.whitelisted-methods}")
    private List<String> whitelistedMethods;

    @Value("${relayer.max-gas-price-gwei}")
    private long maxGasPriceGwei;

    public RelayerService(Web3jProvider web3jProvider, Credentials credentials, 
                          RelayerNonceManager nonceManager, 
                          RelayTaskRepository taskRepository) {
        this.web3jProvider = web3jProvider;
        this.credentials = credentials;
        this.nonceManager = nonceManager;
        this.taskRepository = taskRepository;
    }

    public RelayResponse relay(RelayRequest request) {
        try {
            log.info("Relay request from: {} to: {}", request.getFrom(), request.getTo());

            // ── Step 0: Check target whitelist ────────────────────────
            boolean isWhitelisted = whitelistedTargets.stream()
                    .anyMatch(target -> target.equalsIgnoreCase(request.getTo()));
            if (!isWhitelisted) {
                log.warn("Target contract not whitelisted: {}", request.getTo());
                return RelayResponse.builder()
                        .success(false)
                        .message("Target contract is not whitelisted")
                        .build();
            }

            // ── Step 0.5: Check method whitelist ───────────────────────
            String data = request.getData();
            if (data == null || data.length() < 10) {
                log.warn("Invalid data payload from: {}", request.getFrom());
                return RelayResponse.builder()
                        .success(false)
                        .message("Invalid data payload")
                        .build();
            }
            String methodSignature = data.substring(0, 10);
            boolean isMethodWhitelisted = whitelistedMethods.stream()
                    .anyMatch(m -> m.equalsIgnoreCase(methodSignature));
            if (!isMethodWhitelisted) {
                log.warn("Method not whitelisted: {}", methodSignature);
                return RelayResponse.builder()
                        .success(false)
                        .message("Method is not whitelisted")
                        .build();
            }

            // ── Step 1: Verify EIP-712 signature ─────────────────────
            boolean valid = verifySignature(request);
            if (!valid) {
                log.warn("Invalid signature from: {}", request.getFrom());
                return RelayResponse.builder()
                        .success(false)
                        .message("Invalid EIP-712 signature")
                        .build();
            }
            log.info("Signature valid for: {}", request.getFrom());
            
            // Get the correct Web3j instance for this chain
            Web3j web3j = web3jProvider.getWeb3j(request.getChainId(), request.getRpcUrl());

            // ── Step 2: Encode forwarder execute() call ───────────────
            String encodedCall = encodeForwarderCall(request);

            // ── Step 3: Simulate and Estimate Gas ─────────────────────
            log.info("Step 3: Simulating transaction to estimate gas...");
            Transaction ethCallTx = Transaction.createEthCallTransaction(
                    credentials.getAddress(),
                    forwarderAddress,
                    encodedCall
            );
            
            EthEstimateGas estimateResponse = web3j.ethEstimateGas(ethCallTx).send();
            if (estimateResponse.hasError()) {
                log.warn("Transaction simulation failed: {}", estimateResponse.getError().getMessage());
                return RelayResponse.builder()
                        .success(false)
                        .message("Transaction simulation failed: " + estimateResponse.getError().getMessage())
                        .build();
            }
            
            BigInteger estimatedGas = estimateResponse.getAmountUsed();
            // Add 10% buffer to estimated gas
            BigInteger gasLimit = estimatedGas.multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100));
            log.info("Estimated gas: {}, Using limit: {}", estimatedGas, gasLimit);

            // ── Step 4: Submit tx from relayer wallet ─────────────────
            log.info("Step 4: Submitting transaction from relayer wallet...");
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger maxGasPrice = BigInteger.valueOf(maxGasPriceGwei).multiply(BigInteger.TEN.pow(9));
            if (gasPrice.compareTo(maxGasPrice) > 0) {
                log.warn("Network gas price too high: {} > {}", gasPrice, maxGasPrice);
                return RelayResponse.builder()
                        .success(false)
                        .message("Network gas price is too high right now")
                        .build();
            }

            log.info("Fetching nonce for relayer on chain {}...", request.getChainId());
            BigInteger relayerNonce = nonceManager.getAndIncrementNonce(request.getChainId(), web3j);
            log.info("Relayer nonce: {}", relayerNonce);

            RawTransaction rawTransaction = RawTransaction.createTransaction(
                    relayerNonce, gasPrice, gasLimit, forwarderAddress, encodedCall);

            log.info("Signing transaction with relayer private key using chainId {}...", request.getChainId());
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, request.getChainId(), credentials);
            String hexValue = Numeric.toHexString(signedMessage);

            log.info("Sending raw transaction to network {}...", request.getRpcUrl());
            EthSendTransaction txResponse = web3j.ethSendRawTransaction(hexValue).send();

            if (txResponse.hasError()) {
                String error = txResponse.getError().getMessage();
                log.error("Transaction failed: {}", error);
                return RelayResponse.builder()
                        .success(false)
                        .message(mapWeb3Error(error))
                        .build();
            }

            String txHash = txResponse.getTransactionHash();
            log.info("Transaction submitted: {}", txHash);

            // ── Step 5: Save to DB ─────────────────────────────────────
            RelayTask task = RelayTask.builder()
                    .userAddress(request.getFrom())
                    .targetAddress(request.getTo())
                    .txHash(txHash)
                    .relayerNonce(relayerNonce)
                    .gasPricePaid(gasPrice)
                    .dataPayload(encodedCall)
                    .chainId(request.getChainId())
                    .rpcUrl(request.getRpcUrl())
                    .status(RelayTask.Status.PENDING)
                    .build();
            taskRepository.save(task);

            return RelayResponse.builder()
                    .success(true)
                    .txHash(txHash)
                    .message("Transaction submitted successfully")
                    .build();

        } catch (Exception e) {
            log.error("Relay error: {}", e.getMessage(), e);
            return RelayResponse.builder()
                    .success(false)
                    .message("Relay error: " + e.getMessage())
                    .build();
        }
    }

    // ── Nonce Fetching ───────────────────────────────────────────────

    public BigInteger getNonce(String address, Long chainId, String rpcUrl) {
        try {
            Web3j web3j = web3jProvider.getWeb3j(chainId, rpcUrl);
            Function function = new Function("getNonce",
                    Collections.singletonList(new Address(address)),
                    Collections.singletonList(new TypeReference<Uint256>() {}));
            String encodedCall = FunctionEncoder.encode(function);
            
            Transaction transaction = Transaction.createEthCallTransaction(
                    null, forwarderAddress, encodedCall);
            
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();
            
            if (response.hasError() || response.getValue() == null || response.getValue().equals("0x")) {
                return BigInteger.ZERO;
            }
            
            List<org.web3j.abi.datatypes.Type> decoded = org.web3j.abi.FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());
                    
            return decoded.isEmpty() ? BigInteger.ZERO : (BigInteger) decoded.get(0).getValue();
        } catch (Exception e) {
            log.error("Failed to fetch nonce for address {}: {}", address, e.getMessage());
            return BigInteger.ZERO;
        }
    }

    // ── EIP-712 Signature Verification ───────────────────────────────

    private boolean verifySignature(RelayRequest request) {
        try {
            byte[] digest = buildEIP712Digest(request);

            byte[] sigBytes = Numeric.hexStringToByteArray(request.getSignature());
            if (sigBytes.length != 65) throw new IllegalArgumentException("Invalid signature length");

            byte v = sigBytes[64];
            if (v < 27) v += 27;
            byte[] r = Arrays.copyOfRange(sigBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(sigBytes, 32, 64);

            Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);
            BigInteger recoveredKey = Sign.signedMessageHashToKey(digest, signatureData);
            String recoveredAddress = "0x" + Keys.getAddress(recoveredKey);

            log.info("Recovered Address: {}", recoveredAddress);
            log.info("Expected Address: {}", request.getFrom());

            return recoveredAddress.equalsIgnoreCase(request.getFrom());
        } catch (Exception e) {
            log.error("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private byte[] buildEIP712Digest(RelayRequest request) {
        byte[] domainSeparator = buildDomainSeparator(request.getChainId());
        byte[] structHash = buildStructHash(request);

        // "\x19\x01" + domainSeparator + structHash
        ByteBuffer buffer = ByteBuffer.allocate(2 + 32 + 32);
        buffer.put((byte) 0x19);
        buffer.put((byte) 0x01);
        buffer.put(domainSeparator);
        buffer.put(structHash);

        return Hash.sha3(buffer.array());
    }

    private byte[] buildDomainSeparator(long chainId) {
        byte[] typeHash = Hash.sha3(
                "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                        .getBytes()
        );
        byte[] nameHash    = Hash.sha3("Relayer".getBytes());
        byte[] versionHash = Hash.sha3("1".getBytes());
        byte[] chainIdPad  = Numeric.toBytesPadded(BigInteger.valueOf(chainId), 32);
        byte[] addressPad  = Numeric.toBytesPadded(Numeric.toBigInt(forwarderAddress), 32);

        ByteBuffer buffer = ByteBuffer.allocate(32 * 5);
        buffer.put(typeHash);
        buffer.put(nameHash);
        buffer.put(versionHash);
        buffer.put(chainIdPad);
        buffer.put(addressPad);

        return Hash.sha3(buffer.array());
    }

    private byte[] buildStructHash(RelayRequest request) {
        byte[] typeHash = Hash.sha3(
                "ForwardRequest(address from,address to,uint256 value,uint256 nonce,uint256 deadline,bytes data)"
                        .getBytes()
        );
        byte[] fromPad  = Numeric.toBytesPadded(Numeric.toBigInt(request.getFrom()), 32);
        byte[] toPad    = Numeric.toBytesPadded(Numeric.toBigInt(request.getTo()), 32);
        byte[] valuePad = Numeric.toBytesPadded(request.getValue(), 32);
        byte[] noncePad = Numeric.toBytesPadded(request.getNonce(), 32);
        byte[] deadlinePad = Numeric.toBytesPadded(request.getDeadline(), 32);
        byte[] dataHash = Hash.sha3(Numeric.hexStringToByteArray(request.getData()));

        ByteBuffer buffer = ByteBuffer.allocate(32 * 7);
        buffer.put(typeHash);
        buffer.put(fromPad);
        buffer.put(toPad);
        buffer.put(valuePad);
        buffer.put(noncePad);
        buffer.put(deadlinePad);
        buffer.put(dataHash);

        return Hash.sha3(buffer.array());
    }

    // ── Forwarder execute() ABI encoding ─────────────────────────────

    private String encodeForwarderCall(RelayRequest request) {
        Function function = new Function(
                "execute",
                Arrays.asList(
                        new DynamicStruct(
                                new Address(request.getFrom()),
                                new Address(request.getTo()),
                                new Uint256(request.getValue()),
                                new Uint256(request.getNonce()),
                                new Uint256(request.getDeadline()),
                                new DynamicBytes(Numeric.hexStringToByteArray(request.getData()))
                        ),
                        new DynamicBytes(Numeric.hexStringToByteArray(request.getSignature()))
                ),
                Collections.emptyList()
        );
        return FunctionEncoder.encode(function);
    }

    // ── Error Mapping ────────────────────────────────────────────────

    private String mapWeb3Error(String rawError) {
        if (rawError == null) return "Unknown transaction error occurred on the network.";
        
        String lowerError = rawError.toLowerCase();
        if (lowerError.contains("insufficient funds")) {
            return "Relayer has insufficient funds for gas on this network.";
        } else if (lowerError.contains("nonce too low")) {
            return "Transaction dropped: nonce too low. Please retry.";
        } else if (lowerError.contains("replacement transaction underpriced")) {
            return "Transaction underpriced. The network is currently busy.";
        } else if (lowerError.contains("intrinsic gas too low")) {
            return "Transaction gas limit is too low.";
        } else if (lowerError.contains("execution reverted")) {
            return "Transaction execution reverted by the destination smart contract.";
        }
        
        // Return original error if no friendly mapping is found
        return "Transaction failed: " + rawError;
    }
}