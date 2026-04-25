package com.ledgerpay.auth.api;

import java.util.UUID;

public record MerchantResponse(UUID id, UUID userId, String businessName, String apiKey, String webhookUrl) {}
