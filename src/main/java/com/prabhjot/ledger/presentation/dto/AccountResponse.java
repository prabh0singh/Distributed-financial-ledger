package com.prabhjot.ledger.presentation.dto;

import com.prabhjot.ledger.domain.model.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String currency,
        BigDecimal balance,
        boolean allowNegative,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrency(),
                account.getBalance(),
                account.isAllowNegative(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
