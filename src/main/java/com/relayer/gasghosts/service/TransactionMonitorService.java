package com.relayer.gasghosts.service;

import com.relayer.gasghosts.model.RelayTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import com.relayer.gasghosts.config.Web3jProvider;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionMonitorService {

    private static final Logger log = LoggerFactory.getLogger(TransactionMonitorService.class);

    private final RelayTaskRepository taskRepository;
    private final Web3jProvider web3jProvider;
    private final Credentials credentials;

    @Value("${relayer.tx-bump-threshold-minutes:2}")
    private int bumpThresholdMinutes;

    @Value("${relayer.forwarder.address}")
    private String forwarderAddress;

    public TransactionMonitorService(RelayTaskRepository taskRepository, Web3jProvider web3jProvider, Credentials credentials) {
        this.taskRepository = taskRepository;
        this.web3jProvider = web3jProvider;
        this.credentials = credentials;
    }

    @Scheduled(fixedDelayString = "60000")
    public void monitorPendingTransactions() {
        log.info("Running pending transaction monitor...");
        LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(bumpThresholdMinutes);
        
        List<RelayTask> pendingTasks = taskRepository.findByStatusAndCreatedAtBefore(RelayTask.Status.PENDING, thresholdTime);
        
        for (RelayTask task : pendingTasks) {
            try {
                Web3j web3j = web3jProvider.getWeb3j(task.getChainId(), task.getRpcUrl());
                EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(task.getTxHash()).send();
                Optional<TransactionReceipt> receiptOpt = receiptResponse.getTransactionReceipt();
                
                if (receiptOpt.isPresent()) {
                    TransactionReceipt receipt = receiptOpt.get();
                    if (receipt.isStatusOK()) {
                        task.setStatus(RelayTask.Status.MINED);
                        log.info("Transaction {} was successfully mined", task.getTxHash());
                    } else {
                        task.setStatus(RelayTask.Status.FAILED);
                        log.warn("Transaction {} failed on chain", task.getTxHash());
                    }
                    taskRepository.save(task);
                } else {
                    bumpTransactionGas(task);
                }
            } catch (Exception e) {
                log.error("Error monitoring task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void bumpTransactionGas(RelayTask task) throws Exception {
        log.info("Bumping gas for stuck transaction: {}", task.getTxHash());

        BigInteger newGasPrice = task.getGasPricePaid().multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        
        BigInteger gasLimit = BigInteger.valueOf(350000); 

        RawTransaction rawTransaction = RawTransaction.createTransaction(
                task.getRelayerNonce(),
                newGasPrice,
                gasLimit,
                forwarderAddress,
                task.getDataPayload() == null ? "" : task.getDataPayload()
        );

        long chainId = task.getChainId();
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        String hexValue = Numeric.toHexString(signedMessage);

        Web3j web3j = web3jProvider.getWeb3j(task.getChainId(), task.getRpcUrl());
        EthSendTransaction txResponse = web3j.ethSendRawTransaction(hexValue).send();

        if (txResponse.hasError()) {
            log.error("Failed to bump transaction {}: {}", task.getTxHash(), txResponse.getError().getMessage());
            return;
        }

        String newTxHash = txResponse.getTransactionHash();
        log.info("Transaction bumped successfully. New Hash: {}", newTxHash);

        task.setStatus(RelayTask.Status.REPLACED);
        taskRepository.save(task);

        RelayTask newTask = RelayTask.builder()
                .userAddress(task.getUserAddress())
                .targetAddress(task.getTargetAddress())
                .txHash(newTxHash)
                .relayerNonce(task.getRelayerNonce())
                .gasPricePaid(newGasPrice)
                .dataPayload(task.getDataPayload())
                .chainId(task.getChainId())
                .rpcUrl(task.getRpcUrl())
                .status(RelayTask.Status.PENDING)
                .build();
        taskRepository.save(newTask);
    }
}
