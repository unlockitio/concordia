package org.concordia.babycdf;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

final class ReviewDeadline implements AutoCloseable {
    private static final ThreadLocal<ReviewDeadline> CURRENT = new ThreadLocal<>();
    private final LongSupplier clock;
    private final long start;
    private final ReviewDeadline previous;

    ReviewDeadline(LongSupplier clock) {
        this.clock = clock;
        start = clock.getAsLong();
        previous = CURRENT.get();
        CURRENT.set(previous == null ? this : previous);
    }

    static long remaining(long operationBudget) {
        var deadline = CURRENT.get();
        return deadline == null ? operationBudget : Math.min(operationBudget,
                Duration.ofSeconds(60).toNanos() - (deadline.clock.getAsLong() - deadline.start));
    }

    static void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("review canceled");
        if (remaining(Long.MAX_VALUE) <= 0) throw new IOException("cycle deadline exceeded");
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
