package com.ledgerpay.common.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Column(name = "key", length = 128)
    private String key;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "response_body", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String responseBody;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static IdempotencyRecord create(String key, UUID userId, String method, String path,
                                           String requestHash, Instant expiresAt) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.key = key;
        r.userId = userId;
        r.method = method;
        r.path = path;
        r.requestHash = requestHash;
        r.status = IdempotencyStatus.IN_PROGRESS;
        r.createdAt = Instant.now();
        r.expiresAt = expiresAt;
        return r;
    }
}
