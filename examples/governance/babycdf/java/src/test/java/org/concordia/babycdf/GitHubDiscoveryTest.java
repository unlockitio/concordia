package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.DiscoveryReport.StopReason.*;

class GitHubDiscoveryTest {
    private static final String ROOT = "https://api.github.com/repos/canton-foundation/canton-dev-fund/";
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static String row(int n, String title) { return "{\"number\":" + n + ",\"title\":\"" + title + "\"}"; }
    private static String page(String endpoint, int n) { return ROOT + endpoint + "?state=all&per_page=100&page=" + n; }
    private static GitHubDiscovery.Response response(int status, String body, String next) {
        return new GitHubDiscovery.Response(status, next == null ? List.of() : List.of("<" + next + ">; rel=\"next\""), body);
    }
    private static GitHubDiscovery discovery(GitHubDiscovery.Transport t, int pages, int requests, AtomicLong nanos) {
        return new GitHubDiscovery(t, new GitHubDiscovery.Limits(pages, requests,
                Duration.ofSeconds(5), Duration.ofSeconds(1)), Clock.fixed(NOW, ZoneOffset.UTC), nanos::get);
    }
    @Test void multiPageObservedInventorySeparatesPrsAndTitleDomainMilestones() {
        List<String> calls = new ArrayList<>();
        var d = discovery((uri, timeout) -> {
            calls.add(uri.toString());
            assertTrue(timeout.compareTo(Duration.ofSeconds(1)) <= 0);
            if (uri.getPath().endsWith("pulls")) {
                if (uri.getQuery().contains("page=2")) return response(200, "[" + row(184, "Proposal: Concordia") + "," + row(185, "Other") + "]", null);
                return response(200, "[" + row(184, "Proposal: Concordia") + "]", page("pulls", 2));
            }
            if (uri.getQuery().contains("page=2")) return response(200,
                    "[" + row(540, "Concordia #184 - Milestone 3: Governance Module Expansion") + "," + row(541, "Ordinary issue") + "]", null);
            return response(200, "[{\"number\":184,\"title\":\"Proposal: Concordia\",\"pull_request\":{}},"
                    + row(540, "Concordia #184 - Milestone 3: Governance Module Expansion") + "]", page("issues", 2));
        }, 10, 20, new AtomicLong());
        var report = d.discover();
        assertTrue(report.complete());
        assertEquals(NOW, report.observedAt());
        assertEquals(2, report.pulls().observedUniqueCount());
        assertEquals(2, report.issues().observedUniqueCount());
        assertEquals(Set.of(540L), report.issues().milestoneIssueNumbers());
        assertEquals(4, calls.size());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().items().clear());
    }
    @Test void rejectsUnsafeNextBeforeTransportCanLeakAnything() {
        for (String target : List.of("https://evil.example/issues", ROOT + "pulls?state=all&per_page=100",
                "http://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=100",
                ROOT + "issues?state=all&per_page=100&token=secret", ROOT + "issues?state=all&per_page=100#fragment")) {
            List<String> calls = new ArrayList<>();
            var report = discovery((uri, timeout) -> {
                calls.add(uri.toString());
                return uri.getPath().endsWith("pulls") ? response(200, "[]", null)
                        : response(200, "[" + row(540, "Concordia #184 - Milestone 3: Expansion") + "]", target);
            }, 10, 20, new AtomicLong()).discover();
            assertEquals(UNSAFE_LINK, report.issues().stopReason());
            assertEquals(1, report.issues().observedUniqueCount());
            assertEquals(2, calls.size());
        }
    }
    @Test void cyclesAreIncompleteAndBounded() {
        var report = discovery((uri, timeout) -> response(200, "[]", uri.toString()), 10, 20, new AtomicLong()).discover();
        assertEquals(CYCLE, report.pulls().stopReason());
        assertEquals(1, report.pulls().requests());
        assertFalse(report.complete());
    }
    @Test void forbiddenAndRateLimitsPreservePartialCounts() {
        for (int status : List.of(403, 429, 500)) {
            var report = discovery((uri, timeout) -> uri.getQuery().contains("page=2")
                    ? response(status, "error", null) : response(200, "[" + row(184, "Proposal") + "]", page(uri.getPath().endsWith("pulls") ? "pulls" : "issues", 2)),
                    10, 20, new AtomicLong()).discover();
            assertEquals(1, report.pulls().observedUniqueCount());
            assertEquals(status == 403 ? FORBIDDEN : status == 429 ? RATE_LIMITED : HTTP_ERROR, report.pulls().stopReason());
            assertFalse(report.complete());
        }
    }
    @Test void pageAndGlobalRequestLimitsAreExplicit() {
        GitHubDiscovery.Transport t = (uri, timeout) -> response(200, "[]", page(uri.getPath().endsWith("pulls") ? "pulls" : "issues", 2));
        assertEquals(PAGE_LIMIT, discovery(t, 1, 10, new AtomicLong()).discover().pulls().stopReason());
        var report = discovery(t, 10, 1, new AtomicLong()).discover();
        assertEquals(REQUEST_LIMIT, report.pulls().stopReason());
        assertEquals(REQUEST_LIMIT, report.issues().stopReason());
        assertEquals(0, report.issues().requests());
    }
    @Test void elapsedDeadlineStopsWithoutCountingLateResponse() {
        AtomicLong nanos = new AtomicLong();
        var report = discovery((uri, timeout) -> {
            nanos.set(Duration.ofSeconds(6).toNanos());
            return response(200, "[" + row(184, "Proposal") + "]", null);
        }, 10, 20, nanos).discover();
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertEquals(0, report.pulls().observedUniqueCount());
        assertEquals(0, report.issues().requests());
    }
    @Test void malformedPagesAndTransportFailuresAreNotComplete() {
        for (String body : List.of("{}", "not json", "[{\"number\":540}]", "[" + row(184, "ok") + ",{}]")) {
            var report = discovery((u, t) -> response(200, body, null), 10, 20, new AtomicLong()).discover();
            assertEquals(INVALID_RESPONSE, report.pulls().stopReason());
            assertEquals(0, report.pulls().observedUniqueCount());
        }
        assertEquals(TRANSPORT_ERROR, discovery((u, t) -> { throw new IOException("offline"); },
                10, 20, new AtomicLong()).discover().pulls().stopReason());
    }
    @Test void oversizedStreamConsumesOnlyLimitPlusOneAndClosesPreservingPriorPage() {
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return ' '; }
            @Override public void close() { closed.set(true); }
        };
        var report = discovery((u, t) -> {
            if (u.getPath().endsWith("issues")) return response(200, "[]", null);
            if (!u.getQuery().contains("page=2")) return response(200, "[" + row(184, "Proposal") + "]", page("pulls", 2));
            return new GitHubDiscovery.Response(200, List.of(), stream);
        }, 10, 20, new AtomicLong()).discover();
        assertEquals(GitHubDiscovery.MAX_RESPONSE_BYTES + 1, consumed.get());
        assertTrue(closed.get());
        assertEquals(RESPONSE_LIMIT, report.pulls().stopReason());
        assertEquals(1, report.pulls().observedUniqueCount());
        assertFalse(report.complete());
    }
    @Test void trailingJsonValuesAndGarbageRejectedButWhitespaceAccepted() {
        for (String suffix : List.of("{}", "[]", "42", "true", "null", "garbage")) {
            var report = discovery((u, t) -> response(200, "[" + row(184, "Proposal") + "] " + suffix, null),
                    10, 20, new AtomicLong()).discover();
            assertEquals(INVALID_RESPONSE, report.pulls().stopReason());
            assertEquals(0, report.pulls().observedUniqueCount());
        }
        assertTrue(discovery((u, t) -> response(200, "[] \r\n\t ", null),
                10, 20, new AtomicLong()).discover().complete());
    }
    @Test void deadlineCrossingDuringReadClosesAndDiscardsLatePage() {
        AtomicLong nanos = new AtomicLong();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var stream = new java.io.ByteArrayInputStream("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public byte[] readNBytes(int n) {
                nanos.set(Duration.ofSeconds(6).toNanos());
                return new byte[]{'[', ']'};
            }
            @Override public void close() { closed.set(true); }
        };
        var report = discovery((u, t) -> new GitHubDiscovery.Response(200, List.of(), stream), 10, 20, nanos).discover();
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertTrue(closed.get());
        assertEquals(0, report.pulls().observedUniqueCount());
    }
    @Test void deadlineCrossingAfterParseCannotClaimComplete() {
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        var report = new GitHubDiscovery((u, t) -> response(200, "[" + row(184, "Proposal") + "]", null),
                new GitHubDiscovery.Limits(10, 20, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ticks.incrementAndGet() >= 6 ? Duration.ofSeconds(6).toNanos() : 0).discover();
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertEquals(0, report.pulls().observedUniqueCount());
    }
    @Test void structuralAndItemBoundsRejectInvalidPagesAndCloseErrorResponses() {
        for (String body : List.of("[".repeat(40) + "]".repeat(40),
                "[" + String.join(",", Collections.nCopies(101, row(184, "Proposal"))) + "]")) {
            assertEquals(INVALID_RESPONSE, discovery((u, t) -> response(200, body, null),
                    10, 20, new AtomicLong()).discover().pulls().stopReason());
        }
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var report = discovery((u, t) -> new GitHubDiscovery.Response(403, List.of(), new java.io.ByteArrayInputStream(new byte[0]) {
            @Override public void close() { closes.incrementAndGet(); }
        }), 10, 20, new AtomicLong()).discover();
        assertEquals(FORBIDDEN, report.pulls().stopReason());
        assertEquals(2, closes.get());
    }
    @Test @org.junit.jupiter.api.Timeout(5)
    void scheduledExpiryClosesActuallyBlockedReadAndTerminatesTimer() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var timerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var stream = new java.io.InputStream() {
            @Override public int read() throws IOException {
                entered.countDown();
                try { closed.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                throw new IOException("closed by expiry");
            }
            @Override public void close() {
                if (Thread.currentThread().getName().startsWith("pool-")) timerThread.set(Thread.currentThread());
                closed.countDown();
            }
        };
        var report = new GitHubDiscovery((u, t) -> {
            if (u.getPath().endsWith("issues")) return response(200, "[]", null);
            if (!u.getQuery().contains("page=2")) return response(200, "[" + row(184, "Proposal") + "]", page("pulls", 2));
            return new GitHubDiscovery.Response(200, List.of(), stream);
        }, new GitHubDiscovery.Limits(10, 20, Duration.ofSeconds(4), Duration.ofMillis(100)),
                Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime).discover();
        assertEquals(0, entered.getCount(), "read really blocked before close");
        assertEquals(0, closed.getCount());
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertEquals(1, report.pulls().observedUniqueCount());
        assertNotNull(timerThread.get(), "expiry callback performed close");
        timerThread.get().join(1000);
        assertFalse(timerThread.get().isAlive(), "timer terminated before discovery returned");
    }
    @Test void headersCannotResetRequestDeadline() {
        AtomicLong nanos = new AtomicLong();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var report = discovery((u, t) -> {
            nanos.addAndGet(Duration.ofMillis(1100).toNanos());
            return new GitHubDiscovery.Response(200, List.of(), new java.io.InputStream() {
                @Override public int read() { reads.incrementAndGet(); return -1; }
            });
        }, 10, 20, nanos).discover();
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertEquals(0, reads.get());
    }
    @Test void requestDeadlineAppliesAfterParsingEvenWithAggregateTimeRemaining() {
        var ticks = new java.util.concurrent.atomic.AtomicInteger();
        var report = new GitHubDiscovery((u, t) -> response(200, "[" + row(184, "Proposal") + "]", null),
                new GitHubDiscovery.Limits(10, 20, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ticks.incrementAndGet() >= 7 ? Duration.ofMillis(1100).toNanos() : 0).discover();
        assertEquals(TIME_LIMIT, report.pulls().stopReason());
        assertEquals(0, report.pulls().observedUniqueCount());
    }
    @Test void limitsRejectUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new GitHubDiscovery.Limits(0, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GitHubDiscovery.Limits(1, 1, Duration.ofDays(1), Duration.ofSeconds(1)));
    }
}
