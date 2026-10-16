package com.prabhjot.ledger.presentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-character ISO code")
        String currency,

        @DecimalMin(value = "0.0000", message = "Initial balance cannot be negative")
        BigDecimal initialBalance,

        Boolean allowNegative
) {
    public boolean resolveAllowNegative() {
        return allowNegative != null && allowNegative;
    }
}
