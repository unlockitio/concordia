package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only GitHub discovery and demo-fixture review; optional demo-local ballot submission. */
public final class Advisory {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String REPO = "canton-foundation/canton-dev-fund";
    static final String PREFIX = "repos/" + REPO;
    static final String RULE_SHA256 = "93f543b533ba458d3b24370452769a7352777b4ac68dbed75607d09520e905d2";
    static final String AUTHORITY = "pinned_demo_voted_fixture_not_live_ledger";
    static final String SCOPE = "demo_only_advisory_no_votes_or_official_status_changes";
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    static final Pattern CLAUSE = Pattern.compile("(?m)^%s = ReviewClause \"([^\"]+)\"\\s*\\(PinnedExcerpt \"([^\"]+)\" \"([^\"]+)\"\\)\\s*\\(PinnedExcerpt \"([^\"]+)\" \"([^\"]+)\"\\)");
    static final Path RULE = Path.of("../impl/daml/Cap/Examples/BabyCdf/RuleVote.daml");

    private Advisory() { }

    public interface Api { JsonNode get(String path) throws IOException, InterruptedException; }

    public static final class GitHub implements Api {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        public JsonNode get(String path) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.github.com/" + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "concordia-babycdf-advisory-demo").GET();
            String token = System.getenv("GITHUB_TOKEN");
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("GitHub HTTP " + response.statusCode() + " for " + path);
            return JSON.readTree(response.body());
        }
    }

    /** Offline seed for the previously verified PR identity; blob bytes still pass collect's Git digest check. */
    public static final class OfflinePr184 implements Api {
        private static final String BLOB = "f591787a772848e4537aba5d8ba02be1764647da";
        private final byte[] bytes;
        public OfflinePr184(Path repository) throws IOException, InterruptedException {
            if (!Files.isDirectory(repository.resolve(".git"))) throw new IOException("offline blob repository missing .git");
            Process process = new ProcessBuilder("git", "-C", repository.toString(), "cat-file", "blob", BLOB)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            bytes = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) throw new IOException("pinned PR blob unavailable locally");
        }
        public JsonNode get(String path) throws IOException {
            if (path.equals(query("repo:" + REPO + " is:pr \"Proposal: Concordia\"")))
                return JSON.readTree("{\"items\":[{\"number\":184}]}");
            if (path.equals(PREFIX + "/pulls/184"))
                return JSON.readTree("{\"number\":184,\"head\":{\"sha\":\"c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521\"},\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
            if (path.equals(PREFIX + "/pulls/184/files?per_page=100"))
                return JSON.readTree("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + BLOB + "\"}]");
            if (path.equals(PREFIX + "/git/blobs/" + BLOB))
                return JSON.createObjectNode().put("sha", BLOB).put("encoding", "base64")
                        .put("content", Base64.getEncoder().encodeToString(bytes));
            if (path.equals(query("repo:" + REPO + " is:issue \"Concordia #184\"")))
                return JSON.readTree("{\"items\":[]}");
            throw new IOException("offline seed has no evidence for " + path);
        }
    }

    public record Clause(String id, String expectedAnchor, String expected, String conflictAnchor, String conflict) { }
    public record Fixture(int version, List<Clause> clauses) { }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    static String digest(String algorithm, byte[] bytes) {
        try { return hex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha256(String s) { return digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)); }

    public static Fixture fixture(Path source, String expected, int version) throws IOException {
        if (!RULE_SHA256.equals(expected) || (version != 1 && version != 2))
            throw new IllegalArgumentException("explicit recognized demo fixture SHA-256 and rule version required");
        byte[] raw = Files.readAllBytes(source);
        if (!expected.equals(digest("SHA-256", raw))) throw new IOException("demo voted fixture changed; review and explicitly repin");
        String text = new String(raw, StandardCharsets.UTF_8);
        List<Clause> clauses = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String name : List.of("compositionClause", "executionClause").subList(0, version)) {
            Matcher m = Pattern.compile(CLAUSE.pattern().formatted(name)).matcher(text);
            if (!m.find() || !ids.add(m.group(1))) throw new IOException("voted demo fixture clause cannot be parsed uniquely");
            clauses.add(new Clause(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)));
        }
        return new Fixture(version, List.copyOf(clauses));
    }

    static String value(JsonNode node, String field) { return node.path(field).asText(""); }
    static int number(JsonNode node) { return node.path("number").asInt(-1); }
    static String query(String q) {
        return "search/issues?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&per_page=100";
    }
    static ObjectNode item(String kind, int number, JsonNode resource, String revision, boolean pinned, String text) {
        ObjectNode item = JSON.createObjectNode();
        item.put("kind", kind).put("number", number).put("url", value(resource, "html_url"))
                .put("github_state", value(resource, "state")).put("revision", revision).put("pinned", pinned);
        if (!pinned) {
            item.put("body_sha256", sha256(text));
            item.put("evidence_warning", "GitHub updated_at and body hash are not an immutable issue revision; no definitive finding");
        }
        item.put("_review_text", text);
        return item;
    }

    public static ObjectNode collect(Api api) throws IOException, InterruptedException {
        ArrayNode inputs = JSON.createArrayNode();
        ArrayNode errors = JSON.createArrayNode();
        boolean proposalFound = false;
        for (JsonNode hit : api.get(query("repo:" + REPO + " is:pr \"Proposal: Concordia\"")).path("items")) {
            if (number(hit) != 184) continue;
            JsonNode pr = api.get(PREFIX + "/pulls/184");
            if (number(pr) != 184 || !SHA.matcher(value(pr.path("head"), "sha")).matches()) {
                errors.add("proposal PR identity not verified"); continue;
            }
            JsonNode files = api.get(PREFIX + "/pulls/184/files?per_page=100");
            for (JsonNode file : files) {
                if (!"proposals/concordia.md".equals(value(file, "filename"))) continue;
                String sha = value(file, "sha");
                if (!SHA.matcher(sha).matches()) { errors.add("proposal blob SHA missing"); continue; }
                JsonNode blob = api.get(PREFIX + "/git/blobs/" + sha);
                if (!"base64".equals(value(blob, "encoding")) || !sha.equals(value(blob, "sha"))) {
                    errors.add("proposal blob response unverified"); continue;
                }
                byte[] raw;
                try { raw = Base64.getMimeDecoder().decode(value(blob, "content")); }
                catch (IllegalArgumentException e) { errors.add("proposal blob encoding invalid"); continue; }
                byte[] header = ("blob " + raw.length + "\0").getBytes(StandardCharsets.US_ASCII);
                byte[] gitBlob = new byte[header.length + raw.length];
                System.arraycopy(header, 0, gitBlob, 0, header.length);
                System.arraycopy(raw, 0, gitBlob, header.length, raw.length);
                if (!sha.equals(digest("SHA-1", gitBlob))) { errors.add("proposal blob digest mismatch"); continue; }
                String text = new String(raw, StandardCharsets.UTF_8);
                ObjectNode item = item("proposal_pr", 184, pr, sha, true, text);
                item.put("head_commit", value(pr.path("head"), "sha"));
                item.set("github_merged_at", pr.path("merged_at"));
                item.put("source_url", "https://github.com/" + REPO + "/blob/" + value(pr.path("head"), "sha") + "/proposals/concordia.md");
                inputs.add(item); proposalFound = true;
            }
            if (!proposalFound) errors.add("proposal file not found in PR files");
        }
        if (!proposalFound) errors.add("proposal PR not discovered");
        Set<Integer> seen = new HashSet<>();
        for (JsonNode hit : api.get(query("repo:" + REPO + " is:issue \"Concordia #184\"")).path("items")) {
            int n = number(hit);
            if (n < 1 || !seen.add(n)) continue;
            JsonNode issue = api.get(PREFIX + "/issues/" + n);
            if (number(issue) != n) { errors.add("milestone issue #" + n + " identity not verified"); continue; }
            if (issue.has("pull_request") || !value(issue, "title").startsWith("Concordia #184 - Milestone ")) continue;
            String revision = value(issue, "updated_at");
            if (revision.isEmpty()) errors.add("milestone issue #" + n + " lacks revision time");
            inputs.add(item("milestone_issue", n, issue, revision, false, value(issue, "body")));
        }
        if (seen.isEmpty()) errors.add("no Concordia milestone issues discovered");
        List<JsonNode> sorted = new ArrayList<>(); inputs.forEach(sorted::add);
        sorted.sort(Comparator.comparing((JsonNode n) -> value(n, "kind")).thenComparingInt(Advisory::number));
        ObjectNode result = JSON.createObjectNode();
        ArrayNode ordered = result.put("scope", SCOPE).putArray("inputs");
        sorted.forEach(ordered::add);
        result.set("errors", errors);
        return result;
    }

    static ObjectNode finding(String text, boolean pinned, Clause clause) {
        ObjectNode result = JSON.createObjectNode();
        String status = "insufficient_evidence", anchor = "no matching pinned excerpt";
        if (pinned) {
            if (text.contains(clause.conflict())) { status = "fail"; anchor = clause.conflictAnchor(); }
            else if (text.contains(clause.expected())) { status = "pass"; anchor = clause.expectedAnchor(); }
        }
        result.put("clause_id", clause.id()).put("status", status).put("source_anchor", anchor)
                .put("source", pinned ? "verified_proposal_blob" : "unpinned_issue_body")
                .put("expected_quote", clause.expected()).put("contradictory_quote", clause.conflict());
        return result;
    }

    public static ObjectNode pollOnce(Api api, Fixture fixture, Set<String> state) throws IOException, InterruptedException {
        ObjectNode collected = collect(api);
        ObjectNode result = JSON.createObjectNode().put("scope", SCOPE);
        ArrayNode reviews = result.putArray("reviews");
        result.set("errors", collected.path("errors"));
        for (JsonNode input : collected.path("inputs")) {
            String key = JSON.writeValueAsString(List.of(REPO, value(input, "kind"), number(input),
                    value(input, "revision"), value(input, "body_sha256"), "architecture-review", fixture.version(), RULE_SHA256));
            if (state.contains(key)) continue;
            ObjectNode review = input.deepCopy();
            String text = value(review, "_review_text");
            review.remove("_review_text");
            review.put("rule_id", "architecture-review").put("rule_version", fixture.version())
                    .put("rule_source_sha256", RULE_SHA256).put("rule_authority", AUTHORITY);
            ArrayNode findings = review.putArray("findings");
            for (Clause clause : fixture.clauses()) findings.add(finding(text, input.path("pinned").asBoolean(), clause));
            reviews.add(review);
            state.add(key);
        }
        return result;
    }

    /** A request to open a ballot round, not a cast vote or an approval. */
    public record BallotCommand(String kind, String subjectUrl, String contentVersion,
                                String roundId, String proposer, String governorContractId) { }
    public interface LedgerApi {
        boolean authorizedProposer(String party, String governorContractId) throws Exception;
        boolean roundExists(String roundId) throws Exception;
        void propose(BallotCommand command) throws Exception;
    }

    /** Fail closed: discovery is not authority, and issue timestamps are not immutable pins. */
    public static ObjectNode prepareBallot(JsonNode review, String proposer, String governorContractId,
                                            boolean submissionAuthorized, LedgerApi ledger) throws Exception {
        String kind = value(review, "kind");
        String version = value(review, "revision");
        int number = number(review);
        String url = value(review, "url");
        // Only reviews produced by the pinned demo fixture may become command previews.
        if (!AUTHORITY.equals(value(review, "rule_authority"))
                || !RULE_SHA256.equals(value(review, "rule_source_sha256")))
            throw new IllegalArgumentException("review lacks verified demo rule provenance");
        boolean proposal = "proposal_pr".equals(kind) && number == 184;
        boolean milestone = "milestone_issue".equals(kind) && number > 0;
        if (!proposal && !milestone) throw new IllegalArgumentException("unknown ballot subject");
        ObjectNode result = JSON.createObjectNode().put("kind", kind).put("subject_url", url)
                .put("content_version", version).put("submitted", false);
        if (milestone) {
            result.put("agenda", "Milestone").put("command", "AgendaGovernor.ProposeAgenda")
                    .put("status", "blocked_unpinned_evidence")
                    .put("requirement", "immutable independently verified milestone evidence required; updated_at and body_sha256 are not pins");
            return result;
        }
        if (!review.path("pinned").asBoolean() || !SHA.matcher(version).matches()) {
            result.put("status", "blocked_unpinned_evidence");
            return result;
        }
        if (url.isBlank() || !url.equals("https://github.com/" + REPO + "/pull/184"))
            throw new IllegalArgumentException("proposal identity not verified");
        // Include the immutable version: a changed blob is a new round, never a retry of an old round.
        String roundId = "babycdf:grant:" + number + ":" + version;
        result.put("round_id", roundId).put("agenda", "Grant")
                .put("command", "AgendaGovernor.ProposeAgenda")
                .put("status", "preview_submission_blocked");
        if (!submissionAuthorized || proposer == null || proposer.isBlank() || governorContractId == null
                || governorContractId.isBlank() || ledger == null) return result;
        if (!ledger.authorizedProposer(proposer, governorContractId)) {
            result.put("status", "blocked_unauthorized_party");
            return result;
        }
        if (ledger.roundExists(roundId)) {
            result.put("status", "already_proposed");
            return result;
        }
        try {
            ledger.propose(new BallotCommand("grant", url, version, roundId, proposer, governorContractId));
        } catch (Exception uncertain) {
            try {
                if (ledger.roundExists(roundId)) {
                    result.put("status", "submitted_reconciled");
                    return result;
                }
            } catch (Exception reconciliation) { uncertain.addSuppressed(reconciliation); }
            throw uncertain; // Never blindly resubmit after an uncertain response.
        }
        result.put("submitted", true).put("status", "submitted_by_ledger_adapter");
        return result;
    }

    public static Set<String> loadState(Path path) throws IOException {
        JsonNode data = JSON.readTree(Files.readString(path));
        if (data.path("schema").asInt(-1) != 1 || !data.path("identities").isArray())
            throw new IOException("unsupported polling state schema");
        Set<String> state = new HashSet<>();
        for (JsonNode key : data.path("identities")) {
            if (!key.isTextual() || !state.add(key.asText())) throw new IOException("invalid or duplicate polling state identity");
        }
        return state;
    }
    public static void saveState(Path path, Set<String> state) throws IOException {
        ObjectNode data = JSON.createObjectNode().put("schema", 1);
        ArrayNode identities = data.putArray("identities");
        state.stream().sorted().forEach(identities::add);
        Path temp = Files.createTempFile(path.getParent(), ".poll-state-", ".tmp");
        try { Files.setPosixFilePermissions(temp, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX filesystem. */ }
        Files.writeString(temp, JSON.writeValueAsString(data));
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        double interval = 0;
        Path statePath = null;
        SandboxBallots sandbox = null;
        Path offlineRepository = null;
        for (int i = 0; i < args.length; i++) {
            if ("--interval".equals(args[i]) && ++i < args.length) interval = Double.parseDouble(args[i]);
            else if ("--state-file".equals(args[i]) && ++i < args.length) statePath = Path.of(args[i]).toAbsolutePath();
            else if ("--offline-pr184-repo".equals(args[i]) && ++i < args.length) offlineRepository = Path.of(args[i]);
            else if ("--local-sandbox".equals(args[i]) && i + 5 < args.length && sandbox == null) {
                sandbox = new SandboxBallots(Integer.parseInt(args[++i]), args[++i], args[++i], args[++i], args[++i]);
            } else throw new IllegalArgumentException("usage: [--interval positive-seconds] [--state-file direct-local-filename] [--offline-pr184-repo local-repository] [--local-sandbox port demo-member deployed-package-id governor-cid sandbox-user-id]");
        }
        if (interval != 0 && (!Double.isFinite(interval) || interval < 0.001 || interval > 86400))
            throw new IllegalArgumentException("interval must be between 0.001 and 86400 seconds");
        if (statePath != null) {
            Path root = Path.of(".").toRealPath();
            if (!statePath.normalize().getParent().equals(root) || Files.isSymbolicLink(statePath))
                throw new IllegalArgumentException("state file must be a direct regular file in the Java project directory");
            if (Files.exists(statePath) && !Files.isRegularFile(statePath)) throw new IllegalArgumentException("state file is not regular");
        }
        Fixture fixture = fixture(RULE, RULE_SHA256, 2);
        Set<String> state = statePath != null && Files.exists(statePath) ? loadState(statePath) : new HashSet<>();
        Api api = offlineRepository == null ? new GitHub() : new OfflinePr184(offlineRepository);
        try {
            do {
                try {
                    ObjectNode result = pollOnce(api, fixture, sandbox == null ? state : new HashSet<>());
                    ArrayNode ballots = result.putArray(sandbox == null ? "ballot_previews" : "ballot_submissions");
                    for (JsonNode review : result.path("reviews"))
                        ballots.add(prepareBallot(review, sandbox == null ? null : sandbox.party(),
                                sandbox == null ? null : sandbox.governorCid(), sandbox != null, sandbox));
                    result.put("ledger_submission", sandbox == null ? "blocked: explicit local sandbox configuration required"
                            : "demo_local_only_no_foundation_decision");
                    if (offlineRepository != null) result.put("discovery", "offline_pinned_pr184_seed_not_fresh_github_poll");
                    if (sandbox != null && offlineRepository != null) {
                        String round = "babycdf:grant:184:f591787a772848e4537aba5d8ba02be1764647da";
                        result.put("matching_active_proposal_and_action_count", sandbox.activeRoundCount(round));
                    }
                    System.out.println(JSON.writeValueAsString(result));
                    if (statePath != null) saveState(statePath, state);
                } catch (IOException e) {
                    ObjectNode error = JSON.createObjectNode().put("scope", SCOPE);
                    error.putArray("reviews"); error.putArray("errors").add("poll or sandbox unavailable: " + e.getMessage());
                    System.out.println(JSON.writeValueAsString(error));
                    if (interval == 0 || sandbox != null) throw e;
                }
                if (interval == 0) break;
                try { Thread.sleep((long) (interval * 1000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            } while (!Thread.currentThread().isInterrupted());
        } finally { if (sandbox != null) sandbox.close(); }
    }
}
