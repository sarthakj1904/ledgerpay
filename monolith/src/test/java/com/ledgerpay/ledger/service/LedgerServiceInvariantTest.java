package com.ledgerpay.ledger.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ledgerpay.common.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level tests for the fundamental double-entry invariant enforced by
 * {@link LedgerService#assertBalanced(List)}. Reaching a package-private method lets us exercise
 * the pure rule without spinning up the JPA stack.
 */
class LedgerServiceInvariantTest {

    private final UUID walletA = UUID.randomUUID();
    private final UUID walletB = UUID.randomUUID();

    @Test
    void balancedPostingPasses() {
        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(walletA, 1000),
                LedgerLeg.credit(walletB, 1000)
        );
        LedgerService.assertBalanced(legs);
    }

    @Test
    void balancedMultilegPostingPasses() {
        UUID walletC = UUID.randomUUID();
        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(walletA, 700),
                LedgerLeg.debit(walletB, 300),
                LedgerLeg.credit(walletC, 1000)
        );
        LedgerService.assertBalanced(legs);
    }

    @Test
    void unbalancedPostingIsRejected() {
        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(walletA, 1000),
                LedgerLeg.credit(walletB, 999)
        );
        assertThatThrownBy(() -> LedgerService.assertBalanced(legs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Double-entry invariant violated");
    }

    @Test
    void nonPositiveAmountIsRejected() {
        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(walletA, 0),
                LedgerLeg.credit(walletB, 0)
        );
        assertThatThrownBy(() -> LedgerService.assertBalanced(legs))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void legConstructorsPreserveType() {
        assertThat(LedgerLeg.debit(walletA, 100).entryType())
                .isEqualTo(com.ledgerpay.ledger.domain.EntryType.DEBIT);
        assertThat(LedgerLeg.credit(walletA, 100).entryType())
                .isEqualTo(com.ledgerpay.ledger.domain.EntryType.CREDIT);
    }
}
