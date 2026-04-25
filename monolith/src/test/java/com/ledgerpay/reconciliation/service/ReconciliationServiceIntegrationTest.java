package com.ledgerpay.reconciliation.service;

import java.util.UUID;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.payment.api.AddMoneyRequest;
import com.ledgerpay.payment.service.PaymentService;
import com.ledgerpay.reconciliation.domain.ReconciliationReport;
import com.ledgerpay.reconciliation.domain.ReconciliationStatus;
import com.ledgerpay.testsupport.IntegrationTestBase;
import com.ledgerpay.testsupport.TestDataFactory;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationServiceIntegrationTest extends IntegrationTestBase {

    @Autowired ReconciliationService reconciliationService;
    @Autowired PaymentService paymentService;
    @Autowired WalletRepository walletRepository;
    @Autowired TestDataFactory fixtures;
    @Autowired TransactionTemplate txTemplate;

    // The two tests share the JVM-wide Postgres container and intentionally run in order:
    // the tamper test mutates state that would poison a later "clean" check.
    @Test
    @Order(1)
    void cleanLedgerProducesBalancedReport() {
        UserAccount user = fixtures.newUser();
        Wallet wallet = fixtures.newWallet(user.getId(), OwnerType.USER);

        paymentService.addMoney(
                "idem-" + UUID.randomUUID(),
                user.getId(),
                "127.0.0.1",
                new AddMoneyRequest(wallet.getId(), 1_000_00L, "INR"));

        ReconciliationReport report = reconciliationService.runOnce();

        assertThat(report.getStatus()).isEqualTo(ReconciliationStatus.BALANCED);
        assertThat(report.getTotalDebitsMinor())
                .as("debits and credits must reconcile globally")
                .isEqualTo(report.getTotalCreditsMinor());
        assertThat(report.getMismatchesJson()).isEqualTo("[]");
        assertThat(report.getWalletsChecked()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void manualBalanceTamperingIsDetectedAsMismatch() {
        UserAccount user = fixtures.newUser();
        Wallet wallet = fixtures.newWallet(user.getId(), OwnerType.USER);

        paymentService.addMoney(
                "idem-" + UUID.randomUUID(),
                user.getId(),
                "127.0.0.1",
                new AddMoneyRequest(wallet.getId(), 500_00L, "INR"));

        // Simulate a rogue write that bypasses the ledger by directly mutating a wallet balance.
        // The reconciliation engine must detect that the wallet balance no longer matches its
        // immutable ledger sum.
        txTemplate.executeWithoutResult(s -> {
            Wallet w = walletRepository.findById(wallet.getId()).orElseThrow();
            w.setBalanceMinor(w.getBalanceMinor() + 99_99L);
            walletRepository.save(w);
        });

        ReconciliationReport report = reconciliationService.runOnce();

        assertThat(report.getStatus()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(report.getMismatchesJson()).contains("BALANCE_DRIFT");
    }
}
