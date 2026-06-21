package com.relayer.gasghosts.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.LocalDateTime;

@Entity
@Table(name = "relay_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelayTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userAddress;
    private String targetAddress;
    private Long chainId;
    private String rpcUrl;

    @Column(unique = true)
    private String txHash;

    private BigInteger relayerNonce;
    private BigInteger gasPricePaid;
    
    @Column(length = 2000)
    private String dataPayload;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING,
        MINED,
        FAILED,
        REPLACED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
