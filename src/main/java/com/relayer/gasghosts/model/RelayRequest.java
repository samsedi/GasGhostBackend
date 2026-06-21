package com.relayer.gasghosts.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigInteger;

/**
 * Internal domain model for a forward request.
 * Mapped from RelayRequestDTO by the controller.
 */
@Data
@Builder
public class RelayRequest {
    private String from;
    private String to;
    private BigInteger value;
    private BigInteger nonce;
    private BigInteger deadline;
    private String data;
    private String signature;
    private Long chainId;
    private String rpcUrl;
}