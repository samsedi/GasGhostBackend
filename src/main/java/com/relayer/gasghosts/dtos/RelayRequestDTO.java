package com.relayer.gasghosts.dtos;

import lombok.Data;

/**
 * DTO received from the frontend.
 * Contains the EIP-712 signed meta-transaction request.
 */
@Data
public class RelayRequestDTO {

    /** The original user's wallet address (the signer) */
    private String from;

    /** Target contract address to call */
    private String to;

    /** ETH value to forward — usually "0" for gasless */
    private String value;

    /** User's current nonce from the Forwarder contract */
    private String nonce;

    /** ABI-encoded function call data */
    private String data;

    /** Deadline for the meta-transaction */
    private String deadline;

    /** EIP-712 signature produced by the user's wallet */
    private String signature;

    /** Dynamic Chain ID from the frontend */
    private Long chainId;

    /** Dynamic RPC URL from the frontend */
    private String rpcUrl;
}