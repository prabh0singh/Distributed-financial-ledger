package com.prabhjot.ledger.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "entries")
public class Entry {

    private static final int FINANCIAL_SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private EntryType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = FINANCIAL_SCALE, updatable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Entry() {
    }

    public Entry(UUID id, UUID accountId, EntryType type, BigDecimal amount) {
        this.id = id != null ? id : UUID.randomUUID();
        this.accountId = Objects.requireNonNull(accountId, "Account ID must not be null");
        this.type = Objects.requireNonNull(type, "Entry type must not be null");
        this.amount = Objects.requireNonNull(amount, "Amount must not be null").setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.amount != null) {
            this.amount = this.amount.setScale(FINANCIAL_SCALE, ROUNDING_MODE);
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public void setTransaction(LedgerTransaction transaction) {
        this.transaction = transaction;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public EntryType getType() {
        return type;
    }

    public void setType(EntryType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount != null ? amount.setScale(FINANCIAL_SCALE, ROUNDING_MODE) : null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Entry entry)) return false;
        return Objects.equals(id, entry.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
