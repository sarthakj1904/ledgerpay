package com.ledgerpay.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.common.exception.ConflictException;

@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRepository repository,
                              @Value("${ledgerpay.idempotency.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.ttl = Duration.ofHours(ttlHours);
    }

    /**
     * Tries to reserve an idempotency key for this request. Returns the existing completed record
     * if this is a successful replay (same request hash). Throws {@link ConflictException} if the
     * key was used before with a different payload, or if a prior request is still in-flight.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyResult beginOrReplay(String key,
                                           UUID userId,
                                           String method,
                                           String path,
                                           String requestBody) {
        String hash = sha256(userId, method, path, requestBody);
        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord r = existing.get();
            if (!r.getRequestHash().equals(hash)) {
                throw new ConflictException("idempotency_conflict",
                        "Idempotency-Key reused with a different request payload");
            }
            if (r.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new ConflictException("idempotency_in_progress",
                        "A request with this Idempotency-Key is already in progress");
            }
            return IdempotencyResult.replay(r.getResponseStatus(), r.getResponseBody());
        }

        try {
            repository.save(IdempotencyRecord.create(key, userId, method, path, hash,
                    Instant.now().plus(ttl)));
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert — fall through to replay path.
            return beginOrReplay(key, userId, method, path, requestBody);
        }
        return IdempotencyResult.fresh();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int status, String responseBody) {
        repository.findById(key).ifPresent(r -> {
            r.setStatus(IdempotencyStatus.COMPLETED);
            r.setResponseStatus(status);
            r.setResponseBody(responseBody);
            repository.save(r);
        });
    }

    private static String sha256(UUID userId, String method, String path, String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((userId == null ? "" : userId.toString()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(method.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record IdempotencyResult(boolean replay, Integer status, String body) {
        public static IdempotencyResult fresh() {
            return new IdempotencyResult(false, null, null);
        }
        public static IdempotencyResult replay(Integer status, String body) {
            return new IdempotencyResult(true, status, body);
        }
    }
}
