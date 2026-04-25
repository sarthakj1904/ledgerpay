package com.ledgerpay.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerpay.jwt")
public record JwtProperties(
        String secret,
        long ttlMinutes,
        String issuer
) {}
