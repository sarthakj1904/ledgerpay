package com.ledgerpay.common.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ledgerpay.common.exception.ForbiddenException;

public final class CurrentUser {

    private CurrentUser() {}

    public static AuthenticatedUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUser u)) {
            throw new ForbiddenException("Authentication required");
        }
        return u;
    }

    public static UUID requireId() {
        return require().id();
    }
}
