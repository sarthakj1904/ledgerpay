package com.ledgerpay.common.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, String role) {}
