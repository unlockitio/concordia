package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewerTest {
    String proposal = "CAP is designed to sit above existing Canton asset and settlement infrastructure\n"
            + "downstream execution hooks for approved proposals";
    String issue = "downstream execution hooks for approved proposals";
    String sha() {
        byte[] bytes = proposal.getBytes(StandardCharsets.UTF_8);
        return Advisory.digest("SHA-1", ("blob " + bytes.length + "\0" + proposal).getBytes(StandardCharsets.UTF_8));
    }
    JsonNode json(String text) throws IOException { return Advisory.JSON.readTree(text); }
    Advisory.Api api() {
        return path -> {
            if (path.startsWith("search/issues?")) return json(path.contains("is%3Apr") ? "{\"items\":[{\"number\":184}]}" : "{\"items\":[{\"number\":540}]}");
            if (path.endsWith("/pulls/184")) return json("{\"number\":184,\"head\":{\"sha\":\"" + "c".repeat(40)
                    + "\"},\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
            if (path.contains("/pulls/184/files?")) return json("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + sha() + "\"}]");
            if (path.contains("/git/blobs/")) return json("{\"sha\":\"" + sha() + "\",\"encoding\":\"base64\",\"content\":\""
                    + Base64.getEncoder().encodeToString(proposal.getBytes(StandardCharsets.UTF_8)) + "\"}");
            if (path.endsWith("/issues/540")) return json("{\"number\":540,\"title\":\"Concordia #184 - Milestone 3\",\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/issues/540\",\"updated_at\":\"2026-10-14\",\"body\":\"" + issue + "\"}");
            throw new IOException("unexpected endpoint");
        };
    }
    Reviewer.LedgerRuleGateway ledger() {
        return () -> List.of(new Reviewer.EffectiveRule("#rule-v1", "architecture-review", 1,
                List.of(new Advisory.Clause("old", "anchor", "absent", "conflict", "wrong"))),
                new Reviewer.EffectiveRule("#rule-v2", "architecture-review", 2, List.of(
                        new Advisory.Clause("composition", "proposal section", "CAP is designed to sit above existing Canton asset and settlement infrastructure", "conflict", "CAP replaces infrastructure"),
                        new Advisory.Clause("execution", "milestone section", "downstream execution hooks for approved proposals", "conflict", "no downstream execution hooks for approved proposals"))));
    }
    static final class FakeComments implements Reviewer.CommentGateway {
        List<String> bodies = new ArrayList<>();
        int posts;
        boolean uncertain;
        public List<String> comments(int issue) { assertEquals(184, issue); return List.copyOf(bodies); }
        public void post(int issue, String body) throws IOException {
            assertEquals(184, issue);
            bodies.add(body); posts++;
            if (uncertain) throw new IOException("response lost after post");
        }
    }
    @Test void activeLedgerRuleIsDistinctFromFixtureAndMilestoneCannotBeEndorsed() throws Exception {
        ObjectNode output = Reviewer.review(ledger(), api());
        assertEquals("#rule-v2", output.path("rule_contract_id").asText());
        JsonNode milestone = output.path("reviews").get(0), pr = output.path("reviews").get(1);
        assertEquals(2, pr.path("rule_version").asInt());
        assertEquals("#rule-v2", pr.path("findings").get(0).path("rule_contract_id").asText());
        assertEquals("pass", pr.path("findings").get(0).path("status").asText());
        assertEquals("insufficient_evidence", milestone.path("findings").get(1).path("status").asText());
        FakeComments gateway = new FakeComments();
        assertEquals("not_opted_in", Reviewer.comment((ObjectNode) pr, false, Advisory.REPO, ledger(), api(), gateway));
        assertEquals("blocked_unverified_evidence", Reviewer.comment((ObjectNode) milestone, true, Advisory.REPO, ledger(), api(), gateway));
        assertEquals(0, gateway.posts);
        assertEquals("posted", Reviewer.comment((ObjectNode) pr, true, Advisory.REPO, ledger(), api(), gateway));
        assertTrue(gateway.bodies.get(0).contains(sha()));
        assertTrue(gateway.bodies.get(0).contains("#rule-v2"));
        assertEquals("already_posted", Reviewer.comment((ObjectNode) pr, true, Advisory.REPO, ledger(), api(), gateway));
        assertEquals(1, gateway.posts);
    }
    @Test void uncertainPostIsReconciledButIncompleteOrForgedEvidenceNeverPosts() throws Exception {
        ObjectNode pr = (ObjectNode) Reviewer.review(ledger(), api()).path("reviews").get(1);
        FakeComments gateway = new FakeComments();
        gateway.uncertain = true;
        assertEquals("posted_reconciled", Reviewer.comment(pr, true, Advisory.REPO, ledger(), api(), gateway));
        assertEquals("already_posted", Reviewer.comment(pr, true, Advisory.REPO, ledger(), api(), gateway));
        assertEquals(1, gateway.posts);
        ObjectNode forged = pr.deepCopy();
        forged.put("rule_authority", Advisory.AUTHORITY);
        assertEquals("blocked_unverified_evidence", Reviewer.comment(forged, true, Advisory.REPO, ledger(), api(), gateway));
        ObjectNode incomplete = pr.deepCopy();
        ((ObjectNode) incomplete.path("findings").get(0)).put("status", "fail");
        assertEquals("already_posted", Reviewer.comment(incomplete, true, Advisory.REPO, ledger(), api(), gateway));
        proposal = "CAP is designed to sit above existing Canton asset and settlement infrastructure";
        assertEquals("blocked_unverified_evidence", Reviewer.comment(pr, true, Advisory.REPO, ledger(), api(), gateway));
        assertThrows(IllegalArgumentException.class, () -> Reviewer.comment(pr, true, "other/repo", ledger(), api(), gateway));
    }
    private ValueOuterClass.Value text(String value) {
        return ValueOuterClass.Value.newBuilder().setText(value).build();
    }
    private ValueOuterClass.Value fieldRecord(String... fields) {
        ValueOuterClass.Record.Builder record = ValueOuterClass.Record.newBuilder();
        for (int i = 0; i < fields.length; i += 2)
            record.addFields(ValueOuterClass.RecordField.newBuilder().setLabel(fields[i]).setValue(text(fields[i + 1])));
        return ValueOuterClass.Value.newBuilder().setRecord(record).build();
    }
    @Test void sandboxFilterUsesPackageNameWhileEventRequiresExactPackageId() {
        var template = Reviewer.LocalSandboxGateway.ruleFilter().getCumulative(0).getTemplateFilter().getTemplateId();
        assertEquals("#cap-example-babycdf", template.getPackageId());
        assertEquals("Cap.Examples.BabyCdf.RuleVote", template.getModuleName());
        assertEquals("EffectiveRule", template.getEntityName());
    }
    @Test void decodesOnLedgerPayloadInsteadOfSourceFixture() throws Exception {
        ValueOuterClass.Record draft = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("ruleId").setValue(text("architecture-review")))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("version")
                        .setValue(ValueOuterClass.Value.newBuilder().setInt64(2))).build();
        ValueOuterClass.Record clause = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("clauseId").setValue(text("on-ledger")))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("expected")
                        .setValue(fieldRecord("anchor", "ledger anchor", "quote", "ledger quote")))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("contradicts")
                        .setValue(fieldRecord("anchor", "other anchor", "quote", "other quote"))).build();
        ValueOuterClass.Record args = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("draft")
                        .setValue(ValueOuterClass.Value.newBuilder().setRecord(draft)))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("clauses")
                        .setValue(ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder()
                                .addElements(ValueOuterClass.Value.newBuilder().setRecord(clause))))).build();
        EventOuterClass.CreatedEvent event = EventOuterClass.CreatedEvent.newBuilder()
                .setContractId("#ledger-contract").setCreateArguments(args).build();
        Reviewer.EffectiveRule rule = Reviewer.LocalSandboxGateway.decode(event);
        assertEquals("#ledger-contract", rule.contractId());
        assertEquals("architecture-review", rule.ruleId());
        assertEquals(2, rule.version());
        assertEquals("on-ledger", rule.clauses().get(0).id());
        assertEquals("ledger quote", rule.clauses().get(0).expected());
        assertEquals("other quote", rule.clauses().get(0).conflict());
        assertThrows(IOException.class, () -> Reviewer.LocalSandboxGateway.decode(
                event.toBuilder().setCreateArguments(ValueOuterClass.Record.getDefaultInstance()).build()));
    }
    @Test void noLiveAdapterFailsClosedAndAmbiguousRuleIsRejected() throws Exception {
        assertThrows(IOException.class, () -> Reviewer.review(new Reviewer.BlockedLedgerGateway(), api()));
        assertThrows(IOException.class, () -> Reviewer.review(() -> List.of(), api()));
        Reviewer.EffectiveRule duplicate = ledger().activeRules().get(1);
        assertThrows(IOException.class, () -> Reviewer.review(() -> List.of(duplicate, duplicate), api()));
        assertThrows(IllegalArgumentException.class, () -> new Reviewer.GitHubComments(false, Advisory.REPO, "token"));
        assertThrows(IllegalArgumentException.class, () -> new Reviewer.GitHubComments(true, "other/repo", "token"));
    }
}
