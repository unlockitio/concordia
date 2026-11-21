// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Presentation-first log for the demo agents.
 *
 * <p>This type formats and deduplicates lines. It holds no ledger, HTTP or submission seam, reads
 * nothing and decides nothing: every verdict it prints is computed elsewhere and handed to it, so
 * quietening the log cannot quieten a check. Removing every call would leave the agents' behaviour
 * identical.
 *
 * <p><strong>What it suppresses and what it must not.</strong> A polling agent re-reads the same
 * ledger every few seconds and recomputes the same answer, which is why the default log was
 * unreadable in a room. Repetition of an <em>unchanged</em> answer is therefore suppressed, keyed
 * on everything that could make the answer mean something different: the subject, the rule
 * identity and version, the evidence pin, the verdict and the reason. A change in any of those is
 * a new line. Errors follow the same rule rather than being hidden: the first occurrence prints,
 * identical repeats are silent, and recovery prints once, so a persistent failure is visible
 * without scrolling the real content away.
 *
 * <p>Nothing here invents confidence. A result line is only ever printed by a caller that has
 * already evaluated the subject, and the vocabulary distinguishes a verdict reached on evidence
 * from the absence of reviewable evidence.
 */
final class DemoLog {
    /** Identifiers, hashes, clause dumps and raw JSON appear only when this is on. */
    private final boolean verbose;
    private final boolean enabled;
    private final String actor;
    private final PrintStream out;
    /** Last printed state per subject, so an unchanged answer is not reprinted. */
    private final Map<String, String> lastState = new HashMap<>();
    /** Spoken label per subject, so a withdrawal can name what it retracts. */
    private final Map<String, String> labels = new HashMap<>();
    /** Subjects already evaluated at least once, for "Evaluating" versus "Re-evaluating". */
    private final Set<String> seen = new HashSet<>();
    /** One-shot notices: startup, waiting, and each distinct error message. */
    private final Set<String> once = new HashSet<>();
    /**
     * The current unresolved problem per health category.
     *
     * <p>Keyed, not a single slot. A cycle can be healthy in one respect and degraded in another:
     * the Reviewer can read the rule while agenda discovery fails, and the Advisory agent can
     * verify its pinned evidence while its offline seed legitimately lacks a milestone issue.
     * With one shared slot, clearing the healthy aspect cleared the unhealthy one too, so every
     * poll announced a recovery that had not happened and then reported the same failure again.
     * Each category therefore recovers only when that category is actually clear.
     */
    private final Map<String, String> problems = new HashMap<>();

    DemoLog(String actor, boolean enabled, boolean verbose, PrintStream out) {
        this.actor = actor;
        this.enabled = enabled;
        this.verbose = verbose;
        this.out = out;
    }

    boolean enabled() { return enabled; }
    boolean verbose() { return verbose; }

    private void say(String line) { out.println("[" + actor + "] " + line); }

    /**
     * The advisory-only banner, printed once at startup.
     *
     * <p>It states the limit in plain words. The limit itself is a capability one: the agents' ledger
     * user holds read rights only, so this line describes what the agent cannot do rather than
     * promising what it will not do.
     */
    void banner() {
        if (!enabled || !once.add("banner")) return;
        say("Starting. Recommendations only; no votes or publication.");
    }

    /** A state notice printed at most once per distinct text, such as "waiting for the rule". */
    void note(String line) {
        if (!enabled || !once.add("note:" + line)) return;
        say(line);
    }

    /**
     * Reports a subject's evaluation, unless the identical answer was already reported.
     *
     * @param subjectKey stable identity of the subject, never printed
     * @param subjectLabel what a listener hears, such as "proposal PR #184"
     * @param context     an optional clause such as "using rule v2", or "" for none
     * @param stateKey    everything that makes this answer distinct from a previous one
     * @param verdict     the already-computed verdict
     * @param reason      a short, concrete reason; must not be invented by the caller
     * @return true when a line was printed
     */
    boolean evaluated(String subjectKey, String subjectLabel, String context, String stateKey,
                      String verdict, String reason) {
        if (!enabled) return false;
        // Recorded even when the line is suppressed, so a later withdrawal can
        // still name the subject whose verdict it retracts.
        labels.put(subjectKey, subjectLabel);
        if (stateKey.equals(lastState.get(subjectKey))) return false;
        lastState.put(subjectKey, stateKey);
        boolean again = !seen.add(subjectKey);
        say((again ? "Re-evaluating " : "Evaluating ") + subjectLabel
                + (context == null || context.isBlank() ? "" : " " + context) + ".");
        say("Result: " + verdict + (reason == null || reason.isBlank() ? "" : " \u2014 " + reason) + ".");
        return true;
    }

    /** A one-off observation about a subject, such as the Advisory agent's load confirmation. */
    boolean observed(String subjectKey, String line) {
        if (!enabled) return false;
        if (line.equals(lastState.get(subjectKey))) return false;
        lastState.put(subjectKey, line);
        say(line);
        return true;
    }

    /**
     * Health categories. Each tracks and recovers independently.
     *
     * <p>{@code CYCLE} is deliberately provenance-neutral: a thrown failure from the review step
     * may have come from the ledger read or from evidence acquisition, and the exception does not
     * reliably say which. Claiming "the ledger recovered" when the ledger may never have failed
     * would be a false statement, so that category is named and worded for what is actually known.
     */
    static final String CYCLE = "cycle";
    static final String LEDGER = "ledger";
    static final String AGENDA_DISCOVERY = "agenda-discovery";
    /** Evidence verification: a digest mismatch or an unverified identity. */
    static final String EVIDENCE = "evidence";
    /**
     * The demo's routine, expected evidence shortfall, tracked apart from verification.
     *
     * <p>Separate because they are different facts with different severities. Sharing one
     * category meant a cycle that recovered verification into a routine note could not announce
     * the recovery, and a note could sit where a failure's clearance belonged.
     */
    static final String EVIDENCE_NOTE = "evidence-note";

    /**
     * A failure in one health category, as one short sentence with no stack trace.
     *
     * <p>Identical repeats within a category are silent, so a ledger that is not up yet does not
     * fill the screen, but the first occurrence always prints and a changed failure always prints.
     *
     * @return true when a line was printed
     */
    boolean failed(String category, String summary) {
        String text = summary == null || summary.isBlank() ? "unavailable" : summary;
        boolean changed = !text.equals(problems.get(category));
        problems.put(category, text);
        if (!enabled || !changed) return false;
        say(text);
        return true;
    }

    /**
     * Announces recovery of one category, once, and only if that category had actually failed.
     *
     * <p>Callers must report the outcome of every category they are responsible for on every
     * cycle, and must do so only after the whole cycle's health is known. Calling this before the
     * rest of the cycle has been assessed is what produced the false-recovery flapping: the
     * category must be genuinely clear at the moment it is cleared.
     *
     * @return true when a line was printed
     */
    boolean recovered(String category, String line) {
        if (problems.remove(category) == null) return false;
        if (!enabled) return false;
        say(line);
        return true;
    }

    /** Whether a category is currently in a failed state. Used by callers and tests. */
    boolean failing(String category) { return problems.containsKey(category); }

    /**
     * Withdraws the standing verdict of every evaluated subject that is not in {@code keep}.
     *
     * <p>This exists because silence is not neutral. A verdict already on screen keeps being the
     * audience's impression until something replaces it, so when a subject's evidence stops being
     * verifiable its earlier result must be explicitly retracted rather than simply not repeated.
     *
     * <p>Two effects, both deliberate. One line per withdrawn subject is printed, naming it, so a
     * listener knows exactly which finding no longer applies. And the subject's dedup state is
     * cleared, so when valid evidence returns the recomputed result is treated as new and printed
     * again instead of being suppressed as "unchanged" against the verdict just withdrawn.
     *
     * @param keep        dedup keys whose verdicts are still current this cycle
     * @param explanation builds the retraction sentence from a subject's spoken label
     * @return the number of subjects withdrawn
     */
    int withdraw(Set<String> keep, java.util.function.Function<String, String> explanation) {
        int withdrawn = 0;
        for (String key : new ArrayList<>(lastState.keySet())) {
            // Only evaluated subjects carry a verdict; agenda advice is
            // transient and disappears with the round, so it is left alone.
            if (!key.startsWith("review:") || keep.contains(key)) continue;
            String label = labels.getOrDefault(key, "an earlier subject");
            lastState.remove(key);
            seen.remove(key);
            withdrawn++;
            if (enabled) say(explanation.apply(label));
        }
        return withdrawn;
    }

    /**
     * A one-sentence summary of a failure: the message only, never a class name or a stack trace.
     * A cause chain is walked for the first message, so "IOException: null" never reaches the room.
     */
    static String summarize(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && !message.isBlank()) return trim(message);
        }
        return error == null ? "unavailable" : error.getClass().getSimpleName();
    }

    /**
     * Keeps a message to one readable line, without an exception class name in front of it.
     *
     * <p>A wrapped throwable's message is its cause's {@code toString()}, which starts with the
     * class name, and the agents' own BLOCKED results are formatted the same way. Stripping that
     * prefix matters for more than tidiness: the same underlying failure must produce the same
     * sentence whichever path reports it, or deduplication would alternate between two spellings
     * of one problem and defeat itself.
     */
    static String trim(String message) {
        String flat = message.replaceAll("\\s+", " ").trim();
        // "java.io.IOException: text" or "IOException: text" -> "text".
        flat = flat.replaceFirst("^(?:[a-zA-Z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable): ", "");
        int stop = flat.indexOf(". ");
        if (stop > 20) flat = flat.substring(0, stop);
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "\u2026";
    }

    /** "proposal PR #184" or "milestone #540", from the review's own kind and number. */
    static String subjectLabel(JsonNode review) {
        String kind = Advisory.value(review, "kind");
        int number = Advisory.number(review);
        String id = number > 0 ? " #" + number : "";
        return switch (kind) {
            case "proposal_pr" -> "proposal PR" + id;
            case "milestone_issue" -> "milestone" + id;
            default -> (kind.isBlank() ? "subject" : kind.replace('_', ' ')) + id;
        };
    }

    /** "proposal PR #184" or "milestone #540", from an agenda's subject URL. */
    static String subjectLabelFromUrl(String url) {
        if (url == null || url.isBlank()) return "an unidentified subject";
        int slash = url.lastIndexOf('/');
        String tail = slash < 0 || slash == url.length() - 1 ? url : url.substring(slash + 1);
        if (url.contains("/pull/")) return "proposal PR #" + tail;
        if (url.contains("/issues/")) return "milestone #" + tail;
        if (url.contains("/blob/")) return "proposal document " + tail;
        return tail;
    }

    /**
     * A short, spoken name for a voting round, derived from the round identifier's own suffix.
     *
     * <p>Round identifiers are "&lt;runId&gt;:&lt;suffix&gt;"; the run ID is noise to a listener.
     * An unrecognized suffix is passed through rather than guessed at.
     */
    static String roundLabel(String roundId) {
        if (roundId == null || roundId.isBlank()) return "";
        int colon = roundId.lastIndexOf(':');
        String suffix = colon < 0 ? roundId : roundId.substring(colon + 1);
        return switch (suffix) {
            case "rule-v2", "rule-v1" -> "the rule-change vote";
            case "stale" -> "the duplicate rule-change vote";
            case "grant" -> "the grant vote";
            case "illustrative-milestone", "unpinned-milestone" -> "the milestone vote";
            case "blocked-foreign" -> "the bad-credential test round";
            case "blocked-quorum" -> "the quorum test round";
            case "blocked-double" -> "the double-vote test round";
            default -> "the " + suffix.replace('-', ' ') + " vote";
        };
    }

    /**
     * The reason behind a verdict, taken from the findings the evaluator actually produced.
     *
     * <p>Every branch quotes the clause's own data, so the sentence a listener hears is the one the
     * ledger rule and the evidence produced, not a summary written in advance. An absent or empty
     * finding set yields the "no verified evidence" wording rather than anything resembling
     * approval.
     */
    static String reason(Recommendation.Verdict verdict, JsonNode findings) {
        if (verdict == Recommendation.Verdict.APPROVE) return "all requirements met";
        if (findings == null || !findings.isArray() || findings.isEmpty())
            return "no verified evidence to evaluate";
        for (JsonNode finding : findings) {
            if (!"fail".equals(Advisory.value(finding, "status"))) continue;
            JsonNode missing = finding.path("missing_phrases");
            if (missing.isArray() && !missing.isEmpty())
                return "missing \u201c" + missing.get(0).asText() + "\u201d";
            String detail = Advisory.value(finding, "detail");
            if (detail.startsWith("forbidden text present: "))
                return "contradicted by " + detail.substring("forbidden text present: ".length())
                        .replace('"', '\u201d');
            String clause = Advisory.value(finding, "clause_id");
            return clause.isBlank() ? "a requirement is not met"
                    : "requirement \u201c" + clause + "\u201d not met";
        }
        for (JsonNode finding : findings) {
            if (!"insufficient_evidence".equals(Advisory.value(finding, "status"))) continue;
            String clause = Advisory.value(finding, "clause_id");
            return clause.isBlank() ? "no evidence for one requirement"
                    : "no evidence for \u201c" + clause + "\u201d";
        }
        return "the evidence does not settle every requirement";
    }
}
