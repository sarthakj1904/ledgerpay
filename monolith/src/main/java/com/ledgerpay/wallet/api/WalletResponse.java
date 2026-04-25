package com.ledgerpay.wallet.api;

import java.util.UUID;

import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletStatus;

public record WalletResponse(
        UUID id,
        UUID ownerId,
        OwnerType ownerType,
        String currency,
        long balanceMinor,
        WalletStatus status
) {
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getId(), w.getOwnerId(), w.getOwnerType(),
                w.getCurrency(), w.getBalanceMinor(), w.getStatus());
    }
}
