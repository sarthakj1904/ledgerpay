package com.ledgerpay.payment.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.ledgerpay.auth.domain.Merchant;
import com.ledgerpay.auth.domain.MerchantRepository;
import com.ledgerpay.common.exception.BadRequestException;
import com.ledgerpay.common.exception.DomainException;
import com.ledgerpay.common.exception.ForbiddenException;
import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.ledger.domain.TransactionType;
import com.ledgerpay.ledger.service.LedgerLeg;
import com.ledgerpay.payment.api.AddMoneyRequest;
import com.ledgerpay.payment.api.MerchantPayRequest;
import com.ledgerpay.payment.api.RefundRequest;
import com.ledgerpay.payment.api.TransferRequest;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

/**
 * Orchestrates payment flows: creates the PENDING transaction, invokes the ledger engine, handles
 * retry on optimistic-lock contention, and records terminal status + metrics.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTxOperations tx;
    private final PaymentTransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentMetrics metrics;
    private final UUID suspenseWalletId;
    private final int maxPostRetries;

    public PaymentService(PaymentTxOperations tx,
                          PaymentTransactionRepository transactionRepository,
                          WalletRepository walletRepository,
                          MerchantRepository merchantRepository,
                          PaymentMetrics metrics,
                          @Value("${ledgerpay.system.suspense-wallet-id}") UUID suspenseWalletId,
                          @Value("${ledgerpay.payment.max-optimistic-retries:8}") int maxPostRetries) {
        this.tx = tx;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.merchantRepository = merchantRepository;
        this.metrics = metrics;
        this.suspenseWalletId = suspenseWalletId;
        this.maxPostRetries = maxPostRetries;
    }

    public PaymentTransaction addMoney(String idempotencyKey,
                                       UUID callerId,
                                       String clientIp,
                                       AddMoneyRequest req) {
        Wallet dest = walletRepository.findById(req.walletId())
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + req.walletId()));
        requireWalletOwnedByUser(dest, callerId);

        PaymentTransaction pending = tx.createPending(PaymentTransaction.newPending(
                idempotencyKey, TransactionType.ADD_MONEY,
                null, dest.getId(), req.amountMinor(), req.currencyOrDefault(),
                callerId, clientIp));

        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(suspenseWalletId, req.amountMinor()),
                LedgerLeg.credit(dest.getId(), req.amountMinor())
        );
        return executePosting(pending, legs);
    }

    public PaymentTransaction transfer(String idempotencyKey,
                                       UUID callerId,
                                       String clientIp,
                                       TransferRequest req) {
        if (req.fromWalletId().equals(req.toWalletId())) {
            throw new BadRequestException("self_transfer", "Source and destination wallets must differ");
        }
        Wallet from = walletRepository.findById(req.fromWalletId())
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + req.fromWalletId()));
        Wallet to = walletRepository.findById(req.toWalletId())
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + req.toWalletId()));
        requireWalletOwnedByUser(from, callerId);
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BadRequestException("currency_mismatch",
                    "Source and destination wallet currencies do not match");
        }

        PaymentTransaction pending = tx.createPending(PaymentTransaction.newPending(
                idempotencyKey, TransactionType.TRANSFER,
                from.getId(), to.getId(), req.amountMinor(), from.getCurrency(),
                callerId, clientIp));

        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(from.getId(), req.amountMinor()),
                LedgerLeg.credit(to.getId(), req.amountMinor())
        );
        return executePosting(pending, legs);
    }

    public PaymentTransaction merchantPay(String idempotencyKey,
                                          UUID callerId,
                                          String clientIp,
                                          MerchantPayRequest req) {
        Wallet from = walletRepository.findById(req.fromWalletId())
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + req.fromWalletId()));
        requireWalletOwnedByUser(from, callerId);
        Merchant merchant = merchantRepository.findById(req.merchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + req.merchantId()));
        Wallet merchantWallet = walletRepository
                .findByOwnerIdAndOwnerType(merchant.getId(), OwnerType.MERCHANT)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Merchant has no wallet yet"));
        if (!from.getCurrency().equals(merchantWallet.getCurrency())) {
            throw new BadRequestException("currency_mismatch", "Wallet currencies do not match");
        }

        PaymentTransaction pending = tx.createPending(PaymentTransaction.newPending(
                idempotencyKey, TransactionType.MERCHANT_PAY,
                from.getId(), merchantWallet.getId(), req.amountMinor(),
                from.getCurrency(), callerId, clientIp));

        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(from.getId(), req.amountMinor()),
                LedgerLeg.credit(merchantWallet.getId(), req.amountMinor())
        );
        return executePosting(pending, legs);
    }

    public PaymentTransaction refund(String idempotencyKey,
                                     UUID callerId,
                                     String clientIp,
                                     RefundRequest req) {
        PaymentTransaction parent = transactionRepository.findById(req.transactionId())
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + req.transactionId()));
        if (parent.getStatus() != TransactionStatus.SUCCESS) {
            throw new BadRequestException("non_refundable", "Only SUCCESS transactions can be refunded");
        }
        if (parent.getType() == TransactionType.REFUND) {
            throw new BadRequestException("double_refund", "A refund cannot itself be refunded");
        }
        long alreadyRefunded = transactionRepository.findByParentTransactionId(parent.getId()).stream()
                .filter(t -> t.getStatus() != TransactionStatus.FAILED)
                .mapToLong(PaymentTransaction::getAmountMinor)
                .sum();
        if (alreadyRefunded + req.amountMinor() > parent.getAmountMinor()) {
            throw new BadRequestException("refund_exceeds_original",
                    "Refund would exceed original amount (already refunded "
                            + alreadyRefunded + " of " + parent.getAmountMinor() + ")");
        }
        if (parent.getSourceWalletId() != null) {
            Wallet source = walletRepository.findById(parent.getSourceWalletId())
                    .orElseThrow(() -> new NotFoundException("Source wallet missing"));
            requireWalletOwnedByUser(source, callerId);
        }

        UUID refundSource = parent.getDestWalletId();
        UUID refundDest = parent.getSourceWalletId();
        if (refundSource == null || refundDest == null) {
            throw new BadRequestException("non_refundable",
                    "Parent transaction has no counterparty wallet to refund to");
        }

        PaymentTransaction newTxn = PaymentTransaction.newPending(
                idempotencyKey, TransactionType.REFUND,
                refundSource, refundDest, req.amountMinor(), parent.getCurrency(),
                callerId, clientIp);
        newTxn.setParentTransactionId(parent.getId());
        PaymentTransaction pending = tx.createPending(newTxn);

        List<LedgerLeg> legs = List.of(
                LedgerLeg.debit(refundSource, req.amountMinor()),
                LedgerLeg.credit(refundDest, req.amountMinor())
        );
        return executePosting(pending, legs);
    }

    private PaymentTransaction executePosting(PaymentTransaction pending, List<LedgerLeg> legs) {
        long start = System.nanoTime();
        OptimisticLockingFailureException lastOptimistic = null;
        for (int attempt = 1; attempt <= maxPostRetries; attempt++) {
            try {
                PaymentTransaction result = tx.post(pending, legs);
                metrics.recordOutcome(result.getType(), result.getStatus(), System.nanoTime() - start);
                return result;
            } catch (OptimisticLockingFailureException ex) {
                lastOptimistic = ex;
                log.debug("Optimistic lock contention on txn {} (attempt {}/{})",
                        pending.getId(), attempt, maxPostRetries);
                sleepBackoff(attempt);
            } catch (DomainException de) {
                PaymentTransaction failed = tx.markFailed(pending.getId(), de.getMessage());
                metrics.recordOutcome(failed.getType(), failed.getStatus(), System.nanoTime() - start);
                throw de;
            }
        }
        tx.markFailed(pending.getId(), "Optimistic-lock retries exhausted");
        throw lastOptimistic;
    }

    private void requireWalletOwnedByUser(Wallet wallet, UUID callerUserId) {
        UUID expectedOwner = switch (wallet.getOwnerType()) {
            case USER -> callerUserId;
            case MERCHANT -> merchantRepository.findByUserId(callerUserId)
                    .map(Merchant::getId).orElse(null);
            case SYSTEM -> null;
        };
        if (expectedOwner == null || !expectedOwner.equals(wallet.getOwnerId())) {
            throw new ForbiddenException("Caller does not own wallet " + wallet.getId());
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(10L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
