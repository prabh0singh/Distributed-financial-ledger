package com.prabhjot.ledger.presentation.controller;

import com.prabhjot.ledger.application.service.IdempotencyService;
import com.prabhjot.ledger.application.service.LedgerService;
import com.prabhjot.ledger.presentation.dto.PostTransactionRequest;
import com.prabhjot.ledger.presentation.dto.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    public LedgerController(LedgerService ledgerService, IdempotencyService idempotencyService) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> postTransaction(
            @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String normalizedKey = idempotencyKey.trim();

        // Layer 2 Check (PostgreSQL)
        Optional<TransactionResponse> persistedTx = ledgerService.findByIdempotencyKey(normalizedKey);
        if (persistedTx.isPresent()) {
            return ResponseEntity.ok(persistedTx.get());
        }

        // Layer 1 Check (Redis Cache)
        Optional<TransactionResponse> cachedTx = idempotencyService.getCachedResponse(normalizedKey);
        if (cachedTx.isPresent()) {
            return ResponseEntity.ok(cachedTx.get());
        }

        // Layer 1 Lock (Redis setIfAbsent)
        boolean lockAcquired = idempotencyService.acquireInFlightLock(normalizedKey);
        if (!lockAcquired) {
            // Concurrent request with same idempotency key is in-flight.
            // Poll briefly to return the completed transaction response if ready.
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                cachedTx = idempotencyService.getCachedResponse(normalizedKey);
                if (cachedTx.isPresent()) {
                    return ResponseEntity.ok(cachedTx.get());
                }

                persistedTx = ledgerService.findByIdempotencyKey(normalizedKey);
                if (persistedTx.isPresent()) {
                    return ResponseEntity.ok(persistedTx.get());
                }
            }

            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        try {
            TransactionResponse created = ledgerService.postTransaction(normalizedKey, request);
            idempotencyService.cacheResponse(normalizedKey, created);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (RuntimeException ex) {
            idempotencyService.releaseLock(normalizedKey);
            throw ex;
        }
    }
}
