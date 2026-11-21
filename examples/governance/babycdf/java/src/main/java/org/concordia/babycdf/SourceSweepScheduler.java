// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Interleave a single durable source batch with agenda work; never use a high-water shortcut.
 * The ledger checkpoint is the only cursor. Empty terminal tokens restart a full sweep.
 * Errors propagate to the caller; there is no synthetic fallback or blind retry.
 */
public final class SourceSweepScheduler {
    private final JavaSourceIngestion engine;
    private final LongSupplier nanoTime;
    private final long intervalNanos;
    private long next;
    private boolean started;
    private boolean suspended;
    public SourceSweepScheduler(JavaSourceIngestion engine,LongSupplier nanoTime,long intervalMillis) {
        if(intervalMillis<1000 || intervalMillis>3_600_000)throw new IllegalArgumentException("bounded source interval required");
        this.engine=Objects.requireNonNull(engine);this.nanoTime=Objects.requireNonNull(nanoTime);
        intervalNanos=intervalMillis*1_000_000;
    }
    public enum TickOutcome { SKIPPED, SUCCEEDED }
    public synchronized TickOutcome tick() throws IOException {
        long now=nanoTime.getAsLong();
        if(suspended || (started && now-next<0))return TickOutcome.SKIPPED;
        // Back off even on failed requests; repeated agenda polls cannot hammer the provider.
        started=true;next=now+intervalNanos;
        try {engine.cycle();}
        catch(SourceHttpFailure failure) {
            suspended=failure.suspended;
            // Delay from receipt/failure, not request start; no sleeping worker or tight polls.
            next=nanoTime.getAsLong()+Math.max(intervalNanos,failure.delayNanos);
            throw failure;
        }
        return TickOutcome.SUCCEEDED;
    }
}
