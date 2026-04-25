package com.ledgerpay.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select t from PaymentTransaction t
             where t.sourceWalletId = :walletId or t.destWalletId = :walletId
             order by t.createdAt desc
            """)
    Page<PaymentTransaction> findByWallet(@Param("walletId") UUID walletId, Pageable pageable);

    List<PaymentTransaction> findByParentTransactionId(UUID parentTransactionId);
}
