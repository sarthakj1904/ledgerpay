package com.ledgerpay.common.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.ledgerpay.common.exception.ConflictException;
import com.ledgerpay.common.idempotency.IdempotencyService.IdempotencyResult;
import com.ledgerpay.common.security.AuthenticatedUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enforces request idempotency for POST endpoints under {@code /api/v1/payments/**}. Requires an
 * {@code Idempotency-Key} header. Replays of the same key return the cached response body and
 * status; replays with a different payload are rejected with 409.
 */
@Component
// Runs AFTER the Spring Security filter chain so we can attribute the idempotency key to the caller.
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER = "Idempotency-Key";
    private static final String PATH_PREFIX = "/api/v1/payments/";

    private final IdempotencyService idempotencyService;

    public IdempotencyFilter(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"title":"missing_idempotency_key","detail":"Idempotency-Key header is required"}""");
            return;
        }
        if (key.length() > 128) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"title":"invalid_idempotency_key","detail":"Idempotency-Key too long (max 128)"}""");
            return;
        }

        // CachedBodyHttpServletRequest reads the body once and replays it on every
        // getInputStream() call so the controller sees the same payload after we hash it.
        CachedBodyHttpServletRequest req = new CachedBodyHttpServletRequest(request);
        String bodyString = new String(req.getCachedBody(), StandardCharsets.UTF_8);
        UUID userId = currentUserIdOrNull();

        IdempotencyResult result;
        try {
            result = idempotencyService.beginOrReplay(key, userId, req.getMethod(),
                    req.getRequestURI(), bodyString);
        } catch (ConflictException ce) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"title":"%s","detail":"%s"}""".formatted(ce.getCode(), ce.getMessage()));
            return;
        }

        if (result.replay()) {
            response.setStatus(result.status() == null ? 200 : result.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Idempotent-Replay", "true");
            if (result.body() != null) {
                response.getWriter().write(result.body());
            }
            return;
        }

        ContentCachingResponseWrapper resp = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(req, resp);
        } finally {
            byte[] responseBody = resp.getContentAsByteArray();
            int status = resp.getStatus();
            resp.copyBodyToResponse();
            if (status >= 200 && status < 300) {
                idempotencyService.complete(key, status, new String(responseBody, StandardCharsets.UTF_8));
            }
        }
    }

    private static UUID currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return u.id();
        }
        return null;
    }
}
