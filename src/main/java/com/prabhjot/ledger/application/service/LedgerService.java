package com.prabhjot.ledger.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prabhjot.ledger.domain.exception.CurrencyMismatchException;
import com.prabhjot.ledger.domain.exception.ResourceNotFoundException;
import com.prabhjot.ledger.domain.model.Account;
import com.prabhjot.ledger.domain.model.Entry;
import com.prabhjot.ledger.domain.model.EntryType;
import com.prabhjot.ledger.domain.model.LedgerTransaction;
import com.prabhjot.ledger.domain.model.OutboxEvent;
import com.prabhjot.ledger.domain.model.OutboxStatus;
import com.prabhjot.ledger.domain.validator.DoubleEntryValidator;
import com.prabhjot.ledger.infrastructure.repository.AccountRepository;
import com.prabhjot.ledger.infrastructure.repository.LedgerTransactionRepository;
import com.prabhjot.ledger.infrastructure.repository.OutboxEventRepository;
import com.prabhjot.ledger.presentation.dto.EntryItemRequest;
import com.prabhjot.ledger.presentation.dto.PostTransactionRequest;
import com.prabhjot.ledger.presentation.dto.TransactionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerService {

    private static final int FINANCIAL_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final DoubleEntryValidator doubleEntryValidator;
    private final ObjectMapper objectMapper;

    public LedgerService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            OutboxEventRepository outboxEventRepository,
            DoubleEntryValidator doubleEntryValidator,
            ObjectMapper objectMapper
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.doubleEntryValidator = doubleEntryValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findByIdempotencyKey(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(TransactionResponse::from);
    }

    @Transactional
    public TransactionResponse postTransaction(String idempotencyKey, PostTransactionRequest request) {
        // Invariant 1: Validate double-entry zero-sum rule & precision before touching DB
        doubleEntryValidator.validate(request);

        // Invariant 5 (Layer 2): Check if already persisted
        Optional<LedgerTransaction> existingTx = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTx.isPresent()) {
            return TransactionResponse.from(existingTx.get());
        }

        String txCurrency = request.currency().trim().toUpperCase();

        // Invariant 4: Deadlock-free deterministic locking by sorted UUIDs
        List<UUID> distinctSortedAccountIds = request.entries().stream()
                .map(EntryItemRequest::accountId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID accountId : distinctSortedAccountIds) {
            Account account = accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));

            // Invariant 2: ISO-4217 currency match verification
            if (!account.getCurrency().equalsIgnoreCase(txCurrency)) {
                throw new CurrencyMismatchException(
                        String.format("Account %s currency %s does not match transaction currency %s",
                                accountId, account.getCurrency(), txCurrency)
                );
            }
            lockedAccounts.put(accountId, account);
        }

        // Create append-only LedgerTransaction
        LedgerTransaction transaction = new LedgerTransaction(
                UUID.randomUUID(),
                idempotencyKey,
                txCurrency,
                request.description()
        );

        // Process entries and apply balance changes to accounts
        for (EntryItemRequest item : request.entries()) {
            Account account = lockedAccounts.get(item.accountId());
            BigDecimal amount = item.amount().setScale(FINANCIAL_SCALE, ROUNDING_MODE);

            if (item.type() == EntryType.DEBIT) {
                // Invariant 4: DEBIT decreases balance, enforces overdraft rule
                account.debit(amount);
            } else if (item.type() == EntryType.CREDIT) {
                // Invariant 4: CREDIT increases balance
                account.credit(amount);
            }

            Entry entry = new Entry(
                    UUID.randomUUID(),
                    account.getId(),
                    item.type(),
                    amount
            );
            transaction.addEntry(entry);
        }

        // Save updated accounts and new transaction append-only
        accountRepository.saveAll(lockedAccounts.values());
        LedgerTransaction savedTransaction = transactionRepository.save(transaction);

        TransactionResponse response = TransactionResponse.from(savedTransaction);

        // Invariant 6: Transactional Outbox inside same @Transactional boundary
        try {
            String payload = objectMapper.writeValueAsString(response);
            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID(),
                    "LEDGER_TRANSACTION",
                    savedTransaction.getId().toString(),
                    "TRANSACTION_CREATED",
                    payload,
                    OutboxStatus.PENDING,
                    0
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize transaction payload for outbox", e);
        }

        return response;
    }
}
