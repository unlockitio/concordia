// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end presentation behaviour of the Reviewer, driven through real cycles.
 *
 * <p>These exercise the same {@code runCycle} the container runs, with fake gateways standing in
 * for the ledger, so what is asserted is what a demo audience would actually read.
 */
class ReviewerNarrationTest {
    private ByteArrayOutputStream err;
    private PrintStream stream;

    private DemoLog demo(boolean verbose) {
        err = new ByteArrayOutputStream();
        stream = new PrintStream(err, true, StandardCharsets.UTF_8);
        return new DemoLog("Reviewer", true, verbose, stream);
    }

    private String output() { return err.toString(StandardCharsets.UTF_8); }

    // The pinned proposal text: contains the composition and auditable-state
    // evidence, and deliberately not an independent security audit commitment.
    private final String proposal =
            "CAP is designed to sit above existing Canton asset and settlement infrastructure\n"
            + "downstream execution hooks for approved proposals\n"
            + "auditable contract state\nAcceptance Criteria";

    /** Set by the re-pin test to simulate the document bytes changing. */
    private String proposalOverride;

    private String text() { return proposalOverride == null ? proposal : proposalOverride; }

    private String blobSha() {
        String body = text();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return Advisory.digest("SHA-1", ("blob " + bytes.length + "\0" + body).getBytes(StandardCharsets.UTF_8));
    }

    private Advisory.Api evidence() {
        return path -> {
            if(path.endsWith("/issues/540"))return Advisory.JSON.readTree("{\"number\":540,\"pull_request\":{}}");
            if (path.startsWith("search/issues?"))
                return Advisory.JSON.readTree(path.contains("is%3Apr")
                        ? "{\"items\":[{\"number\":184}]}" : "{\"items\":[]}");
            if (path.endsWith("/pulls/184"))
                return Advisory.JSON.readTree("{\"number\":184,\"head\":{\"sha\":\"" + "c".repeat(40)
                        + "\"},\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
            if (path.contains("/pulls/184/files?"))
                return Advisory.JSON.readTree("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + blobSha() + "\"}]");
            if (path.contains("/git/blobs/"))
                return Advisory.JSON.readTree("{\"sha\":\"" + blobSha() + "\",\"encoding\":\"base64\",\"content\":\""
                        + Base64.getEncoder().encodeToString(text().getBytes(StandardCharsets.UTF_8)) + "\"}");
            throw new IOException("unexpected endpoint " + path);
        };
    }

    private Advisory.Clause clause(String id, String criterion, List<String> required) {
        return new Advisory.Clause(id, "anchor", "expected", "conflict-anchor", "a contradiction",
                criterion, required, List.of());
    }

    /** Rule v1: demands an independent security audit the pinned text never promises. */
    private Reviewer.EffectiveRule ruleV1() {
        return new Reviewer.EffectiveRule("00c0f381a4e0deadbeef", "architecture-review", 1, List.of(
                clause("compose-with-asset-layer", "must compose with the asset layer",
                        List.of("CAP is designed to sit above existing Canton asset and settlement infrastructure")),
                clause("independent-security-audit", "must commit to an independent security audit",
                        List.of("independent security audit"))));
    }

    /** Rule v2: replaces that clause with one the same text can evidence. */
    private Reviewer.EffectiveRule ruleV2() {
        return new Reviewer.EffectiveRule("00a66dabf855deadbeef", "architecture-review", 2, List.of(
                clause("compose-with-asset-layer", "must compose with the asset layer",
                        List.of("CAP is designed to sit above existing Canton asset and settlement infrastructure")),
                clause("governance-execution-hooks", "must describe downstream execution hooks",
                        List.of("downstream execution hooks for approved proposals")),
                clause("auditable-state-and-acceptance", "must evidence auditable state and an acceptance bar",
                        List.of("auditable contract state", "Acceptance Criteria"))));
    }

    private Reviewer.LedgerRuleGateway gateway(Reviewer.EffectiveRule rule, List<Reviewer.OpenAgenda> agendas) {
        return new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(rule); }
            public List<Reviewer.OpenAgenda> openAgendas() { return agendas; }
        };
    }

    private String future() { return String.valueOf((System.currentTimeMillis() + 600_000L) * 1000L); }

    /**
     * The demo's central contrast, as it is actually printed: the same pinned document is rejected
     * under rule v1 and approved under rule v2, and the second evaluation says it is a second one.
     */
    @Test void sameDocumentIsRejectedUnderV1AndApprovedUnderV2() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.runCycle(gateway(ruleV1(), List.of()), evidence(), emitted, demo);
        String afterV1 = output();
        assertTrue(afterV1.contains("[Reviewer] Evaluating proposal PR #184 using rule v1."), afterV1);
        assertTrue(afterV1.contains("[Reviewer] Result: REJECT \u2014 missing \u201cindependent security audit\u201d."), afterV1);
        assertFalse(afterV1.contains("APPROVE"), afterV1);

        // A second identical poll must add nothing: the answer has not moved.
        Reviewer.runCycle(gateway(ruleV1(), List.of()), evidence(), emitted, demo);
        assertEquals(afterV1, output(), "an unchanged cycle must not reprint");

        // The voted rule changes; the same evidence now passes.
        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        String afterV2 = output().substring(afterV1.length());
        assertTrue(afterV2.contains("Re-evaluating proposal PR #184 using rule v2."), afterV2);
        assertTrue(afterV2.contains("Result: APPROVE \u2014 all requirements met."), afterV2);
    }

    /**
     * A rule-change agenda reuses the proposal's URL while carrying its own design text. It must
     * be reported BLOCKED on its own merits, never handed the proposal's passing findings.
     */
    @Test void ruleChangeAgendaWithoutItsOwnEvidenceIsBlockedNotApproved() throws Exception {
        DemoLog demo = demo(false);
        Reviewer.OpenAgenda ruleChange = new Reviewer.OpenAgenda("00ab", "babycdf-agent:rule-v2",
                "https://github.com/canton-foundation/canton-dev-fund/blob/" + "c".repeat(40) + "/proposals/concordia.md",
                "live v2 proposal-blob:f591787a772848e4537aba5d8ba02be1764647da", future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(ruleChange)), evidence(), new HashSet<>(), demo);
        String text = output();
        assertTrue(text.contains("Evaluating the rule-change vote"), text);
        assertTrue(text.contains("Result: BLOCKED \u2014 no verified evidence about this subject to review."), text);
        // The proposal's own standing evaluation is still APPROVE; the agenda is not.
        assertTrue(text.contains("Result: APPROVE \u2014 all requirements met."), text);
    }

    /** A milestone agenda has no pinned evidence in this seed, so it is BLOCKED, not approved. */
    @Test void milestoneAgendaIsBlockedForWantOfEvidence() throws Exception {
        DemoLog demo = demo(false);
        Reviewer.OpenAgenda milestone = new Reviewer.OpenAgenda("00cd", "babycdf-agent:illustrative-milestone",
                "https://github.com/canton-foundation/canton-dev-fund/issues/540",
                "illustrative-immutable-milestone-revision-v1", future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(milestone)), evidence(), new HashSet<>(), demo);
        String text = output();
        assertTrue(text.contains("Evaluating the milestone vote (milestone #540)."), text);
        assertTrue(text.contains("Result: BLOCKED \u2014 no verified evidence about this subject to review."), text);
        // "Illustrative" must never be narrated as verified.
        assertFalse(text.toLowerCase().contains("verified milestone"), text);
    }

    /** A grant agenda is matched to its own subject and carries the advisory label. */
    @Test void grantAgendaRecommendationIsLabelledAdvisory() throws Exception {
        DemoLog demo = demo(false);
        Reviewer.OpenAgenda grant = new Reviewer.OpenAgenda("00ef", "babycdf-agent:grant",
                "https://github.com/canton-foundation/canton-dev-fund/blob/" + "c".repeat(40) + "/proposals/concordia.md",
                blobSha(), future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(grant)), evidence(), new HashSet<>(), demo);
        String text = output();
        assertTrue(text.contains("Evaluating the grant vote on proposal PR #184 using rule v2."), text);
        assertTrue(text.contains("APPROVE"), text);
        assertTrue(text.contains("advisory only, members decide"), text);
    }

    /** The default demo output carries no JSON, no contract ids and no per-cycle counters. */
    @Test void demoOutputIsConciseAndCarriesNoMachineDetail() throws Exception {
        DemoLog demo = demo(false);
        var out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), new HashSet<>(), demo);
        } finally { System.setOut(originalOut); }
        assertEquals("", out.toString(StandardCharsets.UTF_8), "no JSON may be dumped by default in demo mode");
        String text = output();
        assertFalse(text.contains("{"), text);
        assertFalse(text.contains("errors="), text);
        assertFalse(text.contains("inputs="), text);
        assertFalse(text.contains("open agendas="), text);
        assertFalse(text.contains("00a66dabf855"), "a contract id leaked into the demo log: " + text);
        assertFalse(text.contains("RECOMMENDATION"), text);
        for (String line : text.split("\n")) assertTrue(line.startsWith("[Reviewer] "), line);
    }

    /** --verbose keeps every machine-readable line the original CLI produced. */
    @Test void verboseRestoresTheFullMachineReadableOutput() throws Exception {
        DemoLog demo = demo(true);
        var out = new ByteArrayOutputStream();
        var errStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out, originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        // The activity and recommendation lines go to the real System.err, so
        // capture that too rather than only the demo log's own stream.
        System.setErr(new PrintStream(errStream, true, StandardCharsets.UTF_8));
        try {
            Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), new HashSet<>(), demo);
        } finally { System.setOut(originalOut); System.setErr(originalErr); }
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"rule_version\":2"), json);
        assertTrue(json.contains("\"findings\""), json);
        JsonNode parsed = Advisory.JSON.readTree(json);
        assertEquals("architecture-review", parsed.path("rule_id").asText());
        // The original activity line returns, alongside the concise narration.
        String machine = errStream.toString(StandardCharsets.UTF_8);
        assertTrue(machine.contains("[reviewer] active EffectiveRule v2"), machine);
        assertTrue(output().contains("[Reviewer] Result: APPROVE"), output());
    }

    /**
     * A failing ledger must be stated, once, in one short sentence, and recovery must be visible.
     * The underlying BLOCKED result is still produced; only its volume changes.
     */
    @Test void ledgerFailureIsReportedOnceAndRecoveryIsVisible() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway broken = () -> {
            throw new IOException("BLOCKED: local sandbox ACS read failed: Status{code=UNAVAILABLE}");
        };
        Reviewer.runCycle(broken, evidence(), emitted, demo);
        Reviewer.runCycle(broken, evidence(), emitted, demo);
        Reviewer.runCycle(broken, evidence(), emitted, demo);
        String failing = output();
        assertTrue(failing.contains("Waiting: BLOCKED: local sandbox ACS read failed"), failing);
        assertEquals(1, failing.split("\n").length, "a persistent failure must not repeat: " + failing);
        assertFalse(failing.contains("\tat "), "no stack trace may reach the demo log");

        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        String recovered = output().substring(failing.length());
        assertTrue(recovered.contains("Cycle completed again; resuming evaluation."), recovered);
        assertTrue(recovered.contains("Result: APPROVE"), recovered);
    }

    /**
     * Two distinct agenda contracts that happen to share a round ID are two agendas, and both
     * must be reported. Round IDs are caller-chosen labels, so keying on them alone would hide
     * the second one.
     */
    @Test void sameRoundIdOnDifferentAgendaContractsIsNotDeduplicated() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        String url = "https://github.com/canton-foundation/canton-dev-fund/blob/" + "c".repeat(40)
                + "/proposals/concordia.md";
        Reviewer.OpenAgenda first = new Reviewer.OpenAgenda("00aaaa", "babycdf-agent:grant", url, blobSha(), future());
        Reviewer.OpenAgenda second = new Reviewer.OpenAgenda("00bbbb", "babycdf-agent:grant", url, blobSha(), future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(first)), evidence(), emitted, demo);
        int afterFirst = output().split("\n").length;
        Reviewer.runCycle(gateway(ruleV2(), List.of(second)), evidence(), emitted, demo);
        assertTrue(output().split("\n").length > afterFirst,
                "a different agenda contract with the same round ID must still be reported: " + output());
        // Re-seeing the very same contract adds nothing.
        int afterSecond = output().split("\n").length;
        Reviewer.runCycle(gateway(ruleV2(), List.of(second)), evidence(), emitted, demo);
        assertEquals(afterSecond, output().split("\n").length,
                "the same agenda contract must not be reported twice: " + output());
    }

    /** One agenda contract put to a different subject is a different decision, and reprints. */
    @Test void sameContractWithADifferentSubjectIsReportedAgain() {
        var a = Advisory.JSON.createObjectNode().put("contract_id", "00cafe");
        String k1 = ReviewerNarration.agendaKey(a, "run:grant",
                "https://github.com/canton-foundation/canton-dev-fund/pull/184", "sha-one");
        String k2 = ReviewerNarration.agendaKey(a, "run:grant",
                "https://github.com/canton-foundation/canton-dev-fund/issues/540", "sha-one");
        String k3 = ReviewerNarration.agendaKey(a, "run:grant",
                "https://github.com/canton-foundation/canton-dev-fund/pull/184", "sha-two");
        assertNotEquals(k1, k2, "a different subject URL must be a different identity");
        assertNotEquals(k1, k3, "a different subject revision must be a different identity");
        // The immutable contract ID leads the key.
        assertTrue(k1.startsWith("agenda:00cafe|"), k1);
        // A payload without a contract ID still yields a usable identity.
        String noCid = ReviewerNarration.agendaKey(Advisory.JSON.createObjectNode(),
                "run:grant", "url", "body");
        assertTrue(noCid.startsWith("agenda:no-cid|"), noCid);
        assertNotEquals(k1, noCid);
    }

    /** Re-pinned evidence for the same agenda is a new answer and must reprint. */
    @Test void changedEvidencePinReprintsTheAgendaRecommendation() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        String url = "https://github.com/canton-foundation/canton-dev-fund/blob/" + "c".repeat(40)
                + "/proposals/concordia.md";
        Reviewer.OpenAgenda grant = new Reviewer.OpenAgenda("00ef11", "babycdf-agent:grant", url, blobSha(), future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(grant)), evidence(), emitted, demo);
        int first = output().split("\n").length;
        // Same cycle again: nothing new.
        Reviewer.runCycle(gateway(ruleV2(), List.of(grant)), evidence(), emitted, demo);
        assertEquals(first, output().split("\n").length, output());
        // The same agenda, same rule, but the document bytes changed, so the
        // pin changed: the recommendation is recomputed and must be reprinted.
        proposalOverride = proposal + "\nan added sentence";
        Reviewer.OpenAgenda repinned = new Reviewer.OpenAgenda("00ef11", "babycdf-agent:grant",
                url, blobSha(), future());
        Reviewer.runCycle(gateway(ruleV2(), List.of(repinned)), evidence(), emitted, demo);
        assertTrue(output().split("\n").length > first,
                "a changed evidence pin must reprint the recommendation: " + output());
    }

    /** Agenda discovery that degrades is surfaced, not silently dropped. */
    @Test void degradedAgendaDiscoveryIsSurfaced() throws Exception {
        DemoLog demo = demo(false);
        Reviewer.LedgerRuleGateway degraded = new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(ruleV2()); }
            public List<Reviewer.OpenAgenda> openAgendas() throws IOException {
                throw new IOException("BLOCKED: local sandbox agenda read failed");
            }
        };
        Reviewer.runCycle(degraded, evidence(), new HashSet<>(), demo);
        assertTrue(output().contains("Agenda discovery unavailable"), output());
        // The JSON field already says "unavailable: "; the spoken line must not
        // stutter it. The machine-readable field itself is unchanged.
        assertFalse(output().contains("unavailable: unavailable"), output());
        assertTrue(output().contains("Agenda discovery unavailable: BLOCKED: local sandbox agenda read failed"),
                output());
    }

    private long count(String needle) {
        return output().lines().filter(l -> l.contains(needle)).count();
    }

    /**
     * Regression for the reported logging defect, driven through production {@code runCycle}.
     *
     * <p>The rule read succeeds while agenda discovery fails persistently. Previously the
     * successful read cleared the shared problem slot, so every poll printed a recovery that had
     * not happened and then reported the identical failure again. The contract asserted here is:
     * one initial failure, no intervening recovery, no repeated failure, and exactly one genuine
     * recovery when discovery actually returns.
     */
    @Test void persistentAgendaDiscoveryFailureDoesNotFlapAcrossPolls() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway degraded = new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(ruleV2()); }
            public List<Reviewer.OpenAgenda> openAgendas() throws IOException {
                throw new IOException("BLOCKED: local sandbox agenda read failed");
            }
        };
        for (int poll = 0; poll < 4; poll++)
            Reviewer.runCycle(degraded, evidence(), emitted, demo);

        assertEquals(1, count("Agenda discovery unavailable"),
                "the identical failure must be reported exactly once: " + output());
        assertEquals(0, count("Agenda discovery available again"),
                "no recovery may be announced while discovery is still failing: " + output());
        assertEquals(0, count("Cycle completed again"),
                "nothing threw, so no cycle recovery may be announced: " + output());

        // Discovery comes back: exactly one genuine recovery, and no repeats.
        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        assertEquals(1, count("Agenda discovery available again"),
                "recovery must be announced exactly once: " + output());
        assertEquals(1, count("Agenda discovery unavailable"), output());
    }

    /**
     * The same contract for a transport failure of the rule read itself: one failure line across
     * repeated identical polls, then exactly one recovery.
     */
    @Test void persistentLedgerFailureReportsOnceThenRecoversOnce() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway broken = () -> {
            throw new IOException("BLOCKED: local sandbox ACS read failed: Status{code=UNAVAILABLE}");
        };
        for (int poll = 0; poll < 4; poll++) Reviewer.runCycle(broken, evidence(), emitted, demo);
        assertEquals(1, count("Waiting: BLOCKED: local sandbox ACS read failed"),
                "a persistent transport failure must be reported once: " + output());
        assertEquals(0, count("Cycle completed again"), output());

        for (int poll = 0; poll < 3; poll++)
            Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), emitted, demo);
        assertEquals(1, count("Cycle completed again"),
                "recovery must be announced exactly once: " + output());
        // And the proposal evaluation still happens on recovery.
        assertTrue(output().contains("Result: APPROVE"), output());
    }

    /**
     * A ledger failure and an agenda-discovery failure are tracked apart: recovering the ledger
     * must not silence a discovery failure that is still live.
     */
    @Test void ledgerRecoveryDoesNotMaskALiveDiscoveryFailure() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway broken = () -> {
            throw new IOException("BLOCKED: local sandbox ACS read failed: Status{code=UNAVAILABLE}");
        };
        Reviewer.LedgerRuleGateway ruleOkDiscoveryBroken = new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(ruleV2()); }
            public List<Reviewer.OpenAgenda> openAgendas() throws IOException {
                throw new IOException("BLOCKED: local sandbox agenda read failed");
            }
        };
        Reviewer.runCycle(broken, evidence(), emitted, demo);
        // The ledger recovers, but discovery is now failing: one recovery for
        // the ledger, one new failure for discovery, and no confusion of the two.
        Reviewer.runCycle(ruleOkDiscoveryBroken, evidence(), emitted, demo);
        Reviewer.runCycle(ruleOkDiscoveryBroken, evidence(), emitted, demo);
        assertEquals(1, count("Cycle completed again"), output());
        assertEquals(1, count("Agenda discovery unavailable"), output());
        assertEquals(0, count("Agenda discovery available again"), output());
    }

    /** Meaningful result changes still print while a failure is outstanding. */
    @Test void evaluationChangesStillPrintWhileDiscoveryIsDegraded() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway v1Degraded = new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(ruleV1()); }
            public List<Reviewer.OpenAgenda> openAgendas() throws IOException {
                throw new IOException("BLOCKED: local sandbox agenda read failed");
            }
        };
        Reviewer.LedgerRuleGateway v2Degraded = new Reviewer.LedgerRuleGateway() {
            public List<Reviewer.EffectiveRule> activeRules() { return List.of(ruleV2()); }
            public List<Reviewer.OpenAgenda> openAgendas() throws IOException {
                throw new IOException("BLOCKED: local sandbox agenda read failed");
            }
        };
        Reviewer.runCycle(v1Degraded, evidence(), emitted, demo);
        Reviewer.runCycle(v1Degraded, evidence(), emitted, demo);
        Reviewer.runCycle(v2Degraded, evidence(), emitted, demo);
        // The verdict flip is still reported, and the failure still only once.
        assertEquals(1, count("Result: REJECT"), output());
        assertEquals(1, count("Result: APPROVE"), output());
        assertEquals(1, count("Agenda discovery unavailable"), output());
    }

    /** The Advisory agent confirms what it verified, and reports a shortfall rather than hiding it. */
    @Test void advisoryConfirmsVerifiedEvidenceAndReportsGaps() {
        err = new ByteArrayOutputStream();
        DemoLog demo = new DemoLog("Advisory", true, false,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        ObjectNode result = Advisory.JSON.createObjectNode();
        ObjectNode review = result.putArray("reviews").addObject();
        review.put("kind", "proposal_pr").put("number", 184).put("pinned", true);
        result.putArray("errors").add("no Concordia milestone issues discovered");
        Advisory.narrate(demo, result);
        String text = output();
        assertTrue(text.contains("[Advisory] proposal PR #184 loaded; pinned evidence verified."), text);
        assertTrue(text.contains("[Advisory] Note: no Concordia milestone issues discovered"), text);
        // The same cycle again adds nothing.
        Advisory.narrate(demo, result);
        assertEquals(text, output(), "unchanged advisory state must not repeat");
        assertFalse(text.contains("{"), text);
    }

    /**
     * Regression for the same defect on the Advisory side, driven through the production cycle.
     *
     * <p>The sequence exercised here is exactly the one {@code Advisory.main} runs per poll:
     * {@link Advisory#pollOnce} to collect and review, then {@link Advisory#narrate} to report.
     * The offline PR-only seed legitimately reports no milestone issue, so the shortfall is
     * present on every poll while {@code pollOnce} suppresses the already-seen input. Previously
     * the caller announced recovery before those errors were examined, so each poll printed a
     * false recovery followed by the same note. The contract is: the shortfall is stated once, no
     * recovery is announced while it persists, and exactly one recovery when it clears.
     */
    @Test void persistentAdvisoryShortfallDoesNotFlapAcrossPolls() throws Exception {
        err = new ByteArrayOutputStream();
        DemoLog demo = new DemoLog("Advisory", true, false,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        Advisory.Fixture fixture = new Advisory.Fixture(2, List.of(
                clause("compose-with-asset-layer", "must compose",
                        List.of("CAP is designed to sit above existing Canton asset and settlement infrastructure"))));
        var state = new HashSet<String>();

        // Four identical polls against the PR-only seed.
        for (int poll = 0; poll < 4; poll++)
            Advisory.narrate(demo, Advisory.pollOnce(evidence(), fixture, state));

        assertEquals(1, count("Note: no Concordia milestone issues discovered"),
                "the expected shortfall must be stated exactly once: " + output());
        assertEquals(0, count("All expected evidence is present again"),
                "no recovery may be announced while the shortfall persists: " + output());
        assertEquals(1, count("proposal PR #184 loaded; pinned evidence verified"),
                "the verified-evidence confirmation must not repeat: " + output());

        // The milestone issue appears: exactly one genuine recovery, no repeats.
        Advisory.Api complete = withMilestone();
        for (int poll = 0; poll < 3; poll++)
            Advisory.narrate(demo, Advisory.pollOnce(complete, fixture, new HashSet<>()));
        assertEquals(1, count("All expected evidence is present again"),
                "recovery must be announced exactly once: " + output());
        assertEquals(1, count("Note: no Concordia milestone issues discovered"), output());
    }

    /** An Api whose milestone issue is discoverable, so the collector reports no errors. */
    private Advisory.Api withMilestone() {
        return path -> {
            if (path.startsWith("search/issues?"))
                return Advisory.JSON.readTree(path.contains("is%3Apr")
                        ? "{\"items\":[{\"number\":184}]}" : "{\"items\":[{\"number\":540}]}");
            if (path.endsWith("/issues/540"))
                return Advisory.JSON.readTree("{\"number\":540,\"title\":\"Concordia #184 - Milestone 3\","
                        + "\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/issues/540\","
                        + "\"updated_at\":\"2026-10-14T12:34:17Z\",\"body\":\"milestone body\"}");
            return evidence().get(path);
        };
    }

    // ---- Collector errors that do not throw: digest mismatch, bad identity ----
    //
    // Advisory.collect records these in `errors` and omits the input rather than
    // throwing, so the ledger read succeeds and the reviews array is empty. In
    // default demo mode the JSON and activity lines that would carry `errors`
    // are suppressed, so this path was entirely silent and left a previous
    // APPROVE standing as the audience's impression.

    /** Evidence whose blob bytes do not match the advertised digest. */
    private Advisory.Api tamperedBlob() {
        return path -> {
            if (path.contains("/git/blobs/"))
                return Advisory.JSON.readTree("{\"sha\":\"" + blobSha() + "\",\"encoding\":\"base64\",\"content\":\""
                        + Base64.getEncoder().encodeToString("TAMPERED".getBytes(StandardCharsets.UTF_8)) + "\"}");
            return evidence().get(path);
        };
    }

    /** Evidence whose PR head SHA is not a valid 40-hex identity. */
    private Advisory.Api unverifiedIdentity() {
        return path -> {
            if (path.endsWith("/pulls/184"))
                return Advisory.JSON.readTree("{\"number\":184,\"head\":{\"sha\":\"not-a-sha\"},"
                        + "\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
            return evidence().get(path);
        };
    }

    @Test void digestMismatchIsReportedOnceAcrossRepeatedPollsAndClearsOnce() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway healthy = gateway(ruleV2(), List.of());
        for (int poll = 0; poll < 4; poll++)
            Reviewer.runCycle(healthy, tamperedBlob(), emitted, demo);

        assertEquals(1, count("evidence verification failed"),
                "a digest mismatch must be reported exactly once: " + output());
        assertTrue(output().contains("Result: BLOCKED \u2014 evidence verification failed"), output());
        assertEquals(0, count("Evidence verified again"), output());
        // It must NOT be downgraded to the benign expected-shortfall wording.
        assertEquals(0, count("Note:"), "a verification failure must not be reported as a Note: " + output());
        // No approval may be present at all in this run.
        assertEquals(0, count("APPROVE"), output());

        // Evidence becomes valid: exactly one clearance, and the recomputed
        // result is printed because the withdrawn verdict reset the dedup.
        for (int poll = 0; poll < 3; poll++)
            Reviewer.runCycle(healthy, evidence(), emitted, demo);
        assertEquals(1, count("Evidence verified again"),
                "clearance must be announced exactly once: " + output());
        assertEquals(1, count("Result: APPROVE"), output());
        assertEquals(1, count("evidence verification failed"), output());
    }

    @Test void unverifiedProposalIdentityIsReportedAndNotTreatedAsExpected() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway healthy = gateway(ruleV2(), List.of());
        for (int poll = 0; poll < 3; poll++)
            Reviewer.runCycle(healthy, unverifiedIdentity(), emitted, demo);
        assertEquals(1, count("evidence verification failed"), output());
        assertTrue(output().contains("not verified") || output().contains("not discovered"), output());
        assertEquals(0, count("Note:"), output());
        assertEquals(0, count("APPROVE"), output());
    }

    /**
     * The defect exactly as reported: a valid APPROVE, then the same ledger with unverifiable
     * evidence and no open agenda. The stale approval must be explicitly withdrawn, never left
     * standing, and the failure must be stated.
     */
    @Test void validApproveFollowedByBadEvidenceWithdrawsTheStaleVerdict() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway healthy = gateway(ruleV2(), List.of());

        Reviewer.runCycle(healthy, evidence(), emitted, demo);
        assertTrue(output().contains("Result: APPROVE \u2014 all requirements met."), output());
        int afterApprove = output().split("\n").length;

        // Same valid ledger, no agenda, evidence now unverifiable.
        for (int poll = 0; poll < 3; poll++)
            Reviewer.runCycle(healthy, tamperedBlob(), emitted, demo);
        String after = output();
        assertTrue(after.split("\n").length > afterApprove,
                "the silent path is the defect: something must be said: " + after);
        assertTrue(after.contains("Previous finding for proposal PR #184 is no longer current"),
                "the stale verdict must be explicitly withdrawn and named: " + after);
        assertTrue(after.contains("Result: BLOCKED \u2014 evidence verification failed"), after);
        // Exactly one withdrawal and one failure across the three bad polls.
        assertEquals(1, count("is no longer current"), after);
        assertEquals(1, count("evidence verification failed"), after);
        assertEquals(1, count("Result: APPROVE"), after);
    }

    /** A changed verification error is a changed fact and must print again. */
    @Test void changedEvidenceErrorIsReportedAgain() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.LedgerRuleGateway healthy = gateway(ruleV2(), List.of());
        Reviewer.runCycle(healthy, tamperedBlob(), emitted, demo);
        Reviewer.runCycle(healthy, tamperedBlob(), emitted, demo);
        assertEquals(1, count("evidence verification failed"), output());
        // A different verification failure must not be suppressed as a repeat.
        Reviewer.runCycle(healthy, unverifiedIdentity(), emitted, demo);
        assertEquals(2, count("evidence verification failed"),
                "a changed verification error must be reported: " + output());
        assertEquals(0, count("Evidence verified again"),
                "no clearance may be announced between two failures: " + output());
    }

    /**
     * The benign expected shortfall and a real verification failure must not be conflated, in
     * either direction, and the benign one must never mask the serious one.
     */
    @Test void expectedMilestoneShortfallIsClassifiedApartFromVerificationFailures() throws Exception {
        assertTrue(ReviewerNarration.isExpectedShortfall("no Concordia milestone issues discovered"));
        assertFalse(ReviewerNarration.isExpectedShortfall("proposal blob digest mismatch"));
        assertFalse(ReviewerNarration.isExpectedShortfall("proposal PR identity not verified"));
        assertFalse(ReviewerNarration.isExpectedShortfall("proposal blob response unverified"));
        assertFalse(ReviewerNarration.isExpectedShortfall("proposal PR not discovered"));
        // Fail closed: an unrecognized error is a verification failure.
        assertFalse(ReviewerNarration.isExpectedShortfall("something nobody anticipated"));

        // End to end: the PR-only seed reports the benign note, not a failure.
        DemoLog demo = demo(false);
        Reviewer.runCycle(gateway(ruleV2(), List.of()), evidence(), new HashSet<>(), demo);
        assertEquals(0, count("evidence verification failed"), output());
        assertTrue(output().contains("Result: APPROVE"), output());
        // The seed has no milestone issue, which is expected and noted once.
        assertEquals(1, count("Note: no Concordia milestone issues discovered"), output());
    }

    /** Evaluation changes still print while an evidence failure is outstanding. */
    @Test void ruleChangesStillPrintWhileEvidenceIsUnverifiable() throws Exception {
        DemoLog demo = demo(false);
        var emitted = new HashSet<String>();
        Reviewer.runCycle(gateway(ruleV1(), List.of()), tamperedBlob(), emitted, demo);
        Reviewer.runCycle(gateway(ruleV2(), List.of()), tamperedBlob(), emitted, demo);
        // No reviews survive, so no verdict may be claimed for either rule, and
        // the failure is stated once rather than per rule version.
        assertEquals(0, count("Result: APPROVE"), output());
        assertEquals(0, count("Result: REJECT"), output());
        assertEquals(1, count("evidence verification failed"), output());
    }

    /** Unpinned evidence must never be described as verified. */
    @Test void unpinnedAdvisoryEvidenceIsNotCalledVerified() {
        err = new ByteArrayOutputStream();
        DemoLog demo = new DemoLog("Advisory", true, false,
                new PrintStream(err, true, StandardCharsets.UTF_8));
        ObjectNode result = Advisory.JSON.createObjectNode();
        result.putArray("reviews").addObject()
                .put("kind", "milestone_issue").put("number", 540).put("pinned", false);
        result.putArray("errors");
        Advisory.narrate(demo, result);
        String text = output();
        assertTrue(text.contains("milestone #540 loaded; no immutable revision"), text);
        assertFalse(text.contains("verified"), text);
    }
}
