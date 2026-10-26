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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent reviewer. The discovery agent's pinned Daml source is never a ledger read. */
public final class Reviewer {
    private Reviewer() { }

    public record EffectiveRule(String contractId, String ruleId, int version, List<Advisory.Clause> clauses) { }

    /** An implementation must query active, visible EffectiveRule contracts as an authenticated ledger party. */
    public interface LedgerRuleGateway {
        List<EffectiveRule> activeRules() throws Exception;
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
                parsed.add(new Advisory.Clause(text(field(c, "clauseId")), text(field(expected, "anchor")),
                        text(field(expected, "quote")), text(field(conflict, "anchor")), text(field(conflict, "quote"))));
            }
            return new EffectiveRule(event.getContractId(), ruleId, version, List.copyOf(parsed));
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

    public interface CommentGateway {
        List<String> comments(int issue) throws Exception;
        void post(int issue, String body) throws Exception;
    }

    /** Deliberate opt-in, scoped to one repository; credentials are never taken from the discovery API. */
    public static final class GitHubComments implements CommentGateway {
        private final String token;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        public GitHubComments(boolean optIn, String repository, String token) {
            if (!optIn || !Advisory.REPO.equals(repository) || token == null || token.isBlank())
                throw new IllegalArgumentException("comment posting requires explicit opt-in, allowlisted repository and token");
            this.token = token;
        }

        private JsonNode request(String path, String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + Advisory.REPO + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token).header("User-Agent", "concordia-babycdf-reviewer");
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != (body == null ? 200 : 201))
                throw new IOException("GitHub comment API HTTP " + response.statusCode());
            return Advisory.JSON.readTree(response.body());
        }

        public List<String> comments(int issue) throws Exception {
            if (issue != 184) throw new IllegalArgumentException("only proposal PR #184 is commentable");
            // Full pagination is required for reconciliation: an incomplete page cannot prove absence.
            java.util.ArrayList<String> found = new java.util.ArrayList<>();
            for (int page = 1; ; page++) {
                if (page > 100) throw new IOException("comment reconciliation exceeds 100 pages");
                JsonNode data = request("/issues/184/comments?per_page=100&page=" + page, null);
                if (!data.isArray()) throw new IOException("invalid GitHub comment listing");
                for (JsonNode comment : data) found.add(Advisory.value(comment, "body"));
                if (data.size() < 100) return found;
            }
        }

        public void post(int issue, String body) throws Exception {
            if (issue != 184) throw new IllegalArgumentException("only proposal PR #184 is commentable");
            ObjectNode payload = Advisory.JSON.createObjectNode().put("body", body);
            request("/issues/184/comments", Advisory.JSON.writeValueAsString(payload));
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
            for (Advisory.Clause clause : rule.clauses()) {
                ObjectNode finding = Advisory.finding(text, item.path("pinned").asBoolean(), clause);
                finding.put("rule_id", rule.ruleId()).put("rule_version", rule.version())
                        .put("rule_contract_id", rule.contractId());
                findings.add(finding);
            }
            reviews.add(item);
        }
        return result;
    }

    /** No automatic retry after an uncertain post. Caller must reconcile before any new attempt. */
    public static String comment(ObjectNode review, boolean optIn, String repository,
                                 LedgerRuleGateway ledger, Advisory.Api evidence, CommentGateway gateway) throws Exception {
        if (!optIn) return "not_opted_in";
        if (!Advisory.REPO.equals(repository) || ledger == null || evidence == null || gateway == null)
            throw new IllegalArgumentException("live rule, evidence, comment gateway and allowlisted repository required");
        if (!"authenticated_active_effective_rule".equals(Advisory.value(review, "rule_authority"))
                || !"architecture-review".equals(Advisory.value(review, "rule_id"))
                || Advisory.value(review, "rule_contract_id").isBlank() || review.path("rule_version").asInt(0) < 1
                || !"proposal_pr".equals(Advisory.value(review, "kind")) || Advisory.number(review) != 184
                || !review.path("pinned").asBoolean() || !Advisory.SHA.matcher(Advisory.value(review, "revision")).matches()
                || !("https://github.com/" + Advisory.REPO + "/pull/184").equals(Advisory.value(review, "url")))
            return "blocked_unverified_evidence";
        // Caller-supplied JSON is never proof of ledger authority or GitHub pinning.
        ObjectNode fresh = review(ledger, evidence);
        JsonNode matched = null;
        for (JsonNode item : fresh.path("reviews")) {
            if ("proposal_pr".equals(Advisory.value(item, "kind"))
                    && Advisory.value(review, "revision").equals(Advisory.value(item, "revision"))
                    && Advisory.value(review, "rule_contract_id").equals(Advisory.value(item, "rule_contract_id")))
                matched = item;
        }
        if (matched == null) return "blocked_unverified_evidence";
        review = (ObjectNode) matched;
        JsonNode findings = review.path("findings");
        if (!findings.isArray() || findings.isEmpty()) return "blocked_incomplete_review";
        for (JsonNode f : findings) {
            if (!"pass".equals(Advisory.value(f, "status"))
                    || !Advisory.value(review, "rule_contract_id").equals(Advisory.value(f, "rule_contract_id"))
                    || f.path("rule_version").asInt(-1) != review.path("rule_version").asInt())
                return "blocked_incomplete_review";
        }
        String identity = Advisory.REPO + ":184:" + Advisory.value(review, "revision") + ":"
                + Advisory.value(review, "rule_contract_id") + ":" + review.path("rule_version").asInt();
        String marker = "<!-- babycdf-review:" + Advisory.sha256(identity) + " -->";
        StringBuilder body = new StringBuilder(marker).append("\nDemo-only advisory review of verified proposal blob `")
                .append(Advisory.value(review, "revision")).append("` against active EffectiveRule contract `")
                .append(Advisory.value(review, "rule_contract_id")).append("` (")
                .append(Advisory.value(review, "rule_id")).append(" v")
                .append(review.path("rule_version").asInt()).append("). This is not a Foundation decision.\n");
        for (JsonNode f : findings) body.append("\n- `").append(Advisory.value(f, "clause_id").replace("`", ""))
                .append("`: pass, ").append(Advisory.value(f, "source_anchor").replace("\n", " ").replace("`", ""))
                .append(".");
        for (String existing : gateway.comments(184)) if (existing.contains(marker)) return "already_posted";
        try {
            gateway.post(184, body.toString());
            return "posted";
        } catch (Exception uncertain) {
            try {
                for (String existing : gateway.comments(184)) if (existing.contains(marker)) return "posted_reconciled";
            } catch (Exception reconciliation) { uncertain.addSuppressed(reconciliation); }
            throw uncertain;
        }
    }

    public static void main(String[] args) throws Exception {
        LedgerRuleGateway gateway = new BlockedLedgerGateway();
        if (args.length == 4 && "--local-sandbox".equals(args[0]))
            gateway = new LocalSandboxGateway(Integer.parseInt(args[1]), args[2], args[3]);
        else if (args.length != 0)
            throw new IllegalArgumentException("usage: Reviewer [--local-sandbox port read-authorized-party deployed-package-id]");
        // Never fall back to the pinned source fixture as an active ledger contract.
        try {
            System.out.println(Advisory.JSON.writeValueAsString(review(gateway, new Advisory.GitHub())));
        } catch (IOException blocked) {
            System.out.println(Advisory.JSON.createObjectNode().put("status", "BLOCKED")
                    .put("reason", blocked.getMessage()).put("comments", "disabled"));
        }
    }
}
