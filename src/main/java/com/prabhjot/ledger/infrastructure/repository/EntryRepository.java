package com.prabhjot.ledger.infrastructure.repository;

import com.prabhjot.ledger.domain.model.Entry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EntryRepository extends JpaRepository<Entry, UUID> {

    List<Entry> findByTransactionId(UUID transactionId);

    List<Entry> findByAccountId(UUID accountId);
}
