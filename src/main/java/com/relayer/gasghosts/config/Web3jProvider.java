package com.relayer.gasghosts.config;

import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Web3jProvider {

    private final Map<String, Web3j> instances = new ConcurrentHashMap<>();

    public Web3j getWeb3j(Long chainId, String rpcUrl) {
        if (chainId == null || rpcUrl == null || rpcUrl.isBlank()) {
            throw new IllegalArgumentException("Chain ID and RPC URL must be provided");
        }
        String cacheKey = chainId + "-" + rpcUrl;
        return instances.computeIfAbsent(cacheKey, key -> Web3j.build(new HttpService(rpcUrl)));
    }
}
