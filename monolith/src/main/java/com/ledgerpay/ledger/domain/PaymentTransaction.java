package com.ledgerpay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "source_wallet_id")
    private UUID sourceWalletId;

    @Column(name = "dest_wallet_id")
    private UUID destWalletId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "parent_txn_id")
    private UUID parentTransactionId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "initiator_user_id")
    private UUID initiatorUserId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadataJson;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static PaymentTransaction newPending(String idempotencyKey,
                                                TransactionType type,
                                                UUID sourceWalletId,
                                                UUID destWalletId,
                                                long amountMinor,
                                                String currency,
                                                UUID initiatorUserId,
                                                String clientIp) {
        PaymentTransaction t = new PaymentTransaction();
        t.id = UUID.randomUUID();
        t.idempotencyKey = idempotencyKey;
        t.type = type;
        t.status = TransactionStatus.PENDING;
        t.sourceWalletId = sourceWalletId;
        t.destWalletId = destWalletId;
        t.amountMinor = amountMinor;
        t.currency = currency;
        t.initiatorUserId = initiatorUserId;
        t.clientIp = clientIp;
        t.metadataJson = "{}";
        t.riskScore = 0;
        return t;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
