package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdvisoryTest {
    @TempDir Path directory;
    String proposal = "CAP is designed to sit above existing Canton asset and settlement infrastructure";
    String issue = "downstream execution hooks for approved proposals";
    String updated = "2026-10-14T12:34:17Z";
    boolean corrupt;
    boolean fail;
    boolean invalidPin;

    JsonNode obj(String json) throws IOException { return Advisory.JSON.readTree(json); }
    String blobSha() {
        byte[] raw = proposal.getBytes(StandardCharsets.UTF_8);
        return Advisory.digest("SHA-1", ("blob " + raw.length + "\0" + proposal).getBytes(StandardCharsets.UTF_8));
    }
    Advisory.Api api() {
        return path -> {
            if (fail) throw new IOException("temporary outage");
            if (path.startsWith("search/issues?"))
                return obj(path.contains("is%3Apr") ? "{\"items\":[{\"number\":184}]}" : "{\"items\":[{\"number\":540}]}");
            if (path.endsWith("/pulls/184"))
                return obj("{\"number\":184,\"head\":{\"sha\":\"" + "c".repeat(40) + "\"},\"state\":\"closed\",\"merged_at\":\"2026-06-11\",\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
            if (path.contains("/pulls/184/files?"))
                return obj("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + (invalidPin ? "bad" : blobSha()) + "\"}]");
            if (path.contains("/git/blobs/"))
                return obj("{\"sha\":\"" + blobSha() + "\",\"encoding\":\"base64\",\"content\":\"" + Base64.getEncoder().encodeToString((corrupt ? "different" : proposal).getBytes(StandardCharsets.UTF_8)) + "\"}");
            if (path.endsWith("/issues/540")) {
                ObjectNode node = (ObjectNode) obj("{\"number\":540,\"title\":\"Concordia #184 - Milestone 3\",\"state\":\"open\",\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/issues/540\"}");
                node.put("body", issue).put("updated_at", updated);
                return node;
            }
            throw new IOException("unexpected endpoint: " + path);
        };
    }
    Advisory.Fixture fixture() throws IOException { return Advisory.fixture(Advisory.RULE, Advisory.RULE_SHA256, 2); }

    @Test void proposalAndMilestoneHaveSeparateEvidenceAndAnchoredClauses() throws Exception {
        JsonNode result = Advisory.pollOnce(api(), fixture(), new HashSet<>());
        assertEquals(2, result.path("reviews").size());
        JsonNode milestone = result.path("reviews").get(0), pr = result.path("reviews").get(1);
        assertEquals("milestone_issue", milestone.path("kind").asText());
        assertEquals("proposal_pr", pr.path("kind").asText());
        assertEquals(blobSha(), pr.path("revision").asText());
        assertNotEquals(pr.path("revision").asText(), pr.path("head_commit").asText());
        assertEquals("pass", pr.path("findings").get(0).path("status").asText());
        assertEquals("Architectural Notes / CAP is designed to sit above", pr.path("findings").get(0).path("source_anchor").asText());
        assertEquals("insufficient_evidence", pr.path("findings").get(1).path("status").asText());
        assertEquals(Advisory.AUTHORITY, pr.path("rule_authority").asText());
        assertFalse(milestone.path("pinned").asBoolean());
        assertEquals(Advisory.sha256(issue), milestone.path("body_sha256").asText());
        for (JsonNode finding : milestone.path("findings")) assertEquals("insufficient_evidence", finding.path("status").asText());
    }

    @Test void invalidPinOrCorruptBlobCannotProduceProposalReview() throws Exception {
        invalidPin = true;
        JsonNode invalid = Advisory.pollOnce(api(), fixture(), new HashSet<>());
        assertEquals(1, invalid.path("reviews").size());
        assertEquals("proposal blob SHA missing", invalid.path("errors").get(0).asText());
        invalidPin = false; corrupt = true;
        JsonNode mismatch = Advisory.pollOnce(api(), fixture(), new HashSet<>());
        assertEquals(1, mismatch.path("reviews").size());
        assertEquals("proposal blob digest mismatch", mismatch.path("errors").get(0).asText());
    }

    @Test void changesRestartAndTransientFailureKeepPreviousState() throws Exception {
        Set<String> state = new HashSet<>();
        assertEquals(2, Advisory.pollOnce(api(), fixture(), state).path("reviews").size());
        assertEquals(0, Advisory.pollOnce(api(), fixture(), state).path("reviews").size());
        Path stateFile = directory.resolve("state.json");
        Advisory.saveState(stateFile, state);
        fail = true;
        assertThrows(IOException.class, () -> Advisory.pollOnce(api(), fixture(), state));
        assertEquals(state, Advisory.loadState(stateFile));
        fail = false; updated = "2026-10-15T00:00:00Z";
        issue = "no downstream execution hooks for approved proposals";
        JsonNode next = Advisory.pollOnce(api(), fixture(), Advisory.loadState(stateFile));
        assertEquals(1, next.path("reviews").size());
        assertEquals("milestone_issue", next.path("reviews").get(0).path("kind").asText());
        assertEquals("insufficient_evidence", next.path("reviews").get(0).path("findings").get(1).path("status").asText());
    }

    @Test void ballotProposalRequiresAuthorityAndImmutableEvidenceAndDeduplicates() throws Exception {
        JsonNode reviews = Advisory.pollOnce(api(), fixture(), new HashSet<>()).path("reviews");
        JsonNode milestone = reviews.get(0), grant = reviews.get(1);
        class FakeLedger implements Advisory.LedgerApi {
            Set<String> rounds = new HashSet<>();
            int submissions;
            boolean authorized = true;
            public boolean authorizedProposer(String party, String governor) { return authorized; }
            public boolean roundExists(String id) { return rounds.contains(id); }
            public void propose(Advisory.BallotCommand command) {
                assertEquals("grant", command.kind());
                assertEquals("member-a", command.proposer());
                rounds.add(command.roundId()); submissions++;
            }
        }
        FakeLedger ledger = new FakeLedger();
        assertEquals("blocked_unpinned_evidence", Advisory.prepareBallot(milestone, "member-a", "governor", true, ledger).path("status").asText());
        ObjectNode missingProvenance = grant.deepCopy();
        missingProvenance.remove("rule_authority");
        assertThrows(IllegalArgumentException.class, () -> Advisory.prepareBallot(missingProvenance, "member-a", "governor", true, ledger));
        assertEquals("preview_submission_blocked", Advisory.prepareBallot(grant, null, null, false, null).path("status").asText());
        ledger.authorized = false;
        assertEquals("blocked_unauthorized_party", Advisory.prepareBallot(grant, "member-a", "governor", true, ledger).path("status").asText());
        ledger.authorized = true;
        JsonNode submitted = Advisory.prepareBallot(grant, "member-a", "governor", true, ledger);
        assertTrue(submitted.path("submitted").asBoolean());
        assertEquals("already_proposed", Advisory.prepareBallot(grant, "member-a", "governor", true, ledger).path("status").asText());
        assertEquals(1, ledger.submissions);
        ObjectNode revised = grant.deepCopy();
        revised.put("revision", "a".repeat(40));
        assertTrue(Advisory.prepareBallot(revised, "member-a", "governor", true, ledger).path("submitted").asBoolean());
        assertEquals(2, ledger.submissions);
        assertNotEquals(submitted.path("round_id").asText(), ledger.rounds.stream().filter(s -> !s.equals(submitted.path("round_id").asText())).findFirst().orElseThrow());
    }

    @Test void uncertainSubmissionReconcilesWithoutRepeating() throws Exception {
        JsonNode grant = Advisory.pollOnce(api(), fixture(), new HashSet<>()).path("reviews").get(1);
        class UncertainLedger implements Advisory.LedgerApi {
            boolean exists;
            boolean committed = true;
            int calls;
            public boolean authorizedProposer(String p, String c) { return true; }
            public boolean roundExists(String r) { return exists; }
            public void propose(Advisory.BallotCommand c) throws IOException {
                calls++;
                exists = committed;
                throw new IOException("deadline exceeded after commit");
            }
        }
        UncertainLedger ledger = new UncertainLedger();
        assertEquals("submitted_reconciled", Advisory.prepareBallot(grant, "member-a", "governor", true, ledger)
                .path("status").asText());
        assertEquals("already_proposed", Advisory.prepareBallot(grant, "member-a", "governor", true, ledger)
                .path("status").asText());
        assertEquals(1, ledger.calls);
        ledger.exists = false;
        ledger.committed = false;
        assertThrows(IOException.class, () -> Advisory.prepareBallot(grant, "member-a", "governor", true, ledger));
        assertEquals(2, ledger.calls);
    }

    @Test void commandUsesVerifiedBlobVersionAndOnlyMemberActAs() {
        String sha = "f591787a772848e4537aba5d8ba02be1764647da";
        var command = new Advisory.BallotCommand("grant", "https://github.com/canton-foundation/canton-dev-fund/pull/184",
                sha, "babycdf:grant:184:" + sha, "member-a", "governor");
        var request = SandboxBallots.request(command, "a".repeat(64), "sandbox-user", 1_800_000_000);
        var cmds = request.getCommands();
        assertEquals("sandbox-user", cmds.getUserId());
        assertEquals(java.util.List.of("member-a"), cmds.getActAsList());
        assertEquals("babycdf-grant-" + sha, cmds.getCommandId());
        assertEquals(1, cmds.getCommandsCount());
        var exercise = cmds.getCommands(0).getExercise();
        assertEquals("ProposeAgenda", exercise.getChoice());
        assertEquals("governor", exercise.getContractId());
        assertEquals("AgendaGovernor", exercise.getTemplateId().getEntityName());
        assertEquals("Cap.Examples.BabyCdf.RuleVote", exercise.getTemplateId().getModuleName());
        var args = exercise.getChoiceArgument().getRecord();
        assertEquals("Grant", args.getFields(2).getValue().getVariant().getConstructor());
        assertEquals(sha, args.getFields(2).getValue().getVariant().getValue().getRecord()
                .getFields(2).getValue().getText());
        assertEquals("babycdf:grant:184:" + sha, args.getFields(1).getValue().getText());
        assertThrows(IllegalArgumentException.class, () -> new SandboxBallots(6865, "member-a", "bad", "governor", "user"));
    }

    @Test void liveSandboxRoundReadbackWhenConfigured() throws Exception {
        String config = System.getenv("BABYCDF_SANDBOX_READBACK");
        if (config == null) return;
        String[] parts = config.split(" ", -1);
        assertEquals(5, parts.length);
        try (var sandbox = new SandboxBallots(Integer.parseInt(parts[0]), parts[1], parts[2], parts[3], parts[4])) {
            String sha = "f591787a772848e4537aba5d8ba02be1764647da";
            String round = "babycdf:grant:184:" + sha;
            assertTrue(sandbox.authorizedProposer(parts[1], parts[3]));
            assertTrue(sandbox.roundExists(round));
            ObjectNode review = Advisory.JSON.createObjectNode()
                    .put("kind", "proposal_pr").put("number", 184)
                    .put("url", "https://github.com/canton-foundation/canton-dev-fund/pull/184")
                    .put("revision", sha).put("pinned", true)
                    .put("rule_authority", Advisory.AUTHORITY)
                    .put("rule_source_sha256", Advisory.RULE_SHA256);
            assertEquals("already_proposed", Advisory.prepareBallot(review, parts[1], parts[3], true, sandbox)
                    .path("status").asText());
            assertTrue(sandbox.roundExists(round));
            assertThrows(IOException.class, () -> sandbox.propose(new Advisory.BallotCommand("grant",
                    review.path("url").asText(), "a".repeat(40), "babycdf:grant:184:" + "a".repeat(40),
                    parts[1], parts[3])));
            assertThrows(IOException.class, () -> sandbox.roundExists("babycdf:grant:184:" + "a".repeat(40)));
        }
    }

    @Test void authoritativeRoundRequiresMatchingActiveActionSubject() throws Exception {
        String sha = "f591787a772848e4537aba5d8ba02be1764647da";
        var subject = com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                .addFields(textField("kind", "proposal"))
                .addFields(textField("url", "https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521/proposals/concordia.md"))
                .addFields(textField("contentVersion", sha)).build();
        var agenda = com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setVariant(
                com.daml.ledger.api.v2.ValueOuterClass.Variant.newBuilder().setConstructor("Grant")
                        .setValue(com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setRecord(subject))).build();
        var action = com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("action")
                .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                        .addFields(com.daml.ledger.api.v2.ValueOuterClass.RecordField.newBuilder()
                                .setLabel("agenda").setValue(agenda))).build();
        var proposal = com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("proposal")
                .setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.newBuilder()
                        .addFields(textField("roundId", "babycdf:grant:184:" + sha))
                        .addFields(com.daml.ledger.api.v2.ValueOuterClass.RecordField.newBuilder().setLabel("actionCid")
                                .setValue(com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setContractId("action")))).build();
        assertTrue(SandboxBallots.matches(proposal, action, "babycdf:grant:184:" + sha, sha));
        assertFalse(SandboxBallots.matches(proposal, action, "babycdf:grant:184:" + "a".repeat(40), sha));
        assertFalse(SandboxBallots.matches(proposal, action, "babycdf:grant:184:" + sha, "a".repeat(40)));
    }

    private static com.daml.ledger.api.v2.ValueOuterClass.RecordField textField(String label, String text) {
        return com.daml.ledger.api.v2.ValueOuterClass.RecordField.newBuilder().setLabel(label)
                .setValue(com.daml.ledger.api.v2.ValueOuterClass.Value.newBuilder().setText(text)).build();
    }

    @Test void fixturePinAndConflictPrecedence() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Advisory.fixture(Advisory.RULE, "bad", 2));
        assertThrows(IllegalArgumentException.class, () -> Advisory.fixture(Advisory.RULE, Advisory.RULE_SHA256, 3));
        assertEquals(1, Advisory.fixture(Advisory.RULE, Advisory.RULE_SHA256, 1).clauses().size());
        Path changed = directory.resolve("rule.daml");
        Files.writeString(changed, "changed");
        assertThrows(IOException.class, () -> Advisory.fixture(changed, Advisory.RULE_SHA256, 2));
        Advisory.Clause clause = fixture().clauses().get(0);
        String both = clause.expected() + "\n" + clause.conflict();
        assertEquals("fail", Advisory.finding(both, true, clause).path("status").asText());
        assertEquals("insufficient_evidence", Advisory.finding(both, false, clause).path("status").asText());
    }
}
