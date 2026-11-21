package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import static org.concordia.babycdf.DiscoveryReport.*;

/** Bounded read-only GitHub inventory. Does not implement the curated planning catalogue. */
public final class GitHubDiscovery {
    private static final String ROOT = "https://api.github.com/repos/canton-foundation/canton-dev-fund/";
    private static final Pattern MILESTONE = Pattern.compile("^.+ #\\d+ - Milestone \\d+: .+$");
    private final Transport transport;
    private final Limits limits;
    private final Clock clock;
    private final LongSupplier nanoTime;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(256 * 1024).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Limits(int pagesPerEndpoint, int requests, Duration totalTime, Duration requestTime) {
        public Limits {
            if (pagesPerEndpoint < 1 || requests < 1 || totalTime == null || requestTime == null
                    || totalTime.isNegative() || totalTime.isZero() || requestTime.isNegative()
                    || requestTime.isZero() || totalTime.compareTo(Duration.ofHours(1)) > 0
                    || requestTime.compareTo(totalTime) > 0)
                throw new IllegalArgumentException("positive bounded discovery limits required");
        }
        public static Limits defaults() { return new Limits(20, 40, Duration.ofMinutes(2), Duration.ofSeconds(10)); }
    }
    public record Response(int status, List<String> links, InputStream body, Map<String,List<String>> headers) implements AutoCloseable {
        public Response {
            links = List.copyOf(links); Objects.requireNonNull(body);
            // Retain only explicitly safe diagnostic metadata, never arbitrary headers.
            Map<String,List<String>> safe=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.forEach((name,values)->{
                if(Set.of("x-ratelimit-remaining","x-ratelimit-reset","retry-after","x-github-request-id").contains(name.toLowerCase(Locale.ROOT)))
                    safe.put(name.toLowerCase(Locale.ROOT),List.copyOf(values));
            });
            headers=Collections.unmodifiableMap(safe);
        }
        public Response(int status,List<String> links,InputStream body) {this(status,links,body,Map.of());}
        /** Convenience for deterministic fixtures; production always provides a streaming body. */
        public Response(int status, List<String> links, String body) {
            this(status, links, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        @Override public void close() throws IOException { body.close(); }
    }
    /** Implementations must honor timeout and must not follow redirects. */
    @FunctionalInterface public interface Transport {
        Response get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }
    /** Anonymous production transport: no environment credentials, redirects, or ledger access. */
    public static GitHubDiscovery anonymous() { return anonymous(Limits.defaults()); }
    public static GitHubDiscovery anonymous(Limits limits) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        return new GitHubDiscovery((uri, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
            HttpResponse<InputStream> r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Response(r.statusCode(), r.headers().allValues("Link"), r.body(), r.headers().map());
        }, limits, Clock.systemUTC(), System::nanoTime);
    }
    public GitHubDiscovery(Transport transport, Limits limits, Clock clock, LongSupplier nanoTime) {
        this.transport = Objects.requireNonNull(transport);
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }
    public record SourceReport(Instant observedAt, List<SourceObservation> observations, DiscoveryReport inventory) {
        public SourceReport { observations=List.copyOf(observations); }
        public boolean complete() { return inventory.complete(); }
    }
    /** Strict actual-field discovery; invalid pages remain explicitly partial. No synthetic field defaults. */
    public SourceReport discoverSources() {
        long start=nanoTime.getAsLong();
        int[] remaining={limits.requests()};
        Instant receipt=clock.instant();
        Map<Long,SourceObservation> observations=new LinkedHashMap<>();
        Inventory pulls=scan("pulls",start,remaining,observations,receipt);
        Inventory issues=scan("issues",start,remaining,observations,receipt);
        return new SourceReport(receipt,new ArrayList<>(observations.values()),new DiscoveryReport(receipt,pulls,issues));
    }
    public DiscoveryReport discover() {
        long start = nanoTime.getAsLong();
        int[] remaining = {limits.requests()};
        Inventory pulls = scan("pulls", start, remaining);
        Inventory issues = scan("issues", start, remaining);
        return new DiscoveryReport(clock.instant(), pulls, issues);
    }
    private Inventory scan(String endpoint, long start, int[] remaining) {
        return scan(endpoint,start,remaining,null,null);
    }
    private Inventory scan(String endpoint, long start, int[] remaining,
                           Map<Long,SourceObservation> observations,Instant receipt) {
        Map<Long, Item> items = new LinkedHashMap<>();
        Set<Long> milestones = new LinkedHashSet<>();
        Set<URI> visited = new HashSet<>();
        URI uri = URI.create(ROOT + endpoint + "?state=all&per_page=100");
        int requests = 0;
        StopReason stop = StopReason.COMPLETE;
        while (uri != null) {
            long left = limits.totalTime().toNanos() - (nanoTime.getAsLong() - start);
            if (left <= 0) { stop = StopReason.TIME_LIMIT; break; }
            if (requests >= limits.pagesPerEndpoint()) { stop = StopReason.PAGE_LIMIT; break; }
            if (remaining[0] == 0) { stop = StopReason.REQUEST_LIMIT; break; }
            if (!safe(uri, endpoint)) { stop = StopReason.UNSAFE_LINK; break; }
            if (!visited.add(uri)) { stop = StopReason.CYCLE; break; }
            requests++; remaining[0]--;
            long requestStart = nanoTime.getAsLong();
            try {
                Response r;
                try {
                    r = transport.get(uri, Duration.ofNanos(Math.min(left, limits.requestTime().toNanos())));
                } catch (IOException e) { stop = StopReason.TRANSPORT_ERROR; break; }
                try (r) {
                if (expired(start, requestStart)) {
                    stop = StopReason.TIME_LIMIT; break;
                }
                if (r.status() != 200) {
                    stop = r.status() == 403 ? StopReason.FORBIDDEN : r.status() == 429
                            ? StopReason.RATE_LIMITED : StopReason.HTTP_ERROR;
                    break;
                }
                byte[] bytes;
                // ofInputStream completes on headers; explicitly close a stalled body at the
                // remaining request/aggregate deadline as HttpRequest.timeout alone is insufficient.
                long now = nanoTime.getAsLong();
                long readBudget = Math.min(limits.requestTime().toNanos() - (now - requestStart),
                        limits.totalTime().toNanos() - (now - start));
                if (readBudget <= 0) { stop = StopReason.TIME_LIMIT; break; }
                java.util.concurrent.atomic.AtomicBoolean readExpired = new java.util.concurrent.atomic.AtomicBoolean();
                try (var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
                    var expiry = timer.schedule(() -> {
                        readExpired.set(true);
                        try { r.close(); } catch (IOException ignored) { }
                    }, Math.max(1, readBudget), java.util.concurrent.TimeUnit.NANOSECONDS);
                    try { bytes = r.body().readNBytes(MAX_RESPONSE_BYTES + 1); }
                    catch (IOException e) {
                        if (readExpired.get()) { stop = StopReason.TIME_LIMIT; break; }
                        throw e;
                    } finally { expiry.cancel(false); }
                }
                if (readExpired.get()) { stop = StopReason.TIME_LIMIT; break; }
                if (expired(start, requestStart)) {
                    stop = StopReason.TIME_LIMIT; break;
                }
                if (bytes.length > MAX_RESPONSE_BYTES) { stop = StopReason.RESPONSE_LIMIT; break; }
                JsonNode rows = mapper.readTree(bytes);
                if (expired(start, requestStart)) {
                    stop = StopReason.TIME_LIMIT; break;
                }
                if (rows == null || !rows.isArray() || rows.size() > 100) {
                    stop = StopReason.INVALID_RESPONSE; break;
                }
                // Validate the whole page before adding it; never count malformed records as success.
                Map<Long, Item> page = new LinkedHashMap<>();
                Map<Long,SourceObservation> sourcePage=new LinkedHashMap<>();
                for (JsonNode row : rows) {
                    JsonNode number = row.path("number"), title = row.path("title");
                    if (!row.isObject() || !number.isIntegralNumber() || !number.canConvertToLong()
                            || number.longValue() <= 0 || !title.isTextual())
                        throw new IOException("invalid inventory record");
                    if (endpoint.equals("issues") && row.has("pull_request")) continue;
                    if(observations!=null) {
                        SourceObservation observation=SourceObservation.parse(row,endpoint.equals("pulls"),clock.instant());
                        if(observation!=null) {
                            SourceObservation duplicate=sourcePage.putIfAbsent(observation.number(),observation);
                            if(duplicate!=null && !duplicate.fingerprint().equals(observation.fingerprint()))
                                throw new IOException("conflicting duplicate source record");
                        }
                    }
                    page.putIfAbsent(number.longValue(), new Item(number.longValue(), title.textValue()));
                }
                if (expired(start, requestStart)) {
                    stop = StopReason.TIME_LIMIT; break;
                }
                if(observations!=null) {
                    for(var observation:sourcePage.values()) {
                        var old=observations.get(observation.number());
                        if(old!=null && (old.kind()!=observation.kind()
                                || !old.fingerprint().equals(observation.fingerprint())))
                            throw new IOException("conflicting source record across pages");
                    }
                    sourcePage.forEach(observations::putIfAbsent);
                }
                page.forEach(items::putIfAbsent);
                if (endpoint.equals("issues")) for (Item item : page.values())
                    if (MILESTONE.matcher(item.title()).matches()) milestones.add(item.number());
                uri = next(r.links(), uri, endpoint);
                }
            } catch (UnsafeLink e) { stop = StopReason.UNSAFE_LINK; break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); stop = StopReason.INTERRUPTED; break;
            } catch (IOException e) { stop = StopReason.INVALID_RESPONSE; break;
            } catch (RuntimeException e) { stop = StopReason.INVALID_RESPONSE; break; }
        }
        return new Inventory(new LinkedHashSet<>(items.values()), milestones, requests, stop);
    }
    private boolean expired(long aggregateStart, long requestStart) {
        long now = nanoTime.getAsLong();
        return now - aggregateStart >= limits.totalTime().toNanos()
                || now - requestStart >= limits.requestTime().toNanos();
    }
    private static boolean safe(URI uri, String endpoint) {
        if (!"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || !URI.create(ROOT + endpoint).getRawPath().equals(uri.getRawPath())) return false;
        String q = uri.getRawQuery();
        if (q == null) return false;
        Map<String, String> values = new HashMap<>();
        for (String part : q.split("&", -1)) {
            String[] kv = part.split("=", -1);
            if (kv.length != 2 || values.putIfAbsent(kv[0], kv[1]) != null) return false;
        }
        return "all".equals(values.get("state")) && "100".equals(values.get("per_page"))
                && values.keySet().stream().allMatch(k -> Set.of("state", "per_page", "page").contains(k))
                && (!values.containsKey("page") || values.get("page").matches("[1-9][0-9]{0,8}"));
    }
    private static URI next(List<String> headers, URI current, String endpoint) throws UnsafeLink {
        URI next = null;
        for (String header : headers) for (String part : header.split(",")) {
            String[] sections = part.trim().split(";", -1);
            if (sections.length < 2) throw new UnsafeLink();
            boolean isNext = false;
            for (int i = 1; i < sections.length; i++) {
                String s = sections[i].trim();
                if (s.startsWith("rel=")) {
                    String rel = s.substring(4).replace("\"", "");
                    isNext |= Arrays.asList(rel.split(" +")).contains("next");
                }
            }
            if (!isNext) continue;
            String target = sections[0].trim();
            if (!target.startsWith("<") || !target.endsWith(">") || next != null) throw new UnsafeLink();
            try { next = current.resolve(target.substring(1, target.length() - 1)); }
            catch (IllegalArgumentException e) { throw new UnsafeLink(); }
            if (!safe(next, endpoint)) throw new UnsafeLink();
        }
        return next;
    }
    private static final class UnsafeLink extends Exception { }
}
