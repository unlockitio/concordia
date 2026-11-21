package org.concordia.babycdf;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.Map;

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
    static final String RULE_SHA256 = documentHash();
    private static String documentHash() {
        try { return RuleDocument.sha(Files.readAllBytes(RuleDocument.rulesRoot().resolve("rule-v2.json"))); }
        catch(Exception unavailable) { return ""; }
    } // Offline v2 document identity, never ledger authority.
    static final String AUTHORITY = "pinned_demo_voted_fixture_not_live_ledger";
    static final String SCOPE = "demo_only_advisory_no_votes_or_official_status_changes";
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    static final Pattern CLAUSE = Pattern.compile("(?m)^%s = ReviewClause \"([^\"]+)\"\\s*\\(PinnedExcerpt \"([^\"]+)\" \"([^\"]+)\"\\)\\s*\\(PinnedExcerpt \"([^\"]+)\" \"([^\"]+)\"\\)");
    static final Path RULE = Path.of("../rules/rule-v2.json");

    private Advisory() { }

    public interface Api {
        JsonNode get(String path) throws IOException, InterruptedException;
        // Discovery provenance only; every offline adapter must override this.
        // It never bypasses blob digest checks or supplies ledger rule authority.
        default boolean offlineSeed() { return false; }
        default JsonNode fresh(String path) throws IOException,InterruptedException { return get(path); }
    }

    public static final class GitHub implements Api {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
        private final IntakeGitHub auth;
        HttpClient.Redirect redirectPolicy() { return client.followRedirects(); }
        private long windowStart=System.nanoTime();
        private int requests;
        private final Map<String,JsonNode> recent=new HashMap<>();
        private final Map<String,Long> fetched=new HashMap<>();
        public GitHub() { this(System.getenv()); }
        GitHub(Map<String,String> env) {
            try { auth=env.containsKey("INTAKE_GITHUB_TOKEN_FILE")?IntakeGitHub.configured(env):null; }
            catch(IOException invalid) { throw new IllegalArgumentException("GitHub file credential invalid"); }
        }
        @Override public synchronized JsonNode fresh(String path) throws IOException,InterruptedException {
            recent.remove(path); fetched.remove(path); return get(path);
        }
        public synchronized JsonNode get(String path) throws IOException, InterruptedException {
            long now=System.nanoTime();
            if(recent.containsKey(path)&&now-fetched.get(path)<Duration.ofMinutes(10).toNanos()) return recent.get(path);
            if(now-windowStart>Duration.ofHours(1).toNanos()) { windowStart=now; requests=0; }
            if(auth==null&&requests>=60) throw new IOException("GitHub anonymous hourly request budget exhausted");
            requests++;
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.github.com/" + path))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "concordia-babycdf-advisory-demo").GET();
            if(auth!=null) auth.authorizeRead(request,URI.create("https://api.github.com/"+path));
            HttpResponse<java.io.InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try(var input=response.body()) {
                if (response.statusCode() != 200) throw new IOException("GitHub HTTP " + response.statusCode());
                byte[] bytes=readBounded(input,Duration.ofSeconds(15));
                if(bytes.length>2*1024*1024) throw new IOException("GitHub response limit");
                JsonNode parsed=JSON.readTree(bytes);
                if(recent.size()<64&&!path.contains("/git/blobs/")&&bytes.length<=256*1024) { recent.put(path,parsed); fetched.put(path,now); }
                return parsed;
            }
        }
    }

    /** Offline seed for the previously verified PR identity; blob bytes still pass collect's Git digest check. */
    public static final class OfflinePr184 implements Api {
        private static final String BLOB = "f591787a772848e4537aba5d8ba02be1764647da";
        private final byte[] bytes;
        public boolean offlineSeed() { return true; }
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

    /**
     * One review clause. {@code criterion}, {@code requiredPhrases} and {@code forbiddenPhrases}
     * are the voted text criterion carried as ledger data; the source fixture has no criterion, so
     * the compact constructor defaults them to empty rather than inventing a requirement.
     */
    public record Clause(String id, String expectedAnchor, String expected, String conflictAnchor,
                         String conflict, String criterion, List<String> requiredPhrases,
                         List<String> forbiddenPhrases, String kind, List<String> indicators) {
        public Clause(String id,String expectedAnchor,String expected,String conflictAnchor,String conflict,String criterion,List<String> requiredPhrases,List<String> forbiddenPhrases,String kind) {
            this(id,expectedAnchor,expected,conflictAnchor,conflict,criterion,requiredPhrases,forbiddenPhrases,kind,List.of());
        }
        public Clause(String id,String expectedAnchor,String expected,String conflictAnchor,String conflict,String criterion,List<String> requiredPhrases,List<String> forbiddenPhrases) {
            this(id,expectedAnchor,expected,conflictAnchor,conflict,criterion,requiredPhrases,forbiddenPhrases,"CONTRADICTION_OR_SUPPORT");
        }
        public Clause {
            indicators=indicators==null?List.of():List.copyOf(indicators);
            criterion = criterion == null ? "" : criterion;
            requiredPhrases = requiredPhrases == null ? List.of() : List.copyOf(requiredPhrases);
            forbiddenPhrases = forbiddenPhrases == null ? List.of() : List.copyOf(forbiddenPhrases);
        }

        /** Fixture-shaped clause with no voted text criterion. */
        public Clause(String id, String expectedAnchor, String expected, String conflictAnchor, String conflict) {
            this(id, expectedAnchor, expected, conflictAnchor, conflict, "", List.of(), List.of());
        }
    }
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
        if(version!=1&&version!=2||expected==null||!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid offline document identity");
        Path document=Path.of("../rules/rule-v"+version+".json");
        if(!source.equals(RULE))throw new IOException("unrecognized offline fixture location");
        var resolved=RuleDocument.local(new RuleDocument.Ref(document.getFileName().toString(),null,expected),document.getParent());
        if(!resolved.verified())throw new IOException("rule_unverified");
        if(resolved.version()!=version)throw new IOException("rule_unverified");
        return new Fixture(version,resolved.clauses());
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

    static byte[] readBounded(java.io.InputStream input,Duration deadline) throws IOException {
        var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        var expired=new java.util.concurrent.atomic.AtomicBoolean();
        var timeout=timer.schedule(()->{expired.set(true);try {input.close();}catch(IOException ignored){}},deadline.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            byte[] bytes=input.readNBytes(2*1024*1024+1);
            if(expired.get()) throw new IOException("GitHub body deadline exceeded");
            return bytes;
        } finally { timeout.cancel(false);timer.shutdownNow(); }
    }
    static void stripBundleContents(ObjectNode item) {
        if(item.path("evidence_bundle") instanceof ObjectNode bundle) {
            for(JsonNode file:bundle.path("files")) if(file instanceof ObjectNode object) object.remove("content");
            bundle.remove(List.of("title","body"));
        }
    }
    static EvidenceBundle collectBundle(Api api,JsonNode pr,JsonNode listed) throws InterruptedException {
        List<EvidenceBundle.File> offered=new ArrayList<>(); Set<String> expected=new HashSet<>();
        boolean complete=listed.isArray()&&listed.size()<100;
        if(pr.has("changed_files")&&pr.path("changed_files").asInt()!=listed.size()) complete=false;
        int used=0;
        for(JsonNode file:listed) {
            String path=value(file,"filename"),sha=value(file,"sha"); expected.add(path);
            if("removed".equals(value(file,"status"))||path.isBlank()||!SHA.matcher(sha).matches()) { complete=false;continue; }
            try {
                JsonNode blob=cachedBlob(api,sha);
                if(!"base64".equals(value(blob,"encoding"))||!sha.equals(value(blob,"sha"))||blob.path("truncated").asBoolean()) { complete=false;continue; }
                String encoded=value(blob,"content");
                if(encoded.length()>4L*(EvidenceBundle.MAX_EVIDENCE_BYTES-used)/3+8192) { complete=false;continue; }
                byte[] bytes=Base64.getMimeDecoder().decode(encoded);
                if(bytes.length>EvidenceBundle.MAX_EVIDENCE_BYTES-used) { complete=false;continue; }
                String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if(content.indexOf('\0')>=0||!EvidenceBundle.blob(content).equals(sha)) { complete=false;continue; }
                offered.add(new EvidenceBundle.File(path,sha,bytes.length,content)); used+=bytes.length;
            } catch(IOException|IllegalArgumentException failure) { complete=false; }
        }
        return EvidenceBundle.proposal(value(pr,"html_url"),value(pr.path("head"),"sha"),offered,expected,complete);
    }

    public static ObjectNode collect(Api api) throws IOException, InterruptedException { return collect(api,false); }
    private record FileSnapshot(String head,JsonNode files) {}
    private static final Map<Api,FileSnapshot> FILE_SNAPSHOTS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Api,Map<String,EvidenceBundle>> BUNDLES=Collections.synchronizedMap(new WeakHashMap<>());
    private record VerifiedBlob(String sha,String text) {
        JsonNode json() { return JSON.createObjectNode().put("sha",sha).put("encoding","base64").put("content",Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))); }
    }
    private static final Map<Api,Map<String,VerifiedBlob>> BLOBS=Collections.synchronizedMap(new WeakHashMap<>());
    private static JsonNode cachedBlob(Api api,String sha) throws IOException,InterruptedException {
        synchronized(BLOBS) {
            var cache=BLOBS.computeIfAbsent(api,k->new HashMap<>());
            if(cache.containsKey(sha)) return cache.get(sha).json();
            JsonNode blob=api.get(PREFIX+"/git/blobs/"+sha);
            try {
                byte[] raw=Base64.getMimeDecoder().decode(value(blob,"content"));
                String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
                if(cache.size()<64&&raw.length<=EvidenceBundle.MAX_EVIDENCE_BYTES&&text.indexOf('\0')<0
                    &&"base64".equals(value(blob,"encoding"))&&!blob.path("truncated").asBoolean()&&EvidenceBundle.blob(text).equals(sha)) cache.put(sha,new VerifiedBlob(sha,text));
            } catch(java.nio.charset.CharacterCodingException|IllegalArgumentException rejected) { /* Rejected blobs are never retained. */ }
            return blob;
        }
    }
    static ObjectNode collect(Api api,boolean llmEnabled) throws IOException, InterruptedException {
        ArrayNode inputs = JSON.createArrayNode();
        ArrayNode errors = JSON.createArrayNode();
        boolean proposalFound = false;
        for (JsonNode hit : api.get(query("repo:" + REPO + " is:pr \"Proposal: Concordia\"")).path("items")) {
            if (number(hit) != 184) continue;
            JsonNode pr = api.get(PREFIX + "/pulls/184");
            if (number(pr) != 184 || !SHA.matcher(value(pr.path("head"), "sha")).matches()) {
                errors.add("proposal PR identity not verified"); continue;
            }
            JsonNode files;
            String observedHead=value(pr.path("head"),"sha");
            synchronized(FILE_SNAPSHOTS) {
                var snapshot=FILE_SNAPSHOTS.get(api);
                if(snapshot!=null&&snapshot.head().equals(observedHead)) files=snapshot.files();
                else {
                    files=api.fresh(PREFIX+"/pulls/184/files?per_page=100");
                    FILE_SNAPSHOTS.put(api,new FileSnapshot(observedHead,files));
                }
            }
            EvidenceBundle bundle=null;
            boolean headConsistent=false;
            if(llmEnabled) {
                String identity=value(pr,"html_url")+":"+value(pr.path("head"),"sha")+":"+files.toString();
                synchronized(BUNDLES) {
                    var cache=BUNDLES.computeIfAbsent(api,k->new HashMap<>());
                    bundle=cache.get(identity);
                    headConsistent=bundle!=null;
                    if(bundle==null) {
                        bundle=collectBundle(api,pr,files);
                        JsonNode after=api.fresh(PREFIX+"/pulls/184");
                        headConsistent=value(pr.path("head"),"sha").equals(value(after.path("head"),"sha"));
                        if(!headConsistent)
                            bundle=new EvidenceBundle(bundle.url(),bundle.headSha(),bundle.files(),null,null,false);
                        else if(cache.size()<16) cache.put(identity,bundle);
                    }
                }
            }
            for (JsonNode file : files) {
                if (!"proposals/concordia.md".equals(value(file, "filename"))) continue;
                String sha = value(file, "sha");
                if (!SHA.matcher(sha).matches()) { errors.add("proposal blob SHA missing"); continue; }
                JsonNode blob = cachedBlob(api,sha);
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
                if(bundle!=null) {
                    item.set("evidence_bundle",JSON.valueToTree(bundle));
                    item.put("supplemental_valid",bundle.completeness());
                    item.put("head_consistent",headConsistent);
                    item.put("absence_eligible",Presence.eligible(item,bundle));
                }
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
            ObjectNode issueItem=item("milestone_issue", n, issue, revision, false, value(issue,"body"));
            issueItem.set("evidence_bundle",JSON.valueToTree(EvidenceBundle.issue(value(issue,"html_url"),value(issue,"title"),value(issue,"body"))));
            inputs.add(issueItem);
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
        if(!"CONTRADICTION_OR_SUPPORT".equals(clause.kind())) return ClauseCheck.evaluate(clause,text,pinned,false);
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
        return pollOnce(api, fixture, state, null);
    }

    static ObjectNode pollOnce(Api api, Fixture fixture, Set<String> state, SupplementalAssessment supplemental) throws IOException, InterruptedException {
        return pollOnce(api, fixture, state, supplemental, fixture, "pinned_demo_fixture_not_live_authority");
    }

    static ObjectNode pollOnce(Api api, Fixture fixture, Set<String> state, SupplementalAssessment supplemental,
                               Object context, String source) throws IOException, InterruptedException {
        List<Clause> activeClauses=context instanceof Reviewer.EffectiveRule effective?effective.clauses():fixture.clauses();
        String documentSha=context instanceof Reviewer.EffectiveRule effective&&effective.ruleRef()!=null?effective.ruleRef().sha256():RULE_SHA256;
        if(!(context instanceof Reviewer.EffectiveRule)) {
            try { documentSha=RuleDocument.sha(Files.readAllBytes(Path.of("../rules/rule-v"+fixture.version()+".json"))); }
            catch(Exception unavailable) { documentSha=RULE_SHA256; }
        }
        ObjectNode collected = collect(api,(supplemental!=null&&supplemental.enabled())||activeClauses.stream().anyMatch(c->"PRESENCE_REQUIRED".equals(c.kind())));
        ObjectNode result = JSON.createObjectNode().put("scope", SCOPE);
        ArrayNode reviews = result.putArray("reviews");
        result.set("errors", collected.path("errors"));
        for (JsonNode input : collected.path("inputs")) {
            String key = JSON.writeValueAsString(List.of(REPO, value(input, "kind"), number(input),
                    value(input, "revision"), value(input, "body_sha256"), "architecture-review", context instanceof Reviewer.EffectiveRule effective?effective.version():fixture.version(), documentSha));
            if (state.contains(key)) continue;
            ObjectNode review = input.deepCopy();
            String text = value(review, "_review_text");
            review.remove("_review_text");
            review.put("rule_id", "architecture-review").put("rule_version", fixture.version())
                    .put("rule_source_sha256", RULE_SHA256).put("ballot_rule_authority",AUTHORITY)
                    .put("rule_document_sha256",documentSha)
                    .put("rule_version_evaluated",context instanceof Reviewer.EffectiveRule effective?effective.version():fixture.version())
                    .put("rule_authority",context instanceof Reviewer.EffectiveRule effective?"ledger_effective_rule:"+effective.contractId()+":v"+effective.version():AUTHORITY);
            if(context instanceof Reviewer.EffectiveRule effective) review.put("rule_version",effective.version());
            ArrayNode findings = review.putArray("findings");
            if(context instanceof Reviewer.EffectiveRule effective&&effective.ruleFailure()!=null) findings.add(JSON.createObjectNode().put("clause_id","rule_document").put("status","insufficient_evidence").put("detail","rule_unverified"));
            for (Clause clause : activeClauses) {
                EvidenceBundle bundle=review.has("evidence_bundle")?JSON.treeToValue(review.get("evidence_bundle"),EvidenceBundle.class):null;
                findings.add("PRESENCE_REQUIRED".equals(clause.kind())?ClauseCheck.evaluate(clause,text,input.path("pinned").asBoolean(),bundle,Presence.eligible(review,bundle)):finding(text,input.path("pinned").asBoolean(),clause));
            }
            if (supplemental != null && context != null) supplemental.assess(review, text, context, "advisory-proposal-or-milestone", source);
            stripBundleContents(review);
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

    static ObjectNode prepareBallotSafely(JsonNode review,String proposer,String governorContractId,boolean authorized,LedgerApi ledger) throws Exception {
        try { return prepareBallot(review,proposer,governorContractId,authorized,ledger); }
        catch(RuntimeException failure) { return JSON.createObjectNode().put("kind",value(review,"kind")).put("submitted",false).put("status","blocked_invalid_review").put("reason","review_validation_failed"); }
    }

    /** Fail closed: discovery is not authority, and issue timestamps are not immutable pins. */
    public static ObjectNode prepareBallot(JsonNode review, String proposer, String governorContractId,
                                            boolean submissionAuthorized, LedgerApi ledger) throws Exception {
        String kind = value(review, "kind");
        String version = value(review, "revision");
        int number = number(review);
        String url = value(review, "url");
        // Only reviews produced by the pinned demo fixture may become command previews.
        if (!(AUTHORITY.equals(value(review,"rule_authority")) || value(review,"rule_authority").startsWith("ledger_effective_rule:") && AUTHORITY.equals(value(review,"ballot_rule_authority")))
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

    /**
     * Presentation lines for one Advisory cycle.
     *
     * <p>This agent's job in the demo is evidence, not judgement: it confirms what it loaded and
     * verified, and says nothing about whether a proposal should be approved. The confirmation is
     * only printed for an input the collector actually produced, which it does only after the Git
     * blob digest check has passed, so the sentence reports a verified fact rather than an
     * intention. Errors the collector reported are surfaced, never swallowed.
     *
     * <p>{@code pollOnce} suppresses an input it has already seen, so an unchanged cycle produces
     * no reviews and therefore no repeated lines, and a changed revision produces a new one.
     */
    static ObjectNode pollWithLedger(Api api, Fixture fixture, Set<String> state,
                                     SupplementalAssessment supplemental, Reviewer.LedgerRuleGateway ledger) throws IOException, InterruptedException {
        List<Reviewer.EffectiveRule> rules = null;
        int agendaCount = 0;
        if (ledger != null) {
            // Preserve readback failures before pollOnce can mutate polling state.
            try {
                rules = ledger.activeRules();
                agendaCount = ledger.openAgendas().size();
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException unavailable) {
                throw unavailable;
            } catch (Exception unavailable) {
                throw new IOException("ledger readback unavailable", unavailable);
            }
        }
        Object context = fixture;
        String source = "pinned_demo_fixture_not_live_authority";
        if (ledger != null) {
            context = null;
            source = "ledger_effective_rule";
            if (rules != null) {
                try { context = Reviewer.select(rules); }
                catch (Exception unavailable) { if(supplemental!=null&&supplemental.enabled()) supplemental.contextFailure(); }
            }
        }
        ObjectNode result = pollOnce(api, fixture, state, supplemental, context, source);
        if (ledger != null) {
            result.put("visible_active_rule_count", rules.size());
            result.put("visible_open_agenda_count", agendaCount);
            result.put("ledger_read_scope", "configured_party_only_textual_readback");
        }
        return result;
    }

    static void narrate(DemoLog demo, ObjectNode result) {
        for (JsonNode review : result.path("reviews")) {
            String label = DemoLog.subjectLabel(review);
            boolean pinned = review.path("pinned").asBoolean();
            String key = "advisory:" + value(review, "kind") + ":" + number(review);
            demo.observed(key, pinned
                    ? label + " loaded; pinned evidence verified."
                    : label + " loaded; no immutable revision, so its evidence is not pinned.");
        }
        // The cycle's evidence health, assessed once, from the collector's own
        // errors. Failure and recovery are both decided here and nowhere else,
        // so a healthy aspect of the cycle can never clear an unhealthy one.
        //
        // The default demo seed is PR-only and therefore legitimately reports
        // no milestone issue. That is an expected shortfall, not a fault, but
        // it is still reported accurately rather than filtered out: it prints
        // once, and stays silent while unchanged, so it neither disappears nor
        // flaps. A changed shortfall prints again, and its clearing is a
        // genuine recovery.
        // Verification failures and the routine shortfall are different facts
        // with different severities, so they are classified and tracked apart
        // using the same rule the Reviewer applies. A digest mismatch or an
        // unverified identity must never be presented as a routine "Note".
        JsonNode errors = result.path("errors");
        java.util.List<String> serious = new java.util.ArrayList<>();
        java.util.List<String> expected = new java.util.ArrayList<>();
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String text = DemoLog.trim(error.asText());
                if (text.isBlank()) continue;
                if (ReviewerNarration.isExpectedShortfall(text)) expected.add(text);
                else serious.add(text);
            }
        }
        if (!serious.isEmpty()) {
            demo.failed(DemoLog.EVIDENCE, "Evidence verification failed: "
                    + String.join("; ", serious) + ".");
            return;
        }
        demo.recovered(DemoLog.EVIDENCE, "Evidence verified again.");
        if (!expected.isEmpty())
            demo.failed(DemoLog.EVIDENCE_NOTE, "Note: " + String.join("; ", expected) + ".");
        else demo.recovered(DemoLog.EVIDENCE_NOTE, "All expected evidence is present again.");
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
        long interval = 0;
        Path statePath = null;
        SandboxBallots sandbox = null;
        Reviewer.LocalSandboxGateway readLedger = null;
        Path offlineRepository = null;
        Path offlineFixture = null;
        boolean demoMode = false, verboseFlag = AgentFlags.verboseFromEnvironment();
        for (int i = 0; i < args.length; i++) {
            if ("--interval".equals(args[i]) && ++i < args.length) interval = AgentPolling.intervalMillis(args[i]);
            else if ("--state-file".equals(args[i]) && ++i < args.length) statePath = Path.of(args[i]).toAbsolutePath();
            else if ("--offline-pr184-file".equals(args[i]) && ++i < args.length) offlineFixture = Path.of(args[i]);
            else if ("--offline-pr184-repo".equals(args[i]) && ++i < args.length) offlineRepository = Path.of(args[i]);
            // Presentation output is opt-in; without it this agent behaves exactly as before.
            else if ("--demo".equals(args[i])) demoMode = true;
            else if ("--verbose".equals(args[i]) || "--json".equals(args[i])) verboseFlag = true;
            else if ("--read-only-sandbox".equals(args[i]) && i + 3 < args.length && readLedger == null) {
                readLedger = new Reviewer.LocalSandboxGateway(Integer.parseInt(args[++i]), args[++i], args[++i]);
            }
            else if ("--local-sandbox".equals(args[i]) && i + 5 < args.length && sandbox == null) {
                sandbox = new SandboxBallots(Integer.parseInt(args[++i]), args[++i], args[++i], args[++i], args[++i]);
            } else throw new IllegalArgumentException("usage: [--interval positive-seconds] [--state-file direct-local-filename] [--offline-pr184-file local-fixture] [--offline-pr184-repo local-repository] [--local-sandbox port demo-member deployed-package-id governor-cid sandbox-user-id] [--demo] [--verbose|--json]");
        }
        final DemoLog demo = new DemoLog("Advisory", demoMode, verboseFlag, System.err);
        demo.banner();
        final long pollInterval = interval;
        final Path pollStatePath = statePath;
        if (readLedger != null && sandbox != null)
            throw new IllegalArgumentException("read-only ledger mode cannot enable submission");
        final Reviewer.LocalSandboxGateway pollReadLedger = readLedger;
        final SandboxBallots pollSandbox = sandbox;
        if (offlineRepository != null && offlineFixture != null)
            throw new IllegalArgumentException("choose one offline source");
        final Path pollOfflineRepository = offlineRepository != null ? offlineRepository : offlineFixture;
        if (statePath != null) {
            Path root = Path.of(".").toRealPath();
            if (!statePath.normalize().getParent().equals(root) || Files.isSymbolicLink(statePath))
                throw new IllegalArgumentException("state file must be a direct regular file in the Java project directory");
            if (Files.exists(statePath) && !Files.isRegularFile(statePath)) throw new IllegalArgumentException("state file is not regular");
        }
        Fixture fixture = fixture(RULE, RULE_SHA256, 2);
        Set<String> state = statePath != null && Files.exists(statePath) ? loadState(statePath) : new HashSet<>();
        Api api = offlineFixture != null ? new OfflineFile(offlineFixture)
                : offlineRepository == null ? new GitHub() : new OfflinePr184(offlineRepository);
        try (SupplementalAssessment supplemental = SupplementalAssessment.runtime("Advisory", System.err)) {
            AgentPolling.run(pollInterval, () -> {
                final SandboxBallots sandboxCycle = pollSandbox;
                try {
                    ObjectNode result = pollWithLedger(api, fixture, sandboxCycle == null ? state : new HashSet<>(), supplemental, pollReadLedger);
                    ArrayNode ballots = result.putArray(sandboxCycle == null ? "ballot_previews" : "ballot_submissions");
                    for (JsonNode review : result.path("reviews"))
                        ballots.add(prepareBallotSafely(review, sandboxCycle == null ? null : sandboxCycle.party(),
                                sandboxCycle == null ? null : sandboxCycle.governorCid(), sandboxCycle != null, sandboxCycle));
                    result.put("ledger_submission", sandboxCycle == null ? "blocked: explicit local sandbox configuration required"
                            : "demo_local_only_no_foundation_decision");
                    if (pollOfflineRepository != null) result.put("discovery", "offline_pinned_pr184_seed_not_fresh_github_poll");
                    if (sandboxCycle != null && pollOfflineRepository != null) {
                        String round = "babycdf:grant:184:f591787a772848e4537aba5d8ba02be1764647da";
                        result.put("matching_active_proposal_and_action_count", sandboxCycle.activeRoundCount(round));
                    }
                    boolean presenting = demo.enabled();
                    // narrate assesses the whole cycle's health and reports
                    // both failure and recovery itself. Announcing recovery
                    // here, before the collector's own errors were examined,
                    // cleared a failure that was still present and produced a
                    // false recovery on every poll.
                    if (presenting) narrate(demo, result);
                    if (!presenting || demo.verbose()) System.err.println(AgentPolling.advisoryLine(result));
                    if (!presenting || demo.verbose()) System.out.println(JSON.writeValueAsString(result));
                    if (pollStatePath != null) saveState(pollStatePath, state);
                } catch (IOException e) {
                    ObjectNode error = JSON.createObjectNode().put("scope", SCOPE);
                    error.putArray("reviews"); error.putArray("errors").add("poll or sandbox unavailable: " + e.getMessage());
                    // The failure is stated once in plain words; repeats of the
                    // same failure are silent, and the detail stays available.
                    if (demo.enabled()) demo.failed(DemoLog.EVIDENCE, "Waiting: " + DemoLog.summarize(e));
                    if (!demo.enabled() || demo.verbose())
                        System.err.println("[advisory] BLOCKED errors=" + error.path("errors").size()
                                + ": " + e.getMessage());
                    if (!demo.enabled() || demo.verbose()) System.out.println(JSON.writeValueAsString(error));
                    if (pollInterval == 0 || sandboxCycle != null) throw e;
                }
            }, Thread::sleep);
        } finally { if (sandbox != null) sandbox.close(); }
    }
}
