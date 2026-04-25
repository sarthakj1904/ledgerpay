package com.ledgerpay.payment.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.common.security.AuthenticatedUser;
import com.ledgerpay.common.security.CurrentUser;
import com.ledgerpay.payment.service.PaymentService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/add-money")
    public ResponseEntity<TransactionResponse> addMoney(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody AddMoneyRequest req,
            HttpServletRequest http) {
        AuthenticatedUser u = CurrentUser.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(
                paymentService.addMoney(idempotencyKey, u.id(), clientIp(http), req)));
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody TransferRequest req,
            HttpServletRequest http) {
        AuthenticatedUser u = CurrentUser.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(
                paymentService.transfer(idempotencyKey, u.id(), clientIp(http), req)));
    }

    @PostMapping("/merchant-pay")
    public ResponseEntity<TransactionResponse> merchantPay(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody MerchantPayRequest req,
            HttpServletRequest http) {
        AuthenticatedUser u = CurrentUser.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(
                paymentService.merchantPay(idempotencyKey, u.id(), clientIp(http), req)));
    }

    @PostMapping("/refund")
    public ResponseEntity<TransactionResponse> refund(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody RefundRequest req,
            HttpServletRequest http) {
        AuthenticatedUser u = CurrentUser.require();
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(
                paymentService.refund(idempotencyKey, u.id(), clientIp(http), req)));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return req.getRemoteAddr();
    }
}
