package com.ledgerpay.testsupport;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.auth.domain.Role;
import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.auth.domain.UserRepository;
import com.ledgerpay.wallet.domain.OwnerType;
import com.ledgerpay.wallet.domain.Wallet;
import com.ledgerpay.wallet.domain.WalletRepository;

@Component
public class TestDataFactory {

    @Autowired UserRepository userRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Transactional
    public UserAccount newUser() {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        UserAccount u = UserAccount.create(email, passwordEncoder.encode("password123"), Role.USER);
        return userRepository.save(u);
    }

    @Transactional
    public Wallet newWallet(UUID ownerId, OwnerType type) {
        return walletRepository.save(Wallet.create(ownerId, type, "INR"));
    }

    @Transactional
    public Wallet fundedWallet(UUID ownerId, OwnerType type, long balanceMinor) {
        Wallet w = walletRepository.save(Wallet.create(ownerId, type, "INR"));
        w.setBalanceMinor(balanceMinor);
        return walletRepository.save(w);
    }
}
