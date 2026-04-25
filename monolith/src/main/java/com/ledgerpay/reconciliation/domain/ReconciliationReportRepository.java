package com.ledgerpay.reconciliation.domain;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, UUID> {
    @Query("select r from ReconciliationReport r order by r.createdAt desc")
    Page<ReconciliationReport> findAllOrdered(Pageable pageable);
}
