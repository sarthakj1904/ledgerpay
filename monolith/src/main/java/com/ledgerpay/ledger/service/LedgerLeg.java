package com.ledgerpay.ledger.service;

import java.util.UUID;

import com.ledgerpay.ledger.domain.EntryType;

/**
 * One side of a double-entry posting: debit or credit a specific wallet for an amount.
 */
public record LedgerLeg(UUID walletId, EntryType entryType, long amountMinor) {
    public static LedgerLeg debit(UUID walletId, long amountMinor) {
        return new LedgerLeg(walletId, EntryType.DEBIT, amountMinor);
    }
    public static LedgerLeg credit(UUID walletId, long amountMinor) {
        return new LedgerLeg(walletId, EntryType.CREDIT, amountMinor);
    }
}
