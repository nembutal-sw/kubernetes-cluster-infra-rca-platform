package io.clusterinfra.rca.webconsole.service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

final class LeaseRenewalGuard implements AutoCloseable {
    private final String resourceType;
    private final String resourceId;
    private final Logger logger;
    private final AtomicBoolean leaseLost = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> renewal;

    private LeaseRenewalGuard(
        ScheduledExecutorService scheduler,
        String resourceType,
        String resourceId,
        int leaseSeconds,
        BooleanSupplier renewLease,
        Logger logger
    ) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.logger = logger;
        long renewalIntervalSeconds = Math.max(1, leaseSeconds / 3L);
        this.renewal = scheduler.scheduleAtFixedRate(
            () -> renew(renewLease),
            renewalIntervalSeconds,
            renewalIntervalSeconds,
            TimeUnit.SECONDS
        );
    }

    static LeaseRenewalGuard start(
        ScheduledExecutorService scheduler,
        String resourceType,
        String resourceId,
        int leaseSeconds,
        BooleanSupplier renewLease,
        Logger logger
    ) {
        return new LeaseRenewalGuard(
            scheduler,
            resourceType,
            resourceId,
            leaseSeconds,
            renewLease,
            logger
        );
    }

    boolean leaseLost() {
        return leaseLost.get();
    }

    private void renew(BooleanSupplier renewLease) {
        if (closed.get() || leaseLost.get()) {
            return;
        }
        try {
            if (!renewLease.getAsBoolean() && !closed.get()) {
                leaseLost.set(true);
                logger.warn("{} lease renewal lost ownership: {}", resourceType, resourceId);
            }
        } catch (RuntimeException exception) {
            logger.warn("{} lease renewal failed and will be retried: {}", resourceType, resourceId, exception);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        renewal.cancel(false);
    }
}
