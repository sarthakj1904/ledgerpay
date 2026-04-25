package com.ledgerpay.notification.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Component
public class WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookClient.class);

    private final WebClient webClient;
    private final int maxAttempts;

    public WebhookClient(@Value("${ledgerpay.notification.webhook.connect-timeout-ms:2000}") int connectMs,
                         @Value("${ledgerpay.notification.webhook.read-timeout-ms:5000}") int readMs,
                         @Value("${ledgerpay.notification.webhook.max-attempts:4}") int maxAttempts) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readMs / 1000))
                        .addHandlerLast(new WriteTimeoutHandler(readMs / 1000)));
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .build();
        this.maxAttempts = maxAttempts;
    }

    public void send(String url, String secret, String payload) {
        String signature = hmacSha256(secret == null ? "" : secret, payload);
        Mono<Void> call = webClient.post()
                .uri(url)
                .header("X-LedgerPay-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.createException().flatMap(Mono::error))
                .bodyToMono(Void.class);

        call.retryWhen(Retry.backoff(maxAttempts - 1L, Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(5))
                        .jitter(0.2))
                .doOnError(err -> log.warn("Webhook POST to {} failed after retries: {}", url, err.toString()))
                .onErrorResume(err -> Mono.empty())
                .block();
    }

    private static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return "sha256=" + sb;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
