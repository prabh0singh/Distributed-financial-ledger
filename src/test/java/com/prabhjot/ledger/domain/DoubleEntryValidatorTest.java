package com.prabhjot.ledger.domain.validator;

import com.prabhjot.ledger.domain.exception.CurrencyMismatchException;
import com.prabhjot.ledger.domain.exception.UnbalancedTransactionException;
import com.prabhjot.ledger.domain.model.EntryType;
import com.prabhjot.ledger.presentation.dto.EntryItemRequest;
import com.prabhjot.ledger.presentation.dto.PostTransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleEntryValidatorTest {

    private DoubleEntryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DoubleEntryValidator();
    }

    @Test
    @DisplayName("Should pass for a balanced 2-legged transaction")
    void shouldPassForBalancedTwoLeggedTransaction() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Payment",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("100.5000"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("100.5000"), "USD")
                )
        );

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    @DisplayName("Should pass for a balanced multi-legged transaction")
    void shouldPassForBalancedMultiLeggedTransaction() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();
        UUID account3 = UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Split payment",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("150.0000"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("90.0000"), "USD"),
                        new EntryItemRequest(account3, EntryType.CREDIT, new BigDecimal("60.0000"), "USD")
                )
        );

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    @DisplayName("Should throw UnbalancedTransactionException when Debits != Credits")
    void shouldThrowUnbalancedExceptionWhenSumDiffers() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Unbalanced payment",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("100.0000"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("99.9999"), "USD")
                )
        );

        UnbalancedTransactionException ex = assertThrows(
                UnbalancedTransactionException.class,
                () -> validator.validate(request)
        );
        assertTrue(ex.getMessage().contains("must strictly equal"));
    }

    @Test
    @DisplayName("Should throw UnbalancedTransactionException when fewer than 2 entries")
    void shouldThrowWhenFewerThanTwoEntries() {
        UUID account1 = UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Single entry",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("100.0000"), "USD")
                )
        );

        assertThrows(UnbalancedTransactionException.class, () -> validator.validate(request));
    }

    @Test
    @DisplayName("Should throw CurrencyMismatchException when entry currency differs from transaction currency")
    void shouldThrowCurrencyMismatchWhenEntryHasDifferentCurrency() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Currency mix",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("100.0000"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("100.0000"), "EUR")
                )
        );

        CurrencyMismatchException ex = assertThrows(
                CurrencyMismatchException.class,
                () -> validator.validate(request)
        );
        assertTrue(ex.getMessage().contains("EUR does not match transaction currency USD"));
    }

    @Test
    @DisplayName("Should throw UnbalancedTransactionException when amount is zero or negative")
    void shouldThrowWhenAmountIsZeroOrNegative() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        PostTransactionRequest zeroRequest = new PostTransactionRequest(
                "USD",
                "Zero amount",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, BigDecimal.ZERO, "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, BigDecimal.ZERO, "USD")
                )
        );

        assertThrows(UnbalancedTransactionException.class, () -> validator.validate(zeroRequest));

        PostTransactionRequest negativeRequest = new PostTransactionRequest(
                "USD",
                "Negative amount",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("-50.0000"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("-50.0000"), "USD")
                )
        );

        assertThrows(UnbalancedTransactionException.class, () -> validator.validate(negativeRequest));
    }

    @Test
    @DisplayName("Should correctly round amounts with HALF_EVEN and validate equality")
    void shouldCorrectlyRoundAmountsWithHalfEven() {
        UUID account1 = UUID.randomUUID();
        UUID account2 = UUID.randomUUID();

        // 100.00005 with HALF_EVEN rounds to 100.0000
        // 100.00004 rounds to 100.0000
        PostTransactionRequest request = new PostTransactionRequest(
                "USD",
                "Precision test",
                List.of(
                        new EntryItemRequest(account1, EntryType.DEBIT, new BigDecimal("100.00004"), "USD"),
                        new EntryItemRequest(account2, EntryType.CREDIT, new BigDecimal("100.00000"), "USD")
                )
        );

        assertDoesNotThrow(() -> validator.validate(request));
    }
}
