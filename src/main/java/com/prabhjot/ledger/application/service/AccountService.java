package com.prabhjot.ledger.application.service;

import com.prabhjot.ledger.domain.exception.ResourceNotFoundException;
import com.prabhjot.ledger.domain.model.Account;
import com.prabhjot.ledger.infrastructure.repository.AccountRepository;
import com.prabhjot.ledger.presentation.dto.AccountResponse;
import com.prabhjot.ledger.presentation.dto.CreateAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        BigDecimal initialBalance = request.initialBalance() != null ? request.initialBalance() : BigDecimal.ZERO;
        boolean allowNegative = request.resolveAllowNegative();

        Account account = new Account(
                UUID.randomUUID(),
                request.currency(),
                initialBalance,
                allowNegative
        );

        Account saved = accountRepository.save(account);
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id));
        return AccountResponse.from(account);
    }
}
