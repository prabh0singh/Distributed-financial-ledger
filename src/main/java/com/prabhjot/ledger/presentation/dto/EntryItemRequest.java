package com.prabhjot.ledger.presentation.dto;

import com.prabhjot.ledger.domain.model.EntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record EntryItemRequest(
        @NotNull(message = "Account ID is required")
        UUID accountId,

        @NotNull(message = "Entry type is required")
        EntryType type,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.0001", message = "Amount must be strictly positive")
        BigDecimal amount,

        String currency
) {
}
