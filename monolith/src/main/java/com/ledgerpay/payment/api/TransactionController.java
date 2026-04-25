package com.ledgerpay.payment.api;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.common.security.AuthenticatedUser;
import com.ledgerpay.common.security.CurrentUser;
import com.ledgerpay.ledger.domain.PaymentTransaction;
import com.ledgerpay.ledger.domain.PaymentTransactionRepository;
import com.ledgerpay.wallet.service.WalletService;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final PaymentTransactionRepository repository;
    private final WalletService walletService;

    public TransactionController(PaymentTransactionRepository repository,
                                 WalletService walletService) {
        this.repository = repository;
        this.walletService = walletService;
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        AuthenticatedUser u = CurrentUser.require();
        PaymentTransaction t = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + id));
        if (!"ADMIN".equals(u.role())) {
            // Enforce ownership: caller must own either the source or destination wallet.
            boolean owned = ownsWallet(t.getSourceWalletId(), u) || ownsWallet(t.getDestWalletId(), u);
            if (!owned) {
                throw new NotFoundException("Transaction not found: " + id);
            }
        }
        return TransactionResponse.from(t);
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(
            @RequestParam UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthenticatedUser u = CurrentUser.require();
        walletService.requireOwnedBy(walletId, u.id(), u.role());
        Page<PaymentTransaction> p = repository.findByWallet(walletId,
                PageRequest.of(page, Math.min(size, 100)));
        List<TransactionResponse> items = p.map(TransactionResponse::from).getContent();
        return new PageResponse<>(items, p.getNumber(), p.getSize(), p.getTotalElements());
    }

    private boolean ownsWallet(UUID walletId, AuthenticatedUser u) {
        if (walletId == null) return false;
        try {
            walletService.requireOwnedBy(walletId, u.id(), u.role());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {}
}
