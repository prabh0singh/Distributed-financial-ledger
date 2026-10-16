package com.prabhjot.ledger.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PostTransactionRequest(
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-character ISO code")
        String currency,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @NotEmpty(message = "Transaction must contain entries")
        @Size(min = 2, message = "Transaction requires at least 2 entries")
        List<@Valid EntryItemRequest> entries
) {
}
