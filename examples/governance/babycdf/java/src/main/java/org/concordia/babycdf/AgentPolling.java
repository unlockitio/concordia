// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded validation and interruptible scheduling, separate from ledger authority. */
final class AgentPolling {
    private AgentPolling() { }
    interface Cycle { void run() throws Exception; }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    static long intervalMillis(String text) {
        double seconds = Double.parseDouble(text);
        if (!Double.isFinite(seconds) || seconds < 0.001 || seconds > 86400)
            throw new IllegalArgumentException("interval must be 0.001..86400 seconds");
        return Math.max(1, (long) (seconds * 1000));
    }

    static void run(long interval, Cycle cycle, Sleeper sleeper) throws Exception {
        do {
            cycle.run();
            if (interval == 0 || Thread.currentThread().isInterrupted()) return;
            try { sleeper.sleep(interval); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (!Thread.currentThread().isInterrupted());
    }

    static String reviewerLine(JsonNode result) {
        if (!result.has("rule_version"))
            return "[reviewer] BLOCKED errors=" + result.path("errors").size()
                    + ": " + result.path("reason").asText("no verified active rule");
        return "[reviewer] active EffectiveRule v" + result.path("rule_version").asInt()
                + " cid=" + result.path("rule_contract_id").asText()
                + "; discovery=" + result.path("discovery").asText("github-read-only")
                + "; inputs=" + result.path("reviews").size()
                + "; errors=" + result.path("errors").size()
                + "; open agendas=" + result.path("open_agendas").size()
                + "; publication=none (text only)";
    }

    static String advisoryLine(JsonNode result) {
        StringBuilder findings = new StringBuilder();
        for (JsonNode review : result.path("reviews")) {
            findings.append(" PR#").append(review.path("number").asText("?"))
                    .append(" blob=").append(review.path("revision").asText("unverified"));
            for (JsonNode finding : review.path("findings"))
                findings.append(" ").append(finding.path("clause_id").asText())
                        .append(":").append(finding.path("status").asText("insufficient_evidence"));
        }
        return "[advisory] discovery=" + result.path("discovery").asText("github-read-only")
                + "; reviews=" + result.path("reviews").size()
                + "; submission=" + result.path("ledger_submission").asText("blocked") + findings;
    }
}
