package com.relayer.gasghosts.model;
import lombok.Builder;
import lombok.Data;

/**
 * Internal domain model for relay result.
 * Mapped to RelayResponseDTO by the controller.
 */
@Data
@Builder
public class RelayResponse {
    private boolean success;
    private String txHash;
    private String message;
}