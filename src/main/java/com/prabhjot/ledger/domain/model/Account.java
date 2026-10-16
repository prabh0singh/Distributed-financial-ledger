package com.prabhjot.ledger.domain.model;

import com.prabhjot.ledger.domain.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    private static final int FINANCIAL_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = FINANCIAL_SCALE)
    private BigDecimal balance;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Account() {
    }

    public Account(UUID id, String currency, BigDecimal initialBalance, boolean allowNegative) {
        this.id = id != null ? id : UUID.randomUUID();
        this.currency = Objects.requireNonNull(currency, "Currency must not be null").toUpperCase();
        this.balance = (initialBalance != null ? initialBalance : BigDecimal.ZERO).setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        this.allowNegative = allowNegative;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.version = 0L;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        } else {
            this.balance = this.balance.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = this.createdAt;
        }
        if (this.version == null) {
            this.version = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        Objects.requireNonNull(amount, "Amount must not be null");
        BigDecimal normalizedAmount = amount.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        this.balance = this.balance.add(normalizedAmount).setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        this.updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        Objects.requireNonNull(amount, "Amount must not be null");
        BigDecimal normalizedAmount = amount.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        BigDecimal newBalance = this.balance.subtract(normalizedAmount).setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        if (!this.allowNegative && newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(
                    String.format("Insufficient funds for account %s: current balance %s, debit requested %s",
                            this.id, this.balance, normalizedAmount)
            );
        }
        this.balance = newBalance;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency != null ? currency.toUpperCase() : null;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance != null ? balance.setScale(FINANCIAL_SCALE, ROUNDING_MODE) : BigDecimal.ZERO.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public void setAllowNegative(boolean allowNegative) {
        this.allowNegative = allowNegative;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account account)) return false;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
