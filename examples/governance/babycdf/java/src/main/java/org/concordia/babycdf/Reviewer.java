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
 * <p>This agent responds in text only. It has no GitHub write/publication capability, no comment gateway and no ledger
 * submission seam: the GitHub comment-posting path was removed outright rather than left behind a
 * flag, so no configuration can make it publish. Its only outputs are log lines.
 */
public final class Reviewer {
    private Reviewer() { }

    public record EffectiveRule(String contractId, String ruleId, int version, List<Advisory.Clause> clauses, RuleDocument.Ref ruleRef, String ruleFailure) {
        public EffectiveRule(String contractId,String ruleId,int version,List<Advisory.Clause> clauses) { this(contractId,ruleId,version,clauses,null,null); }
    }

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
         * Reads visible AgendaProposal contracts. The demo opener signs both proposals and rules;
         * the same opener party filter therefore needs only CanReadAs opener. This gateway
         * constructs state queries only, never command submissions.
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
            ValueOuterClass.Record reference=record(field(draft,"ruleRef"));
            var commitValue=field(reference,"commit");
            String commit=commitValue.hasOptional()&&commitValue.getOptional().hasValue()?text(commitValue.getOptional().getValue()):null;
            var ref=new RuleDocument.Ref(text(field(reference,"location")),commit,text(field(reference,"sha256")));
            var resolved=RuleDocument.runtime(ref);
            boolean matches=resolved.verified()&&resolved.version()==version;
            return new EffectiveRule(event.getContractId(),ruleId,version,matches?resolved.clauses():List.of(),ref,matches?null:"rule_unverified");
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

    static EffectiveRule select(List<EffectiveRule> rules) throws IOException {
        if (rules == null || rules.isEmpty()) throw new IOException("BLOCKED: no visible active EffectiveRule");
        int latest = rules.stream().filter(r -> r != null && "architecture-review".equals(r.ruleId()))
                .mapToInt(EffectiveRule::version).max().orElseThrow(() -> new IOException("BLOCKED: no architecture-review EffectiveRule"));
        List<EffectiveRule> current = rules.stream().filter(r -> r != null && "architecture-review".equals(r.ruleId()) && r.version() == latest).toList();
        if (current.size() != 1) throw new IOException("BLOCKED: ambiguous active architecture-rule version");
        EffectiveRule rule = current.get(0);
        if (rule.contractId() == null || rule.contractId().isBlank() || latest < 1 || rule.clauses() == null
                || rule.clauses().isEmpty() && rule.ruleFailure()==null) throw new IOException("BLOCKED: incomplete effective rule contract");
        Set<String> ids = new HashSet<>();
        for (Advisory.Clause c : rule.clauses()) {
            if (c == null || c.id() == null || c.id().isBlank() || !ids.add(c.id())
                    || c.expectedAnchor() == null || c.expected() == null || c.expected().isBlank()
                    || c.conflictAnchor() == null || c.conflict() == null || c.conflict().isBlank())
                throw new IOException("BLOCKED: invalid effective rule clauses");
        }
        return rule;
    }

    /** Consistency re-check against collected head identity, not an independent GitHub observation. */
    static void verifyBundle(ObjectNode item) throws IOException {
        if(item.has("evidence_bundle")) {
            EvidenceBundle bundle=Advisory.JSON.treeToValue(item.get("evidence_bundle"),EvidenceBundle.class);
            if("proposal_pr".equals(Advisory.value(item,"kind"))&&!bundle.verified(Advisory.value(item,"head_commit")))
                item.put("supplemental_valid",false).put("evidence_warning","bundle_verification_failed");
        }
    }
    public static ObjectNode review(LedgerRuleGateway ledger, Advisory.Api evidence) throws Exception {
        return review(ledger, evidence, null);
    }

    static ObjectNode review(LedgerRuleGateway ledger, Advisory.Api evidence, SupplementalAssessment supplemental) throws Exception {
        return review(ledger,evidence,supplemental,null);
    }
    @FunctionalInterface interface Collection { ObjectNode collect() throws Exception; }
    static ObjectNode review(LedgerRuleGateway ledger,Advisory.Api evidence,SupplementalAssessment supplemental,Collection collector) throws Exception {
        EffectiveRule rule = select(ledger.activeRules());
        ObjectNode collected = collector==null?Advisory.collect(evidence,(supplemental!=null&&supplemental.enabled())||rule.clauses().stream().anyMatch(c->"PRESENCE_REQUIRED".equals(c.kind()))):collector.collect();
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
            verifyBundle(item);
            if(item.has("evidence_bundle")) item.put("absence_eligible",Presence.eligible(item,Advisory.JSON.treeToValue(item.get("evidence_bundle"),EvidenceBundle.class)));
            item.remove("_review_text");
            item.put("rule_authority", "authenticated_active_effective_rule")
                    .put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                    .put("rule_contract_id", rule.contractId());
            ArrayNode findings = item.putArray("findings");
            boolean pinned = item.path("pinned").asBoolean();
            if(rule.ruleFailure()!=null) findings.add(Advisory.JSON.createObjectNode().put("clause_id","rule_document").put("status","insufficient_evidence").put("detail",rule.ruleFailure()));
            for (Advisory.Clause clause : rule.clauses()) {
                // When the voted clause states a text criterion, evaluate the pinned
                // proposal text against it. Otherwise fall back to excerpt matching.
                ObjectNode finding = clause.requiredPhrases().isEmpty()
                        && clause.forbiddenPhrases().isEmpty()
                        && !"PRESENCE_REQUIRED".equals(clause.kind()) ? Advisory.finding(text, pinned, clause)
                        : ClauseCheck.evaluate(clause, text, pinned, item.has("evidence_bundle")?Advisory.JSON.treeToValue(item.get("evidence_bundle"),EvidenceBundle.class):null, item.path("absence_eligible").asBoolean(false));
                finding.put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                        .put("rule_contract_id", rule.contractId());
                findings.add(finding);
            }
            if (supplemental != null) supplemental.assess(item, text, rule, "review-proposal-or-milestone", "ledger_effective_rule");
            Advisory.stripBundleContents(item);
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
        runCycle(ledger, evidence, new HashSet<>(), null);
    }

    static void runCycle(LedgerRuleGateway ledger, Advisory.Api evidence, Set<String> emitted) throws Exception {
        runCycle(ledger, evidence, emitted, null);
    }

    /**
     * One polling cycle.
     *
     * <p>The machine-readable JSON on stdout and the detailed activity line on stderr are
     * unchanged and still produced whenever the demo log is not the active presentation, so the
     * existing CLI contract holds. When {@code demo} is presenting, those two are withheld by
     * default and reinstated by {@code --verbose} / {@code --json}: what is suppressed is volume,
     * never a check, and never an error.
     */
    static void runCycle(LedgerRuleGateway ledger, Advisory.Api evidence, Set<String> emitted,
                         DemoLog demo) throws Exception {
        runCycle(ledger, evidence, emitted, demo, null);
    }

    static void runCycle(LedgerRuleGateway ledger, Advisory.Api evidence, Set<String> emitted,
                         DemoLog demo, SupplementalAssessment supplemental) throws Exception {
        boolean presenting = demo != null && demo.enabled();
        boolean failed = false;
        ObjectNode result;
        // The outcome is recorded here but NOT acted on yet. Recovery of a
        // category may only be announced once the whole cycle's health is
        // known, because a cycle can read the rule successfully and still be
        // degraded elsewhere.
        String cycleFailure = null;
        try {
            result = review(ledger, evidence, supplemental);
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
            // The failure is never swallowed: it is stated once, in one short
            // sentence, and repeated only when it changes. The narration is
            // skipped for this result because it has nothing further to add:
            // the cycle produced no rule, no findings and no agendas.
            cycleFailure = "Waiting: " + DemoLog.summarize(blocked);
            failed = true;
        }
        if (presenting) {
            // `review` acquires two different things: the active rule from the
            // ledger and the proposal evidence. A thrown failure does not say
            // which stage failed, so the wording is deliberately neutral about
            // provenance rather than claiming the ledger specifically. For the
            // same reason the recovery sentence does not assert that the ledger
            // recovered, since it may never have been the thing that failed.
            if (cycleFailure != null) demo.failed(DemoLog.CYCLE, cycleFailure);
            else demo.recovered(DemoLog.CYCLE, "Cycle completed again; resuming evaluation.");
        }
        if (!presenting || demo.verbose()) System.err.println(AgentPolling.reviewerLine(result));
        if (presenting && !failed) ReviewerNarration.report(demo, result);
        // Text-only advisory recommendation, deduplicated per agenda per rule version.
        for (String recommendation : Recommendation.lines(result, emitted))
            if (!presenting || demo.verbose()) System.err.println(recommendation);
        if (!presenting || demo.verbose()) System.out.println(Advisory.JSON.writeValueAsString(result));
    }

    public static void main(String[] args) throws Exception {
        LedgerRuleGateway gateway = new BlockedLedgerGateway();
        long interval = 0;
        java.nio.file.Path offlineFile = null;
        boolean demoMode = false, verbose = AgentFlags.verboseFromEnvironment();
        for (int i = 0; i < args.length; i++) {
            if ("--interval".equals(args[i]) && i + 1 < args.length)
                interval = AgentPolling.intervalMillis(args[++i]);
            else if ("--offline-pr184-file".equals(args[i]) && i + 1 < args.length)
                offlineFile = java.nio.file.Path.of(args[++i]);
            // Presentation output is opt-in, so every existing invocation keeps
            // the exact machine-readable behaviour it had.
            else if ("--demo".equals(args[i])) demoMode = true;
            // Either flag restores the full detail, including inside --demo.
            else if ("--verbose".equals(args[i]) || "--json".equals(args[i])) verbose = true;
            else if ("--local-sandbox".equals(args[i]) && i + 3 < args.length) {
                gateway = new LocalSandboxGateway(Integer.parseInt(args[++i]), args[++i], args[++i]);
            } else throw new IllegalArgumentException("usage: Reviewer [--interval seconds] [--offline-pr184-file path] [--local-sandbox port party package-id] [--demo] [--verbose|--json]");
        }
        final LedgerRuleGateway ledger = gateway;
        final Advisory.Api evidence = offlineFile == null ? new Advisory.GitHub() : new OfflineFile(offlineFile);
        // Survives across polls so each agenda is recommended once per rule version.
        final Set<String> emitted = new HashSet<>();
        final DemoLog demo = new DemoLog("Reviewer", demoMode, verbose, System.err);
        demo.banner();
        // Never fall back to the pinned source fixture as an active ledger contract.
        try (SupplementalAssessment supplemental = SupplementalAssessment.runtime("Reviewer", System.err)) {
            AgentPolling.run(interval, () -> runCycle(ledger, evidence, emitted, demo, supplemental), Thread::sleep);
        }
    }
}
