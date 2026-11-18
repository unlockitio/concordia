// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ReviewerOfflineTest {
    @TempDir Path temp;
    Reviewer.LedgerRuleGateway ledger() {
        return () -> List.of(new Reviewer.EffectiveRule("ledger-cid", "architecture-review", 7,
                List.of(new Advisory.Clause("C1", "anchor", "CAP", "conflict", "wrong"))));
    }
    @Test void corruptOfflineKeepsLedgerAuthorityAndNeverPasses() throws Exception {
        Path file = temp.resolve("blob"); Files.writeString(file, "corrupt");
        var result = Reviewer.review(ledger(), new OfflineFile(file));
        assertEquals("ledger-cid", result.path("rule_contract_id").asText());
        assertEquals(7, result.path("rule_version").asInt());
        assertEquals("offline_pinned_pr184_seed_not_fresh_github_poll", result.path("discovery").asText());
        assertFalse(result.path("errors").isEmpty());
        assertEquals(0, result.path("reviews").size());
        for (var review : result.path("reviews")) {
            assertFalse(review.path("pinned").asBoolean());
            for (var finding : review.path("findings")) assertNotEquals("pass", finding.path("status").asText());
        }
        assertTrue(AgentPolling.reviewerLine(result).contains("offline_pinned"));
        assertTrue(AgentPolling.reviewerLine(result).contains("errors=" + result.path("errors").size()));
    }
    @Test void validOfflineUsesLedgerVersionAndLabel() throws Exception {
        byte[] raw = "CAP synthetic proposal".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] header = ("blob " + raw.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] blob = new byte[header.length + raw.length];
        System.arraycopy(header, 0, blob, 0, header.length);
        System.arraycopy(raw, 0, blob, header.length, raw.length);
        String sha = Advisory.digest("SHA-1", blob);
        Advisory.Api synthetic = new Advisory.Api() {
            public boolean offlineSeed() { return true; }
            public com.fasterxml.jackson.databind.JsonNode get(String path) throws java.io.IOException {
                if (path.startsWith("search/issues?"))
                    return Advisory.JSON.readTree(path.contains("is%3Apr") ? "{\"items\":[{\"number\":184}]}" : "{\"items\":[]}");
                if (path.endsWith("/pulls/184"))
                    return Advisory.JSON.readTree("{\"number\":184,\"head\":{\"sha\":\"" + "c".repeat(40) + "\"},\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
                if (path.contains("/pulls/184/files?"))
                    return Advisory.JSON.readTree("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + sha + "\"}]");
                if (path.endsWith("/git/blobs/" + sha))
                    return Advisory.JSON.createObjectNode().put("sha", sha).put("encoding", "base64")
                            .put("content", java.util.Base64.getEncoder().encodeToString(raw));
                throw new java.io.IOException("no synthetic evidence for " + path);
            }
        };
        var result = Reviewer.review(ledger(), synthetic);
        assertEquals("ledger-cid", result.path("rule_contract_id").asText());
        assertEquals(7, result.path("rule_version").asInt());
        assertEquals("offline_pinned_pr184_seed_not_fresh_github_poll", result.path("discovery").asText());
        assertTrue(result.path("reviews").get(0).path("pinned").asBoolean());
        assertEquals("pass", result.path("reviews").get(0).path("findings").get(0).path("status").asText());
    }
    @Test void unavailableLedgerNeverReadsOfflineEvidence() throws Exception {
        int[] calls = {0};
        var output = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new java.io.PrintStream(output));
            Reviewer.runCycle(new Reviewer.BlockedLedgerGateway(), path -> { calls[0]++; throw new AssertionError(); });
        } finally { System.setOut(original); }
        assertEquals(0, calls[0]);
        assertTrue(output.toString().contains("BLOCKED"));
    }
}
