package com.ledgerpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

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

/**
 * Immutable double-entry ledger row. The table is protected at the DB level by triggers that
 * reject UPDATE and DELETE; this entity intentionally exposes no setters.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "txn_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 8)
    private EntryType entryType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static LedgerEntry debit(UUID txnId, UUID walletId, long amountMinor,
                                    long balanceAfterMinor, String currency) {
        return new LedgerEntry(UUID.randomUUID(), txnId, walletId,
                EntryType.DEBIT, amountMinor, balanceAfterMinor, currency);
    }

    public static LedgerEntry credit(UUID txnId, UUID walletId, long amountMinor,
                                     long balanceAfterMinor, String currency) {
        return new LedgerEntry(UUID.randomUUID(), txnId, walletId,
                EntryType.CREDIT, amountMinor, balanceAfterMinor, currency);
    }

    private LedgerEntry(UUID id, UUID txnId, UUID walletId, EntryType entryType,
                        long amountMinor, long balanceAfterMinor, String currency) {
        this.id = id;
        this.transactionId = txnId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amountMinor = amountMinor;
        this.balanceAfterMinor = balanceAfterMinor;
        this.currency = currency;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
