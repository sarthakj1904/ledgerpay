package com.ledgerpay.reconciliation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.ledger.domain.LedgerEntryRepository;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.reconciliation.domain.ReconciliationReport;
import com.ledgerpay.reconciliation.domain.ReconciliationReportRepository;
import com.ledgerpay.reconciliation.domain.ReconciliationStatus;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Daily integrity check that proves the ledger is internally consistent. Verifies two facts at
 * the moment it runs:
 *
 * <ol>
 *   <li><b>Conservation of value</b> — the global sum of debit amounts equals the global sum of
 *       credit amounts. Violation means the double-entry posting code is broken.</li>
 *   <li><b>Wallet balance integrity</b> — for every non-SYSTEM wallet,
 *       {@code wallets.balance_minor == sum(credits) - sum(debits)} from the immutable ledger.
 *       Violation means a wallet write-path has bypassed the ledger.</li>
 * </ol>
 *
 * Each run produces one {@link ReconciliationReport} row. Mismatches are recorded in the
 * report's {@code mismatches} JSON column for offline investigation. Metrics are emitted so
 * Prometheus alerts can fire on {@code ledgerpay_reconciliation_status{status="MISMATCH"}}.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final ReconciliationReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReconciliationService(WalletRepository walletRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 PaymentTransactionRepository transactionRepository,
                                 ReconciliationReportRepository reportRepository,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReconciliationReport runOnce() {
        ReconciliationReport report = ReconciliationReport.newReport();
        Instant started = report.getStartedAt();
        log.info("Reconciliation pass started at {}", started);

        List<Wallet> wallets = walletRepository.findAll();
        Map<UUID, Wallet> walletsById = new java.util.HashMap<>(wallets.size());
        for (Wallet w : wallets) walletsById.put(w.getId(), w);

        List<Mismatch> mismatches = new ArrayList<>();
        long globalDebits = 0;
        long globalCredits = 0;
        int walletsChecked = 0;

        for (Object[] row : ledgerEntryRepository.sumByWallet()) {
            UUID walletId = (UUID) row[0];
            long debits = ((Number) row[1]).longValue();
            long credits = ((Number) row[2]).longValue();
            globalDebits += debits;
            globalCredits += credits;

            Wallet wallet = walletsById.get(walletId);
            if (wallet == null) {
                mismatches.add(Mismatch.orphanLedgerEntries(walletId, debits, credits));
                continue;
            }
            // SYSTEM wallets (cash-in suspense, etc.) intentionally drift; skip the per-wallet
            // balance check for them. The global debits==credits check still covers them.
            if (wallet.getOwnerType() == OwnerType.SYSTEM) {
                walletsChecked++;
                continue;
            }
            long expected = credits - debits;
            if (wallet.getBalanceMinor() != expected) {
                mismatches.add(Mismatch.balanceDrift(walletId, wallet.getBalanceMinor(), expected,
                        debits, credits));
            }
            walletsChecked++;
        }

        if (globalDebits != globalCredits) {
            mismatches.add(Mismatch.globalDoubleEntryViolation(globalDebits, globalCredits));
        }

        // Sanity: any wallet with a non-zero balance but zero ledger entries.
        for (Wallet w : wallets) {
            if (w.getOwnerType() == OwnerType.SYSTEM) continue;
            if (walletsById.containsKey(w.getId())
                    && w.getBalanceMinor() != 0
                    && ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(w.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty()) {
                mismatches.add(Mismatch.unbackedBalance(w.getId(), w.getBalanceMinor()));
            }
        }

        report.setFinishedAt(Instant.now());
        report.setStatus(mismatches.isEmpty() ? ReconciliationStatus.BALANCED
                : ReconciliationStatus.MISMATCH);
        report.setWalletsChecked(walletsChecked);
        report.setTransactionsChecked((int) transactionRepository.count());
        report.setLedgerEntriesChecked((int) ledgerEntryRepository.count());
        report.setTotalDebitsMinor(globalDebits);
        report.setTotalCreditsMinor(globalCredits);
        report.setMismatchesJson(serialize(mismatches));

        ReconciliationReport saved = reportRepository.save(report);

        meterRegistry.counter("ledgerpay.reconciliation.runs.total",
                "status", saved.getStatus().name()).increment();
        meterRegistry.gauge("ledgerpay.reconciliation.last_run_mismatches",
                (double) mismatches.size());
        meterRegistry.gauge("ledgerpay.reconciliation.global_diff_minor",
                (double) (globalDebits - globalCredits));

        log.info("Reconciliation pass {} -> {} ({} wallets, {} ledger entries, {} mismatches)",
                saved.getId(), saved.getStatus(), saved.getWalletsChecked(),
                saved.getLedgerEntriesChecked(), mismatches.size());

        if (saved.getStatus() == ReconciliationStatus.MISMATCH) {
            log.error("Reconciliation MISMATCH detected: {}", mismatches);
        }
        return saved;
    }

    private String serialize(List<Mismatch> mismatches) {
        try {
            return objectMapper.writeValueAsString(mismatches);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize mismatches", e);
            return "[]";
        }
    }

    /**
     * Captures one specific anomaly. Designed as a flat record so it serializes to compact JSON
     * for the reconciliation report's {@code mismatches} column.
     */
    public record Mismatch(String kind, UUID walletId,
                           Long expectedMinor, Long actualMinor,
                           Long totalDebitsMinor, Long totalCreditsMinor) {

        static Mismatch balanceDrift(UUID walletId, long actual, long expected,
                                     long debits, long credits) {
            return new Mismatch("BALANCE_DRIFT", walletId, expected, actual, debits, credits);
        }

        static Mismatch globalDoubleEntryViolation(long debits, long credits) {
            return new Mismatch("GLOBAL_DOUBLE_ENTRY_VIOLATION", null,
                    credits, debits, debits, credits);
        }

        static Mismatch orphanLedgerEntries(UUID walletId, long debits, long credits) {
            return new Mismatch("ORPHAN_LEDGER_ENTRIES", walletId, null, null, debits, credits);
        }

        static Mismatch unbackedBalance(UUID walletId, long balance) {
            return new Mismatch("UNBACKED_BALANCE", walletId, 0L, balance, 0L, 0L);
        }
    }
}
