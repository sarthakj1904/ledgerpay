package com.ledgerpay.reconciliation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerpay.common.exception.NotFoundException;
import com.ledgerpay.reconciliation.domain.ReconciliationReport;
import com.ledgerpay.reconciliation.domain.ReconciliationReportRepository;
import com.ledgerpay.reconciliation.domain.ReconciliationStatus;
import com.ledgerpay.reconciliation.service.ReconciliationService;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationReportRepository repository;

    public ReconciliationController(ReconciliationService service,
                                    ReconciliationReportRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping("/run")
    public ResponseEntity<ReportSummary> runNow() {
        ReconciliationReport r = service.runOnce();
        return ResponseEntity.accepted().body(ReportSummary.from(r));
    }

    @GetMapping
    public PageResponse<ReportSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReconciliationReport> p = repository.findAllOrdered(
                PageRequest.of(page, Math.min(size, 100)));
        return new PageResponse<>(p.map(ReportSummary::from).getContent(),
                p.getNumber(), p.getSize(), p.getTotalElements());
    }

    @GetMapping("/{id}")
    public ReportDetail get(@PathVariable UUID id) {
        return ReportDetail.from(repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Reconciliation report not found: " + id)));
    }

    public record ReportSummary(
            UUID id,
            Instant startedAt,
            Instant finishedAt,
            ReconciliationStatus status,
            int walletsChecked,
            int transactionsChecked,
            int ledgerEntriesChecked,
            long totalDebitsMinor,
            long totalCreditsMinor
    ) {
        static ReportSummary from(ReconciliationReport r) {
            return new ReportSummary(r.getId(), r.getStartedAt(), r.getFinishedAt(), r.getStatus(),
                    r.getWalletsChecked(), r.getTransactionsChecked(), r.getLedgerEntriesChecked(),
                    r.getTotalDebitsMinor(), r.getTotalCreditsMinor());
        }
    }

    public record ReportDetail(
            UUID id,
            Instant startedAt,
            Instant finishedAt,
            ReconciliationStatus status,
            int walletsChecked,
            int transactionsChecked,
            int ledgerEntriesChecked,
            long totalDebitsMinor,
            long totalCreditsMinor,
            String mismatchesJson
    ) {
        static ReportDetail from(ReconciliationReport r) {
            return new ReportDetail(r.getId(), r.getStartedAt(), r.getFinishedAt(), r.getStatus(),
                    r.getWalletsChecked(), r.getTransactionsChecked(), r.getLedgerEntriesChecked(),
                    r.getTotalDebitsMinor(), r.getTotalCreditsMinor(), r.getMismatchesJson());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {}
}
