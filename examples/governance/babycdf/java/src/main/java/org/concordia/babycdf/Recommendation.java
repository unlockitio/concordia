// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Advisory, text-only recommendation derived from clause findings.
 *
 * <p>This type has no ledger, HTTP or submission seam of any kind. It maps findings to a verdict
 * and formats one line; emitting that line is the caller's job and the only effect is a log write.
 * The agent cannot vote: its ledger user holds read rights only, with no {@code CanActAs}, so it
 * is incapable of casting, commenting or submitting anything.
 */
public final class Recommendation {
    private Recommendation() { }

    /** Conservative mapping. Order matters: the worst applicable signal wins. */
    public enum Verdict {
        /** No verified rule or no evidence at all: nothing can be recommended. */
        BLOCKED,
        /** At least one clause contradicted by pinned evidence. */
        REJECT,
        /** No contradiction, but at least one clause lacks matching pinned evidence. */
        HOLD,
        /** Every clause passed against pinned evidence. */
        APPROVE
    }

    static final String ADVISORY_LABEL = "advisory only, members decide";

    /**
     * Maps clause findings to a verdict.
     *
     * <p>Precedence is deliberate and conservative: an empty or absent finding set is BLOCKED
     * rather than APPROVE, any {@code fail} is REJECT, and any {@code insufficient_evidence} is
     * HOLD. Only an all-{@code pass} set yields APPROVE, so an unknown status can never be
     * silently treated as approval.
     */
    public static Verdict verdict(JsonNode findings) {
        if (findings == null || !findings.isArray() || findings.isEmpty()) return Verdict.BLOCKED;
        boolean failed = false, insufficient = false, allPass = true;
        for (JsonNode finding : findings) {
            String status = Advisory.value(finding, "status");
            switch (status) {
                case "fail" -> { failed = true; allPass = false; }
                case "insufficient_evidence" -> { insufficient = true; allPass = false; }
                case "pass" -> { }
                // An unrecognized status is never evidence of approval.
                default -> { insufficient = true; allPass = false; }
            }
        }
        if (failed) return Verdict.REJECT;
        if (insufficient) return Verdict.HOLD;
        return allPass ? Verdict.APPROVE : Verdict.HOLD;
    }

    /** Short, stable identity of one recommendation: one line per agenda per rule version. */
    public static String key(String roundId, String ruleContractId, int ruleVersion) {
        return roundId + "|" + ruleContractId + "|" + ruleVersion;
    }

    static String shorten(String contractId) {
        if (contractId == null || contractId.isBlank()) return "unknown";
        return contractId.length() <= 12 ? contractId : contractId.substring(0, 12) + "\u2026";
    }

    static String evidencePin(JsonNode review, boolean offlineSeed) {
        String revision = Advisory.value(review, "revision");
        boolean pinned = review.path("pinned").asBoolean();
        if (revision.isBlank() || !pinned) return "unpinned issue body (no immutable revision)";
        String shortSha = revision.length() <= 10 ? revision : revision.substring(0, 10) + "\u2026";
        int number = Advisory.number(review);
        String source = offlineSeed ? "offline pinned" : "pinned";
        return source + " PR#" + number + " blob " + shortSha;
    }

    /**
     * Formats the advisory line. Always cites the rule id, version and contract, the evidence pin
     * and the advisory label, so a pasted line cannot be mistaken for a decision.
     */
    public static String line(JsonNode review, int ruleVersion, String ruleId, String ruleContractId,
                              String roundId, boolean offlineSeed) {
        Verdict verdict = verdict(review.path("findings"));
        StringBuilder detail = new StringBuilder();
        for (JsonNode finding : review.path("findings")) {
            if (detail.length() > 0) detail.append("; ");
            detail.append(Advisory.value(finding, "clause_id")).append(": ")
                    .append(Advisory.value(finding, "status").replace('_', ' '));
        }
        if (detail.length() == 0) detail.append("no clause findings");
        return "[reviewer] RECOMMENDATION " + Advisory.value(review, "kind")
                + " " + (roundId == null || roundId.isBlank() ? "unknown-round" : roundId)
                + " under " + (ruleId.isBlank() ? "unknown-rule" : ruleId) + " v" + ruleVersion
                + " (cid " + shorten(ruleContractId) + "): " + verdict + " \u2014 " + detail
                + "; evidence: " + evidencePin(review, offlineSeed)
                + " (" + ADVISORY_LABEL + ")";
    }

    /**
     * One line per open agenda, skipping any already emitted.
     *
     * <p>A recommendation is only meaningful while an agenda is open, so nothing is printed when
     * the agent sees none. Each agenda is matched to the review of <em>its own subject</em> by
     * URL: pairing a milestone agenda with the proposal's findings would recommend APPROVE on
     * evidence that was never about that milestone. An agenda with no matching evidence is
     * reported BLOCKED rather than assumed good.
     *
     * <p>{@code emitted} is mutated with the keys that were returned, so a polling agent prints
     * each agenda once per rule version even though it re-reads the ledger every interval.
     */
    public static List<String> lines(JsonNode result, Set<String> emitted) {
        List<String> out = new ArrayList<>();
        if (result == null || !result.has("rule_version")) return out;
        int ruleVersion = result.path("rule_version").asInt();
        String ruleId = Advisory.value(result, "rule_id");
        String ruleContractId = Advisory.value(result, "rule_contract_id");
        boolean offlineSeed = Advisory.value(result, "discovery").startsWith("offline_pinned");
        for (JsonNode agenda : result.path("open_agendas")) {
            String roundId = Advisory.value(agenda, "round_id");
            if (roundId.isBlank()) continue;
            String key = key(roundId, ruleContractId, ruleVersion);
            if (emitted != null && !emitted.add(key)) continue;
            JsonNode review = match(result.path("reviews"),
                    Advisory.value(agenda, "subject_url"), Advisory.value(agenda, "subject_body"));
            if (review == null) {
                out.add("[reviewer] RECOMMENDATION agenda " + roundId
                        + " under " + (ruleId.isBlank() ? "unknown-rule" : ruleId) + " v" + ruleVersion
                        + " (cid " + shorten(ruleContractId) + "): " + Verdict.BLOCKED
                        + " \u2014 not reviewable against proposal evidence (subject "
                        + subject(Advisory.value(agenda, "subject_url")) + ")"
                        + " (" + ADVISORY_LABEL + ")");
            } else {
                out.add(line(review, ruleVersion, ruleId, ruleContractId, roundId, offlineSeed));
            }
        }
        return out;
    }

    /**
     * The review whose evidence is about this agenda's subject, or null when none is.
     *
     * <p>The URL alone is not sufficient. An Architecture (rule-change) agenda deliberately
     * reuses the pinned proposal's URL while carrying its own design text as the body, so a
     * URL-only match would hand a rule change the proposal's passing findings and recommend
     * APPROVE on evidence that was never about it.
     *
     * <p>So both must agree: the agenda's URL must be the reviewed artifact, and its body must
     * equal exactly the revision the review pinned. A Grant agenda's body is that blob SHA and
     * matches; an Architecture agenda's body is design text plus a {@code proposal-blob:} suffix
     * and does not; a Milestone agenda's URL and content version do not either. Everything that
     * does not match is reported as not reviewable rather than assumed good.
     */
    static JsonNode match(JsonNode reviews, String subjectUrl, String subjectBody) {
        if (subjectUrl == null || subjectUrl.isBlank()) return null;
        if (subjectBody == null || subjectBody.isBlank()) return null;
        for (JsonNode review : reviews) {
            boolean sameArtifact = subjectUrl.equals(Advisory.value(review, "source_url"))
                    || subjectUrl.equals(Advisory.value(review, "url"));
            String revision = Advisory.value(review, "revision");
            boolean samePinnedRevision = review.path("pinned").asBoolean()
                    && !revision.isBlank() && subjectBody.equals(revision);
            if (sameArtifact && samePinnedRevision) return review;
        }
        return null;
    }

    /** Compact subject label, so a BLOCKED line still says what it was about. */
    static String subject(String url) {
        if (url == null || url.isBlank()) return "unknown";
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) return url;
        String tail = url.substring(slash + 1);
        return url.contains("/pull/") ? "PR#" + tail
                : url.contains("/issues/") ? "issue#" + tail : tail;
    }

    /** Round identifiers of agendas the agent can see as still open, if any were reported. */
    static Set<String> openRounds(JsonNode result) {
        Set<String> rounds = new LinkedHashSet<>();
        for (JsonNode agenda : result.path("open_agendas")) {
            String roundId = Advisory.value(agenda, "round_id");
            if (!roundId.isBlank()) rounds.add(roundId);
        }
        return rounds;
    }
}
