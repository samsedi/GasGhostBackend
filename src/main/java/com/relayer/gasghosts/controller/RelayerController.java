package com.relayer.gasghosts.controller;

import com.relayer.gasghosts.dtos.RelayRequestDTO;
import com.relayer.gasghosts.dtos.RelayResponseDTO;
import com.relayer.gasghosts.model.RelayRequest;
import com.relayer.gasghosts.model.RelayResponse;
import com.relayer.gasghosts.service.RelayerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import io.github.bucket4j.Bucket;
import com.relayer.gasghosts.service.RateLimiterService;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${relayer.cors-origins}")
public class RelayerController {

    private final RelayerService relayerService;
    private final RateLimiterService rateLimiterService;

    public RelayerController(RelayerService relayerService, RateLimiterService rateLimiterService) {
        this.relayerService = relayerService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "GasGhosts Relayer",
                "version", "1.0.0"
        ));
    }

    @PostMapping("/relay")
    public ResponseEntity<RelayResponseDTO> relay(@RequestBody RelayRequestDTO dto, HttpServletRequest httpRequest) {
        
        Bucket bucket = rateLimiterService.resolveBucket(httpRequest.getRemoteAddr());
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(RelayResponseDTO.builder()
                            .success(false)
                            .message("Too many requests. Please try again later.")
                            .build());
        }

        if (dto.getFrom() == null || dto.getFrom().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(RelayResponseDTO.builder()
                            .success(false)
                            .message("Missing required field: from")
                            .build());
        }
        if (dto.getSignature() == null || dto.getSignature().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(RelayResponseDTO.builder()
                            .success(false)
                            .message("Missing required field: signature")
                            .build());
        }

        // Map DTO to internal domain model
        RelayRequest request = RelayRequest.builder()
                .from(dto.getFrom())
                .to(dto.getTo())
                .value(new BigInteger(dto.getValue() != null ? dto.getValue() : "0"))
                .nonce(new BigInteger(dto.getNonce() != null ? dto.getNonce() : "0"))
                .deadline(new BigInteger(dto.getDeadline() != null ? dto.getDeadline() : "0"))
                .data(dto.getData())
                .signature(dto.getSignature())
                .chainId(dto.getChainId())
                .rpcUrl(dto.getRpcUrl())
                .build();

        RelayResponse response = relayerService.relay(request);

        // Map internal model to response DTO
        RelayResponseDTO responseDTO = RelayResponseDTO.builder()
                .success(response.isSuccess())
                .txHash(response.getTxHash())
                .message(response.getMessage())
                .explorerUrl(response.getTxHash() != null
                        ? "https://sepolia.etherscan.io/tx/" + response.getTxHash()
                        : null)
                .build();

        return response.isSuccess()
                ? ResponseEntity.ok(responseDTO)
                : ResponseEntity.badRequest().body(responseDTO);
    }

    @GetMapping("/nonce/{address}")
    public ResponseEntity<Map<String, String>> getNonce(
            @PathVariable String address,
            @RequestParam(required = false, defaultValue = "11155111") Long chainId,
            @RequestParam(required = false, defaultValue = "https://eth-sepolia.g.alchemy.com/v2/QV-fhAZqbRcPWE9mHvskD") String rpcUrl) {
        
        BigInteger nonce = relayerService.getNonce(address, chainId, rpcUrl);
        return ResponseEntity.ok(Map.of(
                "address", address,
                "nonce", nonce.toString()
        ));
    }
}