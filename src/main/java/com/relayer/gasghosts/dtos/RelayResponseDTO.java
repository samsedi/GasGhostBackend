package com.relayer.gasghosts.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO sent back to the frontend after relaying.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RelayResponseDTO {

    /** Whether the relay was successful */
    private boolean success;

    /** Transaction hash on-chain — null if failed */
    private String txHash;

    /** Human-readable message */
    private String message;

    /** Etherscan link for the transaction */
    private String explorerUrl;
}