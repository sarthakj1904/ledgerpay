package com.ledgerpay.audit.domain;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "fraud_flags")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudFlag {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "txn_id", nullable = false)
    private UUID transactionId;

    @Column(name = "rule_name", nullable = false, length = 64)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private FraudSeverity severity;

    @Column(name = "details", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static FraudFlag create(UUID txnId, String ruleName, FraudSeverity severity, String details) {
        FraudFlag f = new FraudFlag();
        f.id = UUID.randomUUID();
        f.transactionId = txnId;
        f.ruleName = ruleName;
        f.severity = severity;
        f.details = details == null ? "{}" : details;
        return f;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
