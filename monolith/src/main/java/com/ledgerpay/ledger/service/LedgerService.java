package com.ledgerpay.ledger.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.common.exception.BadRequestException;
import com.ledgerpay.common.exception.InsufficientFundsException;
import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.common.exception.WalletFrozenException;
import com.ledgerpay.ledger.domain.EntryType;
import com.ledgerpay.ledger.domain.LedgerEntry;
import com.ledgerpay.ledger.domain.LedgerEntryRepository;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

/**
 * The heart of LedgerPay: posts a balanced set of double-entry legs against a
 * {@link PaymentTransaction} atomically with the wallet balance updates and the ledger rows.
 * <p>
 * Guarantees enforced inside {@link #post(PaymentTransaction, List)}:
 * <ul>
 *     <li>sum(debits) == sum(credits) — no money is created or destroyed.</li>
 *     <li>User and Merchant wallets can never go negative; SYSTEM wallets are allowed to.</li>
 *     <li>Frozen non-SYSTEM wallets reject any posting.</li>
 *     <li>All writes (wallet balances, ledger rows, txn status) commit together.</li>
 * </ul>
 * Optimistic-lock retries are handled by the caller because the caller also needs to write
 * outbox events inside the same transaction.
 */
@Service
public class LedgerService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentTransactionRepository transactionRepository;

    public LedgerService(WalletRepository walletRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         PaymentTransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTransaction post(PaymentTransaction transaction, List<LedgerLeg> legs) {
        assertBalanced(legs);
        if (legs.isEmpty()) {
            throw new BadRequestException("empty_posting", "At least one leg is required");
        }

        PaymentTransaction managed = transactionRepository.findById(transaction.getId())
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + transaction.getId()));

        Map<UUID, Wallet> wallets = loadWallets(legs);
        String expectedCurrency = managed.getCurrency();
        List<LedgerEntry> newEntries = new ArrayList<>(legs.size());

        for (LedgerLeg leg : legs) {
            Wallet w = wallets.get(leg.walletId());
            if (!w.isSystem() && w.isFrozen()) {
                throw new WalletFrozenException("Wallet " + w.getId() + " is frozen");
            }
            if (!w.getCurrency().equals(expectedCurrency)) {
                throw new BadRequestException("currency_mismatch",
                        "Wallet " + w.getId() + " currency " + w.getCurrency()
                                + " does not match transaction currency " + expectedCurrency);
            }
            long newBalance = leg.entryType() == EntryType.DEBIT
                    ? w.getBalanceMinor() - leg.amountMinor()
                    : w.getBalanceMinor() + leg.amountMinor();
            if (!w.isSystem() && newBalance < 0) {
                throw new InsufficientFundsException(
                        "Wallet " + w.getId() + " has insufficient funds for this operation");
            }
            w.setBalanceMinor(newBalance);
            newEntries.add(switch (leg.entryType()) {
                case DEBIT -> LedgerEntry.debit(managed.getId(), w.getId(),
                        leg.amountMinor(), newBalance, w.getCurrency());
                case CREDIT -> LedgerEntry.credit(managed.getId(), w.getId(),
                        leg.amountMinor(), newBalance, w.getCurrency());
            });
        }

        walletRepository.saveAll(wallets.values());
        ledgerEntryRepository.saveAll(newEntries);

        managed.setStatus(TransactionStatus.SUCCESS);
        return transactionRepository.save(managed);
    }

    private Map<UUID, Wallet> loadWallets(List<LedgerLeg> legs) {
        Map<UUID, Wallet> out = new HashMap<>();
        for (LedgerLeg leg : legs) {
            if (out.containsKey(leg.walletId())) continue;
            Wallet w = walletRepository.findById(leg.walletId())
                    .orElseThrow(() -> new NotFoundException("Wallet not found: " + leg.walletId()));
            out.put(w.getId(), w);
        }
        return out;
    }

    static void assertBalanced(List<LedgerLeg> legs) {
        long debits = 0;
        long credits = 0;
        for (LedgerLeg l : legs) {
            if (l.amountMinor() <= 0) {
                throw new BadRequestException("non_positive_amount", "Leg amount must be positive");
            }
            if (l.entryType() == EntryType.DEBIT) debits += l.amountMinor();
            else credits += l.amountMinor();
        }
        if (debits != credits) {
            throw new IllegalStateException(
                    "Double-entry invariant violated: debits=" + debits + " credits=" + credits);
        }
    }
}
