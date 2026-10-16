package com.prabhjot.ledger.presentation.dto;

import com.prabhjot.ledger.domain.model.LedgerTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String idempotencyKey,
        String currency,
        String description,
        List<EntryItemResponse> entries,
        Instant createdAt
) {
    public static TransactionResponse from(LedgerTransaction tx) {
        List<EntryItemResponse> entryResponses = tx.getEntries() != null
                ? tx.getEntries().stream().map(EntryItemResponse::from).toList()
                : List.of();

        return new TransactionResponse(
                tx.getId(),
                tx.getIdempotencyKey(),
                tx.getCurrency(),
                tx.getDescription(),
                entryResponses,
                tx.getCreatedAt()
        );
    }
}
