package com.ledgerpay.ledger.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
    List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    /**
     * Returns one row per wallet with its ledger-derived debit/credit totals. Used by
     * the reconciliation engine to verify that {@code wallets.balance_minor} matches
     * {@code sum(credits) - sum(debits)} from the immutable ledger.
     *
     * @return rows of (wallet_id : UUID, total_debits : Number, total_credits : Number)
     */
    @Query(value = """
            SELECT wallet_id,
                   SUM(CASE WHEN entry_type = 'DEBIT'  THEN amount_minor ELSE 0 END) AS total_debits,
                   SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE 0 END) AS total_credits
              FROM ledger_entries
             GROUP BY wallet_id
            """, nativeQuery = true)
    List<Object[]> sumByWallet();
}
