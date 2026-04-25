package com.ledgerpay.reconciliation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the reconciliation engine on a daily schedule. The cron is configurable so CI and dev
 * environments can run it more often.
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService service;

    public ReconciliationScheduler(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${ledgerpay.reconciliation.cron:0 0 2 * * *}")
    public void runScheduled() {
        log.info("Scheduled reconciliation kicking off");
        try {
            service.runOnce();
        } catch (Exception ex) {
            log.error("Scheduled reconciliation failed", ex);
        }
    }
}
