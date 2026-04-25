package com.ledgerpay.payment.service;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.auth.domain.Merchant;
import com.ledgerpay.auth.domain.MerchantRepository;
import com.ledgerpay.common.event.EventType;
import com.ledgerpay.common.event.Topics;
import com.ledgerpay.common.exception.ConflictException;
import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.common.outbox.OutboxWriter;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.ledger.domain.TransactionStatus;
import com.ledgerpay.ledger.domain.TransactionType;
import com.ledgerpay.ledger.service.LedgerLeg;
import com.ledgerpay.ledger.service.LedgerService;
import com.ledgerpay.payment.event.PaymentEventPayload;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

/**
 * Transactional helpers invoked from {@link PaymentService}. Isolated into its own bean so that
 * the {@code @Transactional} annotations are always honoured via the Spring proxy.
 */
@Service
public class PaymentTxOperations {

    private final LedgerService ledgerService;
    private final PaymentTransactionRepository transactionRepository;
    private final OutboxWriter outboxWriter;
    private final Topics topics;
    private final WalletRepository walletRepository;
    private final MerchantRepository merchantRepository;

    public PaymentTxOperations(LedgerService ledgerService,
                               PaymentTransactionRepository transactionRepository,
                               OutboxWriter outboxWriter,
                               Topics topics,
                               WalletRepository walletRepository,
                               MerchantRepository merchantRepository) {
        this.ledgerService = ledgerService;
        this.transactionRepository = transactionRepository;
        this.outboxWriter = outboxWriter;
        this.topics = topics;
        this.walletRepository = walletRepository;
        this.merchantRepository = merchantRepository;
    }

    /**
     * Persist the PENDING transaction + PAYMENT_CREATED / REFUND_INITIATED outbox row in its own
     * DB transaction so we always have a durable record, even if later steps fail or the JVM dies.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction createPending(PaymentTransaction txn) {
        try {
            // saveAndFlush forces the INSERT immediately so the UNIQUE(idempotency_key)
            // constraint violation is surfaced inside this method (where we can catch it)
            // rather than during the outer commit.
            PaymentTransaction saved = transactionRepository.saveAndFlush(txn);
            String eventType = saved.getType() == TransactionType.REFUND
                    ? EventType.REFUND_INITIATED
                    : EventType.PAYMENT_CREATED;
            outboxWriter.write(topics.paymentEvents(), "transaction",
                    saved.getId().toString(), eventType, buildPayload(saved));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("idempotency_conflict",
                    "Transaction with this Idempotency-Key already exists");
        }
    }

    /**
     * Post the legs against the ledger + emit PAYMENT_SUCCESS in one atomic DB transaction. An
     * {@code OptimisticLockingFailureException} escapes so the caller can retry the posting.
     */
    @Transactional
    public PaymentTransaction post(PaymentTransaction pending, List<LedgerLeg> legs) {
        PaymentTransaction result = ledgerService.post(pending, legs);
        outboxWriter.write(topics.paymentEvents(), "transaction",
                result.getId().toString(), EventType.PAYMENT_SUCCESS,
                buildPayload(result));
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction markFailed(UUID txnId, String reason) {
        PaymentTransaction t = transactionRepository.findById(txnId)
                .orElseThrow(() -> new NotFoundException("Transaction missing: " + txnId));
        t.setStatus(TransactionStatus.FAILED);
        t.setFailureReason(truncate(reason));
        PaymentTransaction saved = transactionRepository.save(t);
        outboxWriter.write(topics.paymentEvents(), "transaction",
                saved.getId().toString(), EventType.PAYMENT_FAILED,
                buildPayload(saved));
        return saved;
    }

    /**
     * Builds the event payload, attaching merchant webhook coordinates whenever the counterparty
     * is a merchant wallet. Direction depends on transaction type: MERCHANT_PAY targets the
     * destination merchant; REFUND originates from the merchant wallet (the dest of the parent).
     */
    private PaymentEventPayload buildPayload(PaymentTransaction txn) {
        UUID counterpartyWalletId = switch (txn.getType()) {
            case MERCHANT_PAY -> txn.getDestWalletId();
            case REFUND -> txn.getSourceWalletId();
            case TRANSFER, ADD_MONEY -> null;
        };
        if (counterpartyWalletId == null) {
            return PaymentEventPayload.from(txn);
        }
        Wallet wallet = walletRepository.findById(counterpartyWalletId).orElse(null);
        if (wallet == null || wallet.getOwnerType() != OwnerType.MERCHANT) {
            return PaymentEventPayload.from(txn);
        }
        Merchant merchant = merchantRepository.findById(wallet.getOwnerId()).orElse(null);
        if (merchant == null) {
            return PaymentEventPayload.from(txn);
        }
        return PaymentEventPayload.from(
                txn,
                merchant.getId(),
                merchant.getWebhookUrl(),
                merchant.getWebhookSecret()
        );
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
