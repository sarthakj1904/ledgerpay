package com.ledgerpay.reconciliation.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reconciliation_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationReport {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReconciliationStatus status;

    @Column(name = "wallets_checked", nullable = false)
    private int walletsChecked;

    @Column(name = "transactions_checked", nullable = false)
    private int transactionsChecked;

    @Column(name = "ledger_entries_checked", nullable = false)
    private int ledgerEntriesChecked;

    @Column(name = "total_debits_minor", nullable = false)
    private long totalDebitsMinor;

    @Column(name = "total_credits_minor", nullable = false)
    private long totalCreditsMinor;

    @Column(name = "mismatches", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String mismatchesJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ReconciliationReport newReport() {
        ReconciliationReport r = new ReconciliationReport();
        r.id = UUID.randomUUID();
        r.startedAt = Instant.now();
        r.mismatchesJson = "[]";
        return r;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
