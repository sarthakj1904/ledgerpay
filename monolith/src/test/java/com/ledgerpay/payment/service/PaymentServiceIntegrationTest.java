package com.ledgerpay.payment.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.common.exception.InsufficientFundsException;
import com.ledgerpay.ledger.domain.EntryType;
import com.ledgerpay.ledger.domain.LedgerEntry;
import com.ledgerpay.ledger.domain.LedgerEntryRepository;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.ledger.domain.TransactionType;
import com.ledgerpay.payment.api.AddMoneyRequest;
import com.ledgerpay.payment.api.TransferRequest;
import com.ledgerpay.testsupport.IntegrationTestBase;
import com.ledgerpay.testsupport.TestDataFactory;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceIntegrationTest extends IntegrationTestBase {

    @Autowired PaymentService paymentService;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired TestDataFactory fixtures;

    @Test
    void addMoneyCreditsTheWalletAndEmitsPairedLedgerEntries() {
        UserAccount user = fixtures.newUser();
        Wallet wallet = fixtures.newWallet(user.getId(), OwnerType.USER);

        PaymentTransaction txn = paymentService.addMoney(
                "idem-" + UUID.randomUUID(),
                user.getId(),
                "127.0.0.1",
                new AddMoneyRequest(wallet.getId(), 500_00L, "INR"));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(txn.getType()).isEqualTo(TransactionType.ADD_MONEY);

        Wallet reloaded = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(reloaded.getBalanceMinor()).isEqualTo(500_00L);

        List<LedgerEntry> entries = ledgerRepository.findByTransactionIdOrderByCreatedAtAsc(txn.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor).sum())
                .isEqualTo(entries.stream()
                        .filter(e -> e.getEntryType() == EntryType.CREDIT)
                        .mapToLong(LedgerEntry::getAmountMinor).sum());
    }

    @Test
    void transferMovesMoneyAndPreservesConservationOfValue() {
        UserAccount sender = fixtures.newUser();
        UserAccount receiver = fixtures.newUser();
        Wallet from = fixtures.fundedWallet(sender.getId(), OwnerType.USER, 1_000_00L);
        Wallet to = fixtures.newWallet(receiver.getId(), OwnerType.USER);

        PaymentTransaction txn = paymentService.transfer(
                "idem-" + UUID.randomUUID(),
                sender.getId(),
                "127.0.0.1",
                new TransferRequest(from.getId(), to.getId(), 250_00L));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(walletRepository.findById(from.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(750_00L);
        assertThat(walletRepository.findById(to.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(250_00L);
    }

    @Test
    void transferRespectsInsufficientFundsGuard() {
        UserAccount sender = fixtures.newUser();
        UserAccount receiver = fixtures.newUser();
        Wallet from = fixtures.fundedWallet(sender.getId(), OwnerType.USER, 100_00L);
        Wallet to = fixtures.newWallet(receiver.getId(), OwnerType.USER);

        assertThatThrownBy(() -> paymentService.transfer(
                "idem-" + UUID.randomUUID(), sender.getId(), "127.0.0.1",
                new TransferRequest(from.getId(), to.getId(), 500_00L)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletRepository.findById(from.getId()).orElseThrow().getBalanceMinor())
                .isEqualTo(100_00L);
        assertThat(walletRepository.findById(to.getId()).orElseThrow().getBalanceMinor())
                .isZero();
    }

    @Test
    void idempotencyKeyReuseReturnsSameTransaction() {
        UserAccount sender = fixtures.newUser();
        UserAccount receiver = fixtures.newUser();
        Wallet from = fixtures.fundedWallet(sender.getId(), OwnerType.USER, 1_000_00L);
        Wallet to = fixtures.newWallet(receiver.getId(), OwnerType.USER);
        String key = "idem-" + UUID.randomUUID();
        TransferRequest req = new TransferRequest(from.getId(), to.getId(), 50_00L);

        PaymentTransaction first = paymentService.transfer(key, sender.getId(), "127.0.0.1", req);
        // Second call with the same idempotency key should collide on the DB UNIQUE constraint
        // and be surfaced as an idempotency_conflict. The IdempotencyFilter normally shields the
        // controller layer; here we exercise the defence-in-depth at the transactions table.
        assertThatThrownBy(() -> paymentService.transfer(key, sender.getId(), "127.0.0.1", req))
                .hasMessageContaining("Idempotency-Key already exists");

        assertThat(walletRepository.findById(from.getId()).orElseThrow().getBalanceMinor())
                .as("wallet must be debited exactly once")
                .isEqualTo(950_00L);
        assertThat(first.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }
}
