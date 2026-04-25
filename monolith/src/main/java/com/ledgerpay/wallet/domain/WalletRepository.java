package com.ledgerpay.wallet.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    List<Wallet> findByOwnerIdAndOwnerType(UUID ownerId, OwnerType ownerType);
}
