package com.ledgerpay.wallet.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.common.security.AuthenticatedUser;
import com.ledgerpay.common.security.CurrentUser;
import com.ledgerpay.wallet.domain.WalletStatus;
import com.ledgerpay.wallet.service.WalletService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@Valid @RequestBody(required = false) CreateWalletRequest req) {
        AuthenticatedUser u = CurrentUser.require();
        String currency = req == null ? "INR" : req.currencyOrDefault();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                WalletResponse.from(walletService.createWalletForUser(u.id(), currency)));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id) {
        AuthenticatedUser u = CurrentUser.require();
        return WalletResponse.from(walletService.requireOwnedBy(id, u.id(), u.role()));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        AuthenticatedUser u = CurrentUser.require();
        var w = walletService.requireOwnedBy(id, u.id(), u.role());
        return new BalanceResponse(w.getId(), w.getCurrency(), w.getBalanceMinor());
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse freeze(@PathVariable UUID id) {
        return WalletResponse.from(walletService.setStatus(id, WalletStatus.FROZEN));
    }

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public WalletResponse unfreeze(@PathVariable UUID id) {
        return WalletResponse.from(walletService.setStatus(id, WalletStatus.ACTIVE));
    }

    public record BalanceResponse(UUID walletId, String currency, long balanceMinor) {}
}
