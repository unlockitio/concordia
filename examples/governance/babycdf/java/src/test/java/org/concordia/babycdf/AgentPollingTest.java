// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentPollingTest {
    @Test void intervalBoundsRejectNonfiniteAndZero() {
        for (String value : new String[]{"NaN", "Infinity", "0", "-1", "86401"})
            assertThrows(IllegalArgumentException.class, () -> AgentPolling.intervalMillis(value));
        assertEquals(1000, AgentPolling.intervalMillis("1"));
    }
    @Test void oneShotDoesNotSleep() throws Exception {
        int[] cycles = {0};
        AgentPolling.run(0, () -> cycles[0]++, ms -> fail("unexpected sleep"));
        assertEquals(1, cycles[0]);
    }
    @Test void loopStopsOnInterruptAndPreservesFlag() throws Exception {
        int[] cycles = {0};
        try {
            AgentPolling.run(1, () -> cycles[0]++, ms -> { throw new InterruptedException(); });
            assertEquals(1, cycles[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void repeatsThreeCyclesAndPassesInterval() throws Exception {
        int[] cycles = {0}, sleeps = {0};
        try {
            AgentPolling.run(37, () -> cycles[0]++, ms -> {
                assertEquals(37, ms);
                if (++sleeps[0] == 3) throw new InterruptedException();
            });
            assertEquals(3, cycles[0]);
            assertEquals(3, sleeps[0]);
        } finally { Thread.interrupted(); }
    }
    @Test void cycleExceptionPropagatesWithoutSleeping() {
        assertThrows(java.io.IOException.class, () -> AgentPolling.run(1,
                () -> { throw new java.io.IOException("cycle failed"); },
                ms -> fail("must not sleep after cycle failure")));
    }
    @Test void reviewerCycleInterruptionStopsWithoutSleeping() throws Exception {
        Reviewer.LedgerRuleGateway ledger = () -> java.util.List.of(
                new Reviewer.EffectiveRule("cid", "architecture-review", 2, java.util.List.of(
                        new Advisory.Clause("C1", "anchor", "expected", "conflict", "wrong"))));
        int[] calls = {0}, cycles = {0};
        Advisory.Api interrupted = path -> {
            calls[0]++;
            throw new InterruptedException("evidence interrupted");
        };
        try {
            AgentPolling.run(10, () -> {
                cycles[0]++;
                Reviewer.runCycle(ledger, interrupted);
            }, ms -> fail("must not sleep after cycle interruption"));
            assertEquals(1, cycles[0]);
            assertEquals(1, calls[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void linesNeverClaimUnverifiedRuleAuthority() {
        var blocked = Advisory.JSON.createObjectNode().put("reason", "no rule");
        assertTrue(AgentPolling.reviewerLine(blocked).contains("BLOCKED"));
        var live = Advisory.JSON.createObjectNode().put("rule_version", 2).put("rule_contract_id", "cid");
        assertTrue(AgentPolling.reviewerLine(live).contains("EffectiveRule v2"));
        assertTrue(AgentPolling.advisoryLine(blocked).contains("submission=blocked"));
        blocked.putArray("errors").add("one").add("two");
        assertTrue(AgentPolling.reviewerLine(blocked).contains("errors=2"));
        live.putArray("errors").add("one");
        assertTrue(AgentPolling.reviewerLine(live).contains("errors=1"));
        var unknown = Advisory.JSON.createObjectNode();
        unknown.putArray("reviews").addObject().put("revision", "unverified");
        assertTrue(AgentPolling.advisoryLine(unknown).contains("PR#?"));
    }
}
