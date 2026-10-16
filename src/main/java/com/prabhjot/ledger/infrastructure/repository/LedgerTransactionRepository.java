package com.prabhjot.ledger.infrastructure.repository;

import com.prabhjot.ledger.domain.model.LedgerTransaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @EntityGraph(attributePaths = {"entries"})
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}
