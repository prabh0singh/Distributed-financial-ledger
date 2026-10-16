package com.prabhjot.ledger.domain.validator;

import com.prabhjot.ledger.domain.exception.CurrencyMismatchException;
import com.prabhjot.ledger.domain.exception.UnbalancedTransactionException;
import com.prabhjot.ledger.domain.model.EntryType;
import com.prabhjot.ledger.presentation.dto.EntryItemRequest;
import com.prabhjot.ledger.presentation.dto.PostTransactionRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class DoubleEntryValidator {

    private static final int FINANCIAL_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    public void validate(PostTransactionRequest request) {
        if (request == null) {
            throw new UnbalancedTransactionException("Transaction request must not be null");
        }
        validate(request.currency(), request.entries());
    }

    public void validate(String transactionCurrency, List<EntryItemRequest> entries) {
        if (transactionCurrency == null || transactionCurrency.trim().isEmpty()) {
            throw new CurrencyMismatchException("Transaction currency must not be empty");
        }

        if (entries == null || entries.size() < 2) {
            throw new UnbalancedTransactionException("Transaction requires at least 2 entries");
        }

        String normalizedTxCurrency = transactionCurrency.trim().toUpperCase();

        BigDecimal debitSum = BigDecimal.ZERO.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        BigDecimal creditSum = BigDecimal.ZERO.setScale(FINANCIAL_SCALE, ROUNDING_MODE);

        for (EntryItemRequest entry : entries) {
            if (entry == null) {
                throw new UnbalancedTransactionException("Entry item must not be null");
            }

            if (entry.accountId() == null) {
                throw new UnbalancedTransactionException("Account ID must not be null for entry");
            }

            if (entry.type() == null) {
                throw new UnbalancedTransactionException("Entry type must not be null");
            }

            if (entry.amount() == null || entry.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new UnbalancedTransactionException("Entry amount must be strictly positive");
            }

            if (entry.currency() != null && !entry.currency().isBlank()) {
                if (!entry.currency().trim().equalsIgnoreCase(normalizedTxCurrency)) {
                    throw new CurrencyMismatchException(
                            String.format("Entry currency %s does not match transaction currency %s",
                                    entry.currency().trim().toUpperCase(), normalizedTxCurrency)
                    );
                }
            }

            BigDecimal normalizedAmount = entry.amount().setScale(FINANCIAL_SCALE, ROUNDING_MODE);

            if (entry.type() == EntryType.DEBIT) {
                debitSum = debitSum.add(normalizedAmount).setScale(FINANCIAL_SCALE, ROUNDING_MODE);
            } else if (entry.type() == EntryType.CREDIT) {
                creditSum = creditSum.add(normalizedAmount).setScale(FINANCIAL_SCALE, ROUNDING_MODE);
            }
        }

        if (debitSum.compareTo(creditSum) != 0) {
            throw new UnbalancedTransactionException(
                    String.format("Unbalanced transaction: total DEBIT (%s) must strictly equal total CREDIT (%s)",
                            debitSum, creditSum)
            );
        }
    }
}
