package com.ledgerpay.auth.service;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerpay.auth.api.AuthResponse;
import com.ledgerpay.auth.api.LoginRequest;
import com.ledgerpay.auth.api.MerchantResponse;
import com.ledgerpay.auth.api.RegisterRequest;
import com.ledgerpay.auth.domain.Merchant;
import com.ledgerpay.auth.domain.MerchantRepository;
import com.ledgerpay.auth.domain.Role;
import com.ledgerpay.auth.domain.UserAccount;
import com.ledgerpay.auth.domain.UserRepository;
import com.ledgerpay.common.exception.BadRequestException;
import com.ledgerpay.common.exception.ConflictException;
import com.ledgerpay.common.security.JwtService;

@Service
public class AuthService {

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       MerchantRepository merchantRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.merchantRepository = merchantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email().toLowerCase())) {
            throw new ConflictException("email_taken", "Email already registered");
        }
        if (req.role() == Role.ADMIN) {
            throw new BadRequestException("admin_registration_forbidden",
                    "ADMIN accounts cannot be self-registered");
        }
        if (req.role() == Role.MERCHANT && (req.businessName() == null || req.businessName().isBlank())) {
            throw new BadRequestException("business_name_required",
                    "businessName is required for MERCHANT role");
        }

        UserAccount user = UserAccount.create(req.email(), passwordEncoder.encode(req.password()), req.role());
        userRepository.save(user);

        if (req.role() == Role.MERCHANT) {
            Merchant m = Merchant.create(user.getId(), req.businessName(),
                    randomToken(32), randomToken(32));
            m.setWebhookUrl(req.webhookUrl());
            merchantRepository.save(m);
        }

        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        UserAccount user = userRepository.findByEmail(req.email().toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchantForUser(java.util.UUID userId) {
        Merchant m = merchantRepository.findByUserId(userId)
                .orElseThrow(() -> new com.ledgerpay.common.exception.NotFoundException(
                        "Merchant profile not found for user"));
        return new MerchantResponse(m.getId(), m.getUserId(), m.getBusinessName(),
                m.getApiKey(), m.getWebhookUrl());
    }

    private AuthResponse buildAuthResponse(UserAccount user) {
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getRole().name());
        return new AuthResponse(user.getId(), user.getEmail(), user.getRole().name(),
                token, jwtService.ttlSeconds());
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
