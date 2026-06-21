package com.relayer.gasghosts.exception;

import com.relayer.gasghosts.dtos.RelayResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RelayResponseDTO> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed JSON request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(RelayResponseDTO.builder()
                .success(false)
                .message("Malformed JSON request. Please check your payload.")
                .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RelayResponseDTO> handleGenericException(Exception ex) {
        log.error("Unhandled exception occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(RelayResponseDTO.builder()
                        .success(false)
                        .message("An unexpected internal server error occurred.")
                        .build());
    }
}
