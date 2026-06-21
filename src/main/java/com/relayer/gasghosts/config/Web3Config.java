package com.relayer.gasghosts.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.crypto.Credentials;

@Configuration
public class Web3Config {

    @Value("${relayer.alchemy.url}")
    private String alchemyUrl;

    @Value("${relayer.private.key}")
    private String privateKey;

    @Bean
    public Credentials credentials() {
        return Credentials.create(privateKey);
    }
}