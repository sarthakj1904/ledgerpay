package com.ledgerpay.payment.service;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.common.exception.InsufficientFundsException;
import com.ledgerpay.ledger.domain.EntryType;
import com.ledgerpay.ledger.domain.LedgerEntry;
import com.ledgerpay.ledger.domain.LedgerEntryRepository;
import com.ledgerpay.payment.api.TransferRequest;
import com.ledgerpay.testsupport.IntegrationTestBase;
import com.ledgerpay.testsupport.TestDataFactory;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires 100 concurrent transfers from a single wallet and asserts the two non-negotiable
 * invariants of the ledger engine:
 * <ul>
 *     <li>the wallet balance is never driven negative,</li>
 *     <li>sum(debits) == sum(credits) across every posted ledger entry,</li>
 *     <li>net outflow from the sender equals net inflow into the receiver, and both
 *         equal the number of successful transfers times the per-transfer amount.</li>
 * </ul>
 * Under extreme same-wallet contention (20 threads, 100 requests), some transfers will
 * exhaust the 3-retry optimistic-lock budget and surface as
 * {@link OptimisticLockingFailureException}; that is a valid operational outcome and
 * does not violate any invariant (the transaction is marked FAILED with no ledger legs).
 */
class ConcurrentTransferTest extends IntegrationTestBase {

    @Autowired PaymentService paymentService;
    @Autowired WalletRepository walletRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TestDataFactory fixtures;

    @Test
    void manyParallelTransfersConserveValueAndPreventNegativeBalance() throws Exception {
        int threads = 20;
        int requests = 100;
        long initial = 1_000_00L;
        long perTransfer = 15_00L;

        UserAccount sender = fixtures.newUser();
        UserAccount receiver = fixtures.newUser();
        Wallet from = fixtures.fundedWallet(sender.getId(), OwnerType.USER, initial);
        Wallet to = fixtures.newWallet(receiver.getId(), OwnerType.USER);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger optimisticLock = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    paymentService.transfer(
                            "idem-" + UUID.randomUUID(),
                            sender.getId(),
                            "127.0.0.1",
                            new TransferRequest(from.getId(), to.getId(), perTransfer));
                    successes.incrementAndGet();
                } catch (InsufficientFundsException ife) {
                    insufficient.incrementAndGet();
                } catch (OptimisticLockingFailureException ole) {
                    optimisticLock.incrementAndGet();
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(120, TimeUnit.SECONDS);
        assertThat(done).isTrue();

        Wallet fromFinal = walletRepository.findById(from.getId()).orElseThrow();
        Wallet toFinal = walletRepository.findById(to.getId()).orElseThrow();

        assertThat(fromFinal.getBalanceMinor())
                .as("sender wallet must never drop below zero")
                .isGreaterThanOrEqualTo(0L);

        // The only invariant that truly matters: net outflow = net inflow = successful
        // transfers * amount. If this holds, no money was created or destroyed by the
        // concurrent writes — even with optimistic-lock retries firing under heavy contention.
        assertThat(successes.get() * perTransfer)
                .as("net outflow equals net inflow equals successes*amount")
                .isEqualTo(initial - fromFinal.getBalanceMinor())
                .isEqualTo(toFinal.getBalanceMinor());

        assertThat(unexpected.get())
                .as("only InsufficientFunds / OptimisticLock are acceptable failure modes")
                .isZero();

        long debits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor).sum();
        long credits = ledgerEntryRepository.findAll().stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor).sum();
        assertThat(debits).isEqualTo(credits);

        assertThat(successes.get() + insufficient.get() + optimisticLock.get())
                .as("every request ended in a known outcome")
                .isEqualTo(requests);
    }
}
