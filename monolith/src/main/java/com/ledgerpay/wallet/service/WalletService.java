package com.ledgerpay.wallet.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.auth.domain.MerchantRepository;
import com.ledgerpay.auth.domain.Role;
import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.auth.domain.UserRepository;
import com.ledgerpay.common.exception.ForbiddenException;
import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;
import com.ledgerpay.wallet.domain.WalletStatus;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;

    public WalletService(WalletRepository walletRepository,
                         UserRepository userRepository,
                         MerchantRepository merchantRepository) {
        this.walletRepository = walletRepository;
        this.merchantRepository = merchantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Wallet createWalletForUser(UUID userId, String currency) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        OwnerType ownerType = switch (user.getRole()) {
            case MERCHANT -> OwnerType.MERCHANT;
            case USER -> OwnerType.USER;
            case ADMIN -> throw new ForbiddenException("ADMIN accounts cannot own wallets");
        };
        UUID ownerId = userId;
        if (user.getRole() == Role.MERCHANT) {
            ownerId = merchantRepository.findByUserId(userId)
                    .orElseThrow(() -> new NotFoundException("Merchant profile not found"))
                    .getId();
        }
        Wallet wallet = Wallet.create(ownerId, ownerType, currency);
        return walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public Wallet get(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet not found: " + walletId));
    }

    @Transactional(readOnly = true)
    public Wallet requireOwnedBy(UUID walletId, UUID userId, String userRole) {
        Wallet w = get(walletId);
        if ("ADMIN".equals(userRole)) {
            return w;
        }
        UUID callerOwnerId = userId;
        if (w.getOwnerType() == OwnerType.MERCHANT) {
            callerOwnerId = merchantRepository.findByUserId(userId)
                    .map(m -> m.getId())
                    .orElse(null);
        }
        if (callerOwnerId == null || !callerOwnerId.equals(w.getOwnerId())) {
            throw new ForbiddenException("Wallet " + walletId + " is not owned by the caller");
        }
        return w;
    }

    @Transactional
    public Wallet setStatus(UUID walletId, WalletStatus status) {
        Wallet w = get(walletId);
        w.setStatus(status);
        return walletRepository.save(w);
    }
}
