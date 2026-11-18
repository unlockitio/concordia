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
    @Test void activeLedgerRuleIsDistinctFromFixtureAndMilestoneCannotBeEndorsed() throws Exception {
        ObjectNode output = Reviewer.review(ledger(), api());
        assertEquals("#rule-v2", output.path("rule_contract_id").asText());
        JsonNode milestone = output.path("reviews").get(0), pr = output.path("reviews").get(1);
        assertEquals(2, pr.path("rule_version").asInt());
        assertEquals("#rule-v2", pr.path("findings").get(0).path("rule_contract_id").asText());
        assertEquals("pass", pr.path("findings").get(0).path("status").asText());
        assertEquals("insufficient_evidence", milestone.path("findings").get(1).path("status").asText());
    }

    /**
     * The agent must respond in text only. This asserts the absence of any publication seam, so
     * reintroducing a comment or submission path on Reviewer fails here rather than in production.
     */
    @Test void reviewerHasNoPublicationCapabilityAtAll() {
        for (var type : Reviewer.class.getDeclaredClasses())
            assertFalse(type.getSimpleName().toLowerCase().contains("comment"),
                    "unexpected comment type: " + type.getSimpleName());
        for (var method : Reviewer.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("comment") || name.contains("post") || name.contains("publish")
                    || name.contains("submit"), "unexpected publication method: " + method.getName());
        }
        // No HTTP or ledger-write type may appear anywhere in the agent's signatures.
        for (var method : Reviewer.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName().startsWith("java.net.http"));
            for (var parameter : method.getParameterTypes())
                assertFalse(parameter.getName().startsWith("java.net.http"),
                        "unexpected HTTP parameter on " + method.getName());
        }
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
                        .setValue(fieldRecord("anchor", "other anchor", "quote", "other quote")))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("criterion")
                        .setValue(text("ledger-stated criterion")))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("requiredPhrases")
                        .setValue(ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder()
                                .addElements(text("ledger required phrase")))))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("forbiddenPhrases")
                        .setValue(ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder()))).build();
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
        // The voted text criterion must come from the ledger payload too.
        assertEquals("ledger-stated criterion", rule.clauses().get(0).criterion());
        assertEquals(List.of("ledger required phrase"), rule.clauses().get(0).requiredPhrases());
        assertEquals(List.of(), rule.clauses().get(0).forbiddenPhrases());
        assertThrows(IOException.class, () -> Reviewer.LocalSandboxGateway.decode(
                event.toBuilder().setCreateArguments(ValueOuterClass.Record.getDefaultInstance()).build()));
    }
    /**
     * The verdict must follow the ledger's clause data, not a constant in the agent. Two fake
     * ledgers differ only in the clause criterion they publish, and the finding must flip.
     */
    @Test void reviewerEvaluatesLedgerSuppliedCriteriaNotCodeConstants() throws Exception {
        Reviewer.LedgerRuleGateway demanding = () -> List.of(new Reviewer.EffectiveRule(
                "#rule-v1", "architecture-review", 1,
                List.of(new Advisory.Clause("audit", "anchor", "expected", "c-anchor", "conflict",
                        "must commit to an independent security audit",
                        List.of("independent security audit"), List.of()))));
        Reviewer.LedgerRuleGateway relaxed = () -> List.of(new Reviewer.EffectiveRule(
                "#rule-v2", "architecture-review", 2,
                List.of(new Advisory.Clause("audit", "anchor", "expected", "c-anchor", "conflict",
                        "must evidence downstream execution hooks",
                        List.of("downstream execution hooks for approved proposals"), List.of()))));

        JsonNode failing = Reviewer.review(demanding, api()).path("reviews").get(1).path("findings").get(0);
        assertEquals("fail", failing.path("status").asText(), failing.toString());
        assertTrue(failing.path("detail").asText().contains("independent security audit"));
        assertEquals("must commit to an independent security audit", failing.path("criterion").asText());

        JsonNode passing = Reviewer.review(relaxed, api()).path("reviews").get(1).path("findings").get(0);
        assertEquals("pass", passing.path("status").asText(), passing.toString());
        // Same agent, same evidence, different ledger clause: the verdict follows the ledger.
        assertEquals(Recommendation.Verdict.REJECT,
                Recommendation.verdict(Reviewer.review(demanding, api()).path("reviews").get(1).path("findings")));
        assertEquals(Recommendation.Verdict.APPROVE,
                Recommendation.verdict(Reviewer.review(relaxed, api()).path("reviews").get(1).path("findings")));
    }

    @Test void noLiveAdapterFailsClosedAndAmbiguousRuleIsRejected() throws Exception {
        assertThrows(IOException.class, () -> Reviewer.review(new Reviewer.BlockedLedgerGateway(), api()));
        assertThrows(IOException.class, () -> Reviewer.review(() -> List.of(), api()));
        Reviewer.EffectiveRule duplicate = ledger().activeRules().get(1);
        assertThrows(IOException.class, () -> Reviewer.review(() -> List.of(duplicate, duplicate), api()));
    }
}
