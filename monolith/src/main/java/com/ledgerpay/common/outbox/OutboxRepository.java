package com.ledgerpay.common.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetches pending events with a PostgreSQL {@code FOR UPDATE SKIP LOCKED} so multiple
     * publisher instances running in parallel never pick the same row. The locks are released
     * on transaction commit/rollback.
     */
    @Query(value = """
            SELECT * FROM outbox
             WHERE status = 'PENDING'
             ORDER BY created_at
             FOR UPDATE SKIP LOCKED
             LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    @Query("select o from OutboxEvent o where o.status = :status order by o.createdAt desc")
    List<OutboxEvent> findByStatus(@Param("status") OutboxStatus status, Pageable pageable);
}
