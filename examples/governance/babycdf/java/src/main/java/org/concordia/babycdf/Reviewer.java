package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent reviewer. The discovery agent's pinned Daml source is never a ledger read.
 *
 * <p>This agent responds in text only. It has no HTTP client, no comment gateway and no ledger
 * submission seam: the GitHub comment-posting path was removed outright rather than left behind a
 * flag, so no configuration can make it publish. Its only outputs are log lines.
 */
public final class Reviewer {
    private Reviewer() { }

    public record EffectiveRule(String contractId, String ruleId, int version, List<Advisory.Clause> clauses) { }

    /**
     * A visible agenda, read as a read-only observer. Never a voting capability.
     *
     * <p>{@code subjectBody} matters for matching: an Architecture agenda reuses the proposal's
     * URL but carries its own design text, so the URL alone cannot identify what was reviewed.
     */
    public record OpenAgenda(String contractId, String roundId, String subjectUrl,
                             String subjectBody, String closesAt) { }

    /** An implementation must query active, visible EffectiveRule contracts as an authenticated ledger party. */
    public interface LedgerRuleGateway {
        List<EffectiveRule> activeRules() throws Exception;

        /** Visible, still-active agendas. Read-only; the default is "discovered none". */
        default List<OpenAgenda> openAgendas() throws Exception { return List.of(); }
    }

    public static final class BlockedLedgerGateway implements LedgerRuleGateway {
        public List<EffectiveRule> activeRules() throws IOException {
            throw new IOException("BLOCKED: no configured authenticated Canton ledger API adapter, endpoint or party; fixture is not live authority");
        }
    }

    /** Only loopback plaintext sandbox transport; never a remote or production ledger credential path. */
    public static final class LocalSandboxGateway implements LedgerRuleGateway {
        private static final String MODULE = "Cap.Examples.BabyCdf.RuleVote";
        private final int port;
        private final String party;
        private final String packageId;

        public LocalSandboxGateway(int port, String party, String packageId) {
            if (port < 1 || port > 65535 || party == null || party.isBlank()
                    || packageId == null || !packageId.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("sandbox port, read-authorized party and 64-hex deployed package ID required");
            this.port = port;
            this.party = party;
            this.packageId = packageId;
        }

        public List<EffectiveRule> activeRules() throws IOException {
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS);
                ValueOuterClass.Identifier id = ValueOuterClass.Identifier.newBuilder()
                        .setPackageId(packageId).setModuleName(MODULE).setEntityName("EffectiveRule").build();
                TransactionFilterOuterClass.Filters filters = ruleFilter();
                TransactionFilterOuterClass.EventFormat format = TransactionFilterOuterClass.EventFormat.newBuilder()
                        .putFiltersByParty(party, filters).setVerbose(true).build();
                long offset = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
                StateServiceOuterClass.GetActiveContractsRequest request = StateServiceOuterClass.GetActiveContractsRequest
                        .newBuilder().setActiveAtOffset(offset).setEventFormat(format).build();
                List<EffectiveRule> rules = new ArrayList<>();
                var responses = state.getActiveContracts(request);
                while (responses.hasNext()) {
                    var response = responses.next();
                    if (response.hasIncompleteAssigned() || response.hasIncompleteUnassigned())
                        throw new IOException("BLOCKED: incomplete sandbox ACS reassignment");
                    if (!response.hasActiveContract()) continue;
                    EventOuterClass.CreatedEvent event = response.getActiveContract().getCreatedEvent();
                    if (!event.hasTemplateId() || !id.equals(event.getTemplateId()) || !event.hasCreateArguments()
                            || !event.getWitnessPartiesList().contains(party))
                        throw new IOException("BLOCKED: unexpected or invisible EffectiveRule event");
                    rules.add(decode(event));
                }
                return rules;
            } catch (io.grpc.StatusRuntimeException e) {
                throw new IOException("BLOCKED: local sandbox ACS read failed: " + e.getStatus(), e);
            } finally {
                channel.shutdownNow();
            }
        }

        /**
         * Reads visible AgendaProposal contracts. The demo member is an observer on them, so this
         * needs no additional right: it is the same read-only party used for the rule, with no
         * actAs and no vote capability.
         */
        public List<OpenAgenda> openAgendas() throws IOException {
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                StateServiceGrpc.StateServiceBlockingStub state = StateServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS);
                ValueOuterClass.Identifier id = ValueOuterClass.Identifier.newBuilder()
                        .setPackageId(packageId).setModuleName(MODULE).setEntityName("AgendaProposal").build();
                TransactionFilterOuterClass.EventFormat format = TransactionFilterOuterClass.EventFormat.newBuilder()
                        .putFiltersByParty(party, agendaFilter()).setVerbose(true).build();
                long offset = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
                StateServiceOuterClass.GetActiveContractsRequest request = StateServiceOuterClass.GetActiveContractsRequest
                        .newBuilder().setActiveAtOffset(offset).setEventFormat(format).build();
                List<OpenAgenda> agendas = new ArrayList<>();
                var responses = state.getActiveContracts(request);
                while (responses.hasNext()) {
                    var response = responses.next();
                    if (!response.hasActiveContract()) continue;
                    EventOuterClass.CreatedEvent event = response.getActiveContract().getCreatedEvent();
                    if (!event.hasTemplateId() || !id.equals(event.getTemplateId()) || !event.hasCreateArguments())
                        continue;
                    agendas.add(decodeAgenda(event));
                }
                return agendas;
            } catch (io.grpc.StatusRuntimeException e) {
                throw new IOException("BLOCKED: local sandbox agenda read failed: " + e.getStatus(), e);
            } finally {
                channel.shutdownNow();
            }
        }

        static TransactionFilterOuterClass.Filters agendaFilter() {
            return TransactionFilterOuterClass.Filters.newBuilder()
                    .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                            .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                    .setTemplateId(ValueOuterClass.Identifier.newBuilder()
                                            .setPackageId("#cap-example-babycdf")
                                            .setModuleName(MODULE).setEntityName("AgendaProposal")))).build();
        }

        static OpenAgenda decodeAgenda(EventOuterClass.CreatedEvent event) throws IOException {
            String roundId = text(field(event.getCreateArguments(), "roundId"));
            ValueOuterClass.Record subject = record(field(event.getCreateArguments(), "subject"));
            String url = text(field(subject, "url"));
            String body = text(field(subject, "body"));
            ValueOuterClass.Value closes = field(event.getCreateArguments(), "closesAt");
            String closesAt = closes.hasTimestamp() ? String.valueOf(closes.getTimestamp()) : "";
            return new OpenAgenda(event.getContractId(), roundId, url, body, closesAt);
        }

        static TransactionFilterOuterClass.Filters ruleFilter() {
            // ACS template filters require a package name; returned events still carry the exact deployed ID.
            return TransactionFilterOuterClass.Filters.newBuilder()
                    .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                            .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                    .setTemplateId(ValueOuterClass.Identifier.newBuilder()
                                            .setPackageId("#cap-example-babycdf")
                                            .setModuleName(MODULE).setEntityName("EffectiveRule")))).build();
        }

        static EffectiveRule decode(EventOuterClass.CreatedEvent event) throws IOException {
            ValueOuterClass.Record draft = record(field(event.getCreateArguments(), "draft"));
            int version = Math.toIntExact(integer(field(draft, "version")));
            String ruleId = text(field(draft, "ruleId"));
            ValueOuterClass.Value clauses = field(event.getCreateArguments(), "clauses");
            if (!clauses.hasList()) throw new IOException("BLOCKED: EffectiveRule clauses not a list");
            List<Advisory.Clause> parsed = new ArrayList<>();
            for (ValueOuterClass.Value clause : clauses.getList().getElementsList()) {
                ValueOuterClass.Record c = record(clause);
                ValueOuterClass.Record expected = record(field(c, "expected"));
                ValueOuterClass.Record conflict = record(field(c, "contradicts"));
                // The voted text criterion is ledger data; the agent must not substitute
                // a constant of its own when the rule states one.
                parsed.add(new Advisory.Clause(text(field(c, "clauseId")), text(field(expected, "anchor")),
                        text(field(expected, "quote")), text(field(conflict, "anchor")), text(field(conflict, "quote")),
                        text(field(c, "criterion")), textList(field(c, "requiredPhrases")),
                        textList(field(c, "forbiddenPhrases"))));
            }
            return new EffectiveRule(event.getContractId(), ruleId, version, List.copyOf(parsed));
        }

        static List<String> textList(ValueOuterClass.Value value) throws IOException {
            if (!value.hasList()) throw new IOException("BLOCKED: EffectiveRule clause phrase list malformed");
            List<String> items = new ArrayList<>();
            for (ValueOuterClass.Value item : value.getList().getElementsList()) items.add(text(item));
            return List.copyOf(items);
        }

        private static ValueOuterClass.Value field(ValueOuterClass.Record record, String label) throws IOException {
            for (ValueOuterClass.RecordField field : record.getFieldsList())
                if (label.equals(field.getLabel()) && field.hasValue()) return field.getValue();
            throw new IOException("BLOCKED: missing EffectiveRule field " + label);
        }
        private static ValueOuterClass.Record record(ValueOuterClass.Value value) throws IOException {
            if (!value.hasRecord()) throw new IOException("BLOCKED: malformed EffectiveRule record");
            return value.getRecord();
        }
        private static String text(ValueOuterClass.Value value) throws IOException {
            if (!value.hasText()) throw new IOException("BLOCKED: malformed EffectiveRule text");
            return value.getText();
        }
        private static long integer(ValueOuterClass.Value value) throws IOException {
            if (!value.hasInt64()) throw new IOException("BLOCKED: malformed EffectiveRule version");
            return value.getInt64();
        }
    }

    private static EffectiveRule select(List<EffectiveRule> rules) throws IOException {
        if (rules == null || rules.isEmpty()) throw new IOException("BLOCKED: no visible active EffectiveRule");
        int latest = rules.stream().filter(r -> r != null && "architecture-review".equals(r.ruleId()))
                .mapToInt(EffectiveRule::version).max().orElseThrow(() -> new IOException("BLOCKED: no architecture-review EffectiveRule"));
        List<EffectiveRule> current = rules.stream().filter(r -> r != null && "architecture-review".equals(r.ruleId()) && r.version() == latest).toList();
        if (current.size() != 1) throw new IOException("BLOCKED: ambiguous active architecture-rule version");
        EffectiveRule rule = current.get(0);
        if (rule.contractId() == null || rule.contractId().isBlank() || latest < 1 || rule.clauses() == null
                || rule.clauses().isEmpty()) throw new IOException("BLOCKED: incomplete effective rule contract");
        Set<String> ids = new HashSet<>();
        for (Advisory.Clause c : rule.clauses()) {
            if (c == null || c.id() == null || c.id().isBlank() || !ids.add(c.id())
                    || c.expectedAnchor() == null || c.expected() == null || c.expected().isBlank()
                    || c.conflictAnchor() == null || c.conflict() == null || c.conflict().isBlank())
                throw new IOException("BLOCKED: invalid effective rule clauses");
        }
        return rule;
    }

    public static ObjectNode review(LedgerRuleGateway ledger, Advisory.Api evidence) throws Exception {
        EffectiveRule rule = select(ledger.activeRules());
        ObjectNode collected = Advisory.collect(evidence);
        ObjectNode result = Advisory.JSON.createObjectNode().put("scope", Advisory.SCOPE)
                .put("rule_authority", "authenticated_active_effective_rule")
                .put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                .put("rule_contract_id", rule.contractId());
        if (evidence.offlineSeed())
            result.put("discovery", "offline_pinned_pr184_seed_not_fresh_github_poll");
        result.set("errors", collected.path("errors"));
        ArrayNode reviews = result.putArray("reviews");
        for (JsonNode input : collected.path("inputs")) {
            ObjectNode item = input.deepCopy();
            String text = Advisory.value(item, "_review_text");
            item.remove("_review_text");
            item.put("rule_authority", "authenticated_active_effective_rule")
                    .put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                    .put("rule_contract_id", rule.contractId());
            ArrayNode findings = item.putArray("findings");
            boolean pinned = item.path("pinned").asBoolean();
            for (Advisory.Clause clause : rule.clauses()) {
                // When the voted clause states a text criterion, evaluate the pinned
                // proposal text against it. Otherwise fall back to excerpt matching.
                ObjectNode finding = clause.requiredPhrases().isEmpty()
                        && clause.forbiddenPhrases().isEmpty()
                        ? Advisory.finding(text, pinned, clause)
                        : ClauseCheck.evaluate(clause, text, pinned);
                finding.put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                        .put("rule_contract_id", rule.contractId());
                findings.add(finding);
            }
            reviews.add(item);
        }
        // Read-only agenda discovery. A failure here must not fail the review: the
        // recommendation is advisory, so it degrades to "no open agenda seen".
        ArrayNode agendas = result.putArray("open_agendas");
        try {
            // closesAt is microseconds since the epoch, as carried by the Ledger API.
            long nowMicros = System.currentTimeMillis() * 1000L;
            for (OpenAgenda agenda : ledger.openAgendas()) {
                long closes = agenda.closesAt().isBlank() ? 0L : Long.parseLong(agenda.closesAt());
                // Recommending on a closed agenda would be advice nobody can act on.
                if (closes > 0L && closes <= nowMicros) continue;
                agendas.add(Advisory.JSON.createObjectNode()
                        .put("contract_id", agenda.contractId()).put("round_id", agenda.roundId())
                        .put("subject_url", agenda.subjectUrl()).put("subject_body", agenda.subjectBody())
                        .put("closes_at", agenda.closesAt()));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception degraded) {
            String detail = degraded.getMessage();
            result.put("open_agenda_discovery", "unavailable: "
                    + (detail == null || detail.isBlank() ? degraded.getClass().getSimpleName() : detail));
        }
        return result;
    }

    static void runCycle(LedgerRuleGateway ledger, Advisory.Api evidence) throws Exception {
        runCycle(ledger, evidence, new HashSet<>());
    }

    static void runCycle(LedgerRuleGateway ledger, Advisory.Api evidence, Set<String> emitted) throws Exception {
        ObjectNode result;
        try {
            result = review(ledger, evidence);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception blocked) {
            String detail = blocked.getMessage();
            String reason = blocked.getClass().getSimpleName() + ": "
                    + (detail == null || detail.isBlank() ? "cycle failed without a message" : detail);
            result = Advisory.JSON.createObjectNode().put("status", "BLOCKED")
                    .put("reason", reason).put("publication", "none: text output only");
            result.putArray("errors").add(reason);
        }
        System.err.println(AgentPolling.reviewerLine(result));
        // Text-only advisory recommendation, deduplicated per agenda per rule version.
        for (String recommendation : Recommendation.lines(result, emitted)) System.err.println(recommendation);
        System.out.println(Advisory.JSON.writeValueAsString(result));
    }

    public static void main(String[] args) throws Exception {
        LedgerRuleGateway gateway = new BlockedLedgerGateway();
        long interval = 0;
        java.nio.file.Path offlineFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--interval".equals(args[i]) && i + 1 < args.length)
                interval = AgentPolling.intervalMillis(args[++i]);
            else if ("--offline-pr184-file".equals(args[i]) && i + 1 < args.length)
                offlineFile = java.nio.file.Path.of(args[++i]);
            else if ("--local-sandbox".equals(args[i]) && i + 3 < args.length) {
                gateway = new LocalSandboxGateway(Integer.parseInt(args[++i]), args[++i], args[++i]);
            } else throw new IllegalArgumentException("usage: Reviewer [--interval seconds] [--offline-pr184-file path] [--local-sandbox port party package-id]");
        }
        final LedgerRuleGateway ledger = gateway;
        final Advisory.Api evidence = offlineFile == null ? new Advisory.GitHub() : new OfflineFile(offlineFile);
        // Survives across polls so each agenda is recommended once per rule version.
        final Set<String> emitted = new HashSet<>();
        // Never fall back to the pinned source fixture as an active ledger contract.
        AgentPolling.run(interval, () -> runCycle(ledger, evidence, emitted), Thread::sleep);
    }
}
