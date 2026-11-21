// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The demo log must be readable in a room, and must not buy that readability with honesty: no
 * invented verdicts, no hidden errors, and nothing suppressed that actually changed.
 */
class DemoLogTest {
    private ByteArrayOutputStream buffer;

    private DemoLog log(boolean enabled, boolean verbose) {
        buffer = new ByteArrayOutputStream();
        return new DemoLog("Reviewer", enabled, verbose, new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String output() { return buffer.toString(StandardCharsets.UTF_8); }

    private List<String> lines() {
        String text = output();
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }

    @Test void conciseLinesCarryNoIdentifiersHashesOrJson() {
        DemoLog demo = log(true, false);
        demo.banner();
        demo.evaluated("pr184", "proposal PR #184", "using rule v1", "s1", "REJECT",
                "missing \u201cindependent security audit\u201d");
        for (String line : lines()) {
            assertTrue(line.startsWith("[Reviewer] "), line);
            assertFalse(line.contains("{"), "JSON leaked into the demo log: " + line);
            assertFalse(line.contains("cid"), "a contract id leaked: " + line);
            assertFalse(line.matches(".*\\b[0-9a-f]{12,}\\b.*"), "a hash or id leaked: " + line);
            assertFalse(line.contains("errors="), "a counter leaked: " + line);
            assertFalse(line.contains("reviews="), "a counter leaked: " + line);
        }
        assertTrue(output().contains("Recommendations only; no votes or publication."));
        assertTrue(output().contains("[Reviewer] Evaluating proposal PR #184 using rule v1."));
        assertTrue(output().contains("[Reviewer] Result: REJECT \u2014 missing \u201cindependent security audit\u201d."));
    }

    @Test void unchangedResultIsNotReprintedButAChangedOneIs() {
        DemoLog demo = log(true, false);
        assertTrue(demo.evaluated("pr", "proposal PR #184", "using rule v1", "v1|REJECT", "REJECT", "missing audit"));
        // Same state three polls running: silence, not three identical blocks.
        assertFalse(demo.evaluated("pr", "proposal PR #184", "using rule v1", "v1|REJECT", "REJECT", "missing audit"));
        assertFalse(demo.evaluated("pr", "proposal PR #184", "using rule v1", "v1|REJECT", "REJECT", "missing audit"));
        assertEquals(2, lines().size(), output());
        // A real change always prints, and says it is a re-evaluation.
        assertTrue(demo.evaluated("pr", "proposal PR #184", "using rule v2", "v2|APPROVE", "APPROVE", "all requirements met"));
        assertTrue(output().contains("Re-evaluating proposal PR #184 using rule v2."));
        assertTrue(output().contains("Result: APPROVE \u2014 all requirements met."));
        assertEquals(4, lines().size(), output());
    }

    @Test void errorsArePrintedOnceAndRecoveryIsAnnouncedOnce() {
        DemoLog demo = log(true, false);
        demo.failed(DemoLog.LEDGER, "Waiting: ledger not readable yet");
        demo.failed(DemoLog.LEDGER, "Waiting: ledger not readable yet");
        demo.failed(DemoLog.LEDGER, "Waiting: ledger not readable yet");
        assertEquals(1, lines().size(), "a repeated identical failure must not repeat: " + output());
        // A different failure is a different fact and must be visible.
        demo.failed(DemoLog.LEDGER, "Waiting: no verified active rule");
        assertEquals(2, lines().size(), output());
        demo.recovered(DemoLog.LEDGER, "Ledger readable again; resuming evaluation.");
        demo.recovered(DemoLog.LEDGER, "Ledger readable again; resuming evaluation.");
        assertEquals(3, lines().size(), "recovery must be announced exactly once: " + output());
        assertTrue(output().contains("Ledger readable again"));
    }

    /**
     * Health categories are independent. One shared slot was the root cause of the reported
     * flapping: clearing a healthy aspect cleared an unhealthy one, so every poll announced a
     * recovery that had not happened.
     */
    @Test void recoveryOfOneCategoryDoesNotClearAnother() {
        DemoLog demo = log(true, false);
        demo.failed(DemoLog.AGENDA_DISCOVERY, "Agenda discovery unavailable: read failed");
        assertTrue(demo.failing(DemoLog.AGENDA_DISCOVERY));
        assertFalse(demo.failing(DemoLog.LEDGER));
        // The ledger was never failing, so there is nothing to recover and
        // nothing may be printed. Crucially, the agenda failure survives.
        assertFalse(demo.recovered(DemoLog.LEDGER, "Ledger readable again."),
                "recovering a category that never failed must print nothing");
        assertTrue(demo.failing(DemoLog.AGENDA_DISCOVERY),
                "an unrelated recovery must not clear a live failure");
        // Which means the still-present failure must not reprint either.
        assertFalse(demo.failed(DemoLog.AGENDA_DISCOVERY, "Agenda discovery unavailable: read failed"),
                "an unchanged failure must stay silent");
        assertEquals(1, lines().size(), "exactly one line for a persistent single failure: " + output());
        // And its own recovery is announced exactly once.
        assertTrue(demo.recovered(DemoLog.AGENDA_DISCOVERY, "Agenda discovery available again."));
        assertFalse(demo.recovered(DemoLog.AGENDA_DISCOVERY, "Agenda discovery available again."));
        assertEquals(2, lines().size(), output());
    }

    /** Two categories can fail and recover concurrently without interfering. */
    @Test void independentCategoriesTrackSeparately() {
        DemoLog demo = log(true, false);
        demo.failed(DemoLog.LEDGER, "Waiting: ledger down");
        demo.failed(DemoLog.EVIDENCE, "Note: milestone issue missing");
        assertEquals(2, lines().size(), output());
        assertTrue(demo.failing(DemoLog.LEDGER) && demo.failing(DemoLog.EVIDENCE));
        demo.recovered(DemoLog.LEDGER, "Ledger readable again.");
        assertFalse(demo.failing(DemoLog.LEDGER));
        assertTrue(demo.failing(DemoLog.EVIDENCE), "the other category must be unaffected");
        assertEquals(3, lines().size(), output());
    }

    @Test void failuresCarryNoStackTraceAndAlwaysCarryAMessage() {
        String summary = DemoLog.summarize(new RuntimeException(new java.io.IOException(
                "BLOCKED: local sandbox ACS read failed: Status{code=UNAVAILABLE}")));
        assertEquals("BLOCKED: local sandbox ACS read failed: Status{code=UNAVAILABLE}", summary);
        assertFalse(summary.contains("\n"));
        assertFalse(summary.contains("\tat "));
        // A message-less exception still yields something a human can read.
        assertEquals("IllegalStateException", DemoLog.summarize(new IllegalStateException()));
        assertEquals("unavailable", DemoLog.summarize(null));
        // Long messages are trimmed to one line, never dropped.
        String long_ = DemoLog.trim("a".repeat(400));
        assertTrue(long_.length() <= 160, long_);
    }

    @Test void disabledLogPrintsNothingAtAll() {
        DemoLog demo = log(false, false);
        demo.banner();
        demo.note("waiting");
        demo.evaluated("k", "proposal PR #184", "using rule v1", "s", "REJECT", "missing audit");
        demo.failed(DemoLog.LEDGER, "Waiting: unavailable");
        demo.recovered(DemoLog.LEDGER, "back");
        assertEquals("", output(), "demo mode is opt-in; nothing may print without it");
    }

    @Test void verboseIsReportedSoCallersCanRestoreDetail() {
        assertTrue(log(true, true).verbose());
        assertFalse(log(true, false).verbose());
        assertTrue(AgentFlags.verbose("1"));
        assertTrue(AgentFlags.verbose("yes"));
        assertFalse(AgentFlags.verbose("0"));
        assertFalse(AgentFlags.verbose(""));
        assertFalse(AgentFlags.verbose(null));
    }

    @Test void reasonsComeFromTheFindingsNotFromConstants() {
        ObjectNode fail = Advisory.JSON.createObjectNode().put("clause_id", "independent-security-audit")
                .put("status", "fail").put("detail", "required text absent: \"independent security audit\"");
        fail.putArray("missing_phrases").add("independent security audit");
        JsonNode failing = Advisory.JSON.createArrayNode().add(fail);
        assertEquals("missing \u201cindependent security audit\u201d",
                DemoLog.reason(Recommendation.Verdict.REJECT, failing));

        JsonNode passing = Advisory.JSON.createArrayNode().add(
                Advisory.JSON.createObjectNode().put("clause_id", "composition").put("status", "pass"));
        assertEquals("all requirements met", DemoLog.reason(Recommendation.Verdict.APPROVE, passing));

        JsonNode thin = Advisory.JSON.createArrayNode().add(Advisory.JSON.createObjectNode()
                .put("clause_id", "auditable-state-and-acceptance").put("status", "insufficient_evidence"));
        assertEquals("no evidence for \u201cauditable-state-and-acceptance\u201d",
                DemoLog.reason(Recommendation.Verdict.HOLD, thin));

        // No findings must never read like approval.
        assertEquals("no verified evidence to evaluate",
                DemoLog.reason(Recommendation.Verdict.BLOCKED, Advisory.JSON.createArrayNode()));
        assertEquals("no verified evidence to evaluate",
                DemoLog.reason(Recommendation.Verdict.BLOCKED, null));
    }

    @Test void labelsAreSpokenNamesDerivedFromTheSubject() {
        ObjectNode pr = Advisory.JSON.createObjectNode().put("kind", "proposal_pr").put("number", 184);
        assertEquals("proposal PR #184", DemoLog.subjectLabel(pr));
        ObjectNode issue = Advisory.JSON.createObjectNode().put("kind", "milestone_issue").put("number", 540);
        assertEquals("milestone #540", DemoLog.subjectLabel(issue));
        assertEquals("proposal PR #184",
                DemoLog.subjectLabelFromUrl("https://github.com/canton-foundation/canton-dev-fund/pull/184"));
        assertEquals("milestone #540",
                DemoLog.subjectLabelFromUrl("https://github.com/canton-foundation/canton-dev-fund/issues/540"));
        assertEquals("the rule-change vote", DemoLog.roundLabel("babycdf-agent:rule-v2"));
        assertEquals("the grant vote", DemoLog.roundLabel("babycdf-agent:grant"));
        assertEquals("the milestone vote", DemoLog.roundLabel("babycdf-agent:illustrative-milestone"));
        assertEquals("the quorum test round", DemoLog.roundLabel("babycdf-agent:blocked-quorum"));
        // An unknown suffix is passed through, not guessed at.
        assertEquals("the something new vote", DemoLog.roundLabel("run:something-new"));
    }
}
