package com.ledgerpay.auth.api;

import java.util.UUID;

public record AuthResponse(UUID userId, String email, String role, String accessToken, long expiresInSeconds) {}
