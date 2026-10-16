package com.prabhjot.ledger.presentation.dto;

import com.prabhjot.ledger.domain.model.Entry;
import com.prabhjot.ledger.domain.model.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EntryItemResponse(
        UUID id,
        UUID accountId,
        EntryType type,
        BigDecimal amount,
        Instant createdAt
) {
    public static EntryItemResponse from(Entry entry) {
        return new EntryItemResponse(
                entry.getId(),
                entry.getAccountId(),
                entry.getType(),
                entry.getAmount(),
                entry.getCreatedAt()
        );
    }
}
