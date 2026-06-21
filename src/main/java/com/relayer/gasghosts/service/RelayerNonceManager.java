package com.relayer.gasghosts.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RelayerNonceManager {

    private static final Logger log = LoggerFactory.getLogger(RelayerNonceManager.class);

    private final Credentials credentials;
    
    // Map of chainId -> current nonce
    private final Map<Long, BigInteger> currentNonces = new ConcurrentHashMap<>();

    public RelayerNonceManager(Credentials credentials) {
        this.credentials = credentials;
    }

    /**
     * Gets the current nonce for the relayer wallet on the specified chain,
     * incrementing it locally for the next transaction.
     */
    public synchronized BigInteger getAndIncrementNonce(long chainId, Web3j web3j) {
        BigInteger nonce = currentNonces.get(chainId);
        
        if (nonce == null) {
            log.info("Fetching initial nonce from chain {} for relayer...", chainId);
            try {
                BigInteger onChainNonce = web3j.ethGetTransactionCount(
                        credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
                nonce = onChainNonce;
                log.info("Initial nonce on chain {} is: {}", chainId, nonce);
            } catch (Exception e) {
                log.error("Failed to fetch nonce from chain {}", chainId, e);
                throw new RuntimeException("Could not initialize nonce for chain " + chainId);
            }
        }

        BigInteger assignedNonce = nonce;
        currentNonces.put(chainId, nonce.add(BigInteger.ONE));
        return assignedNonce;
    }

    /**
     * Used when a transaction fails and we want to reset the local nonce counter 
     * to the actual on-chain value for a specific chain.
     */
    public synchronized void resetNonce(long chainId) {
        currentNonces.remove(chainId);
        log.info("Local nonce cache cleared for chain {}. Will fetch from network on next request.", chainId);
    }
}
