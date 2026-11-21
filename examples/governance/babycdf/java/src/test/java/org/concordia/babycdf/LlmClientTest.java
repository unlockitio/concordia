package org.concordia.babycdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static LlmSettings settings() { return LlmSettings.fromEnvironment(Map.of("LLM_API_KEY", "test-sentinel-key")); }
    static byte[] response(String content) throws Exception {
        return JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("role", "assistant", "content", content)))));
    }
    static String assessment() { return "{\"verdict\":\"CONCERN\",\"summary\":\"evidence summary\",\"risks\":[\"budget\"]}"; }
    static LlmClient.Exchange exchange(int status, byte[] body) {
        var future = CompletableFuture.completedFuture(new LlmClient.Reply(status, body));
        return new LlmClient.Exchange() {
            public CompletableFuture<LlmClient.Reply> future() { return future; }
            public void cancel() { future.cancel(true); }
        };
    }
    static LlmClient client(byte[] bytes) { return new LlmClient(settings(), r -> exchange(200, bytes), () -> 0, false); }
    @Test void missingKeyMakesNoRequest() {
        var c = new LlmClient(LlmSettings.fromEnvironment(Map.of()), r -> { fail("no call"); return null; }, () -> 0, false);
        var result = assertInstanceOf(LlmClient.Failed.class, c.assess("evidence"));
        assertEquals(LlmClient.Failure.DISABLED, result.reason()); assertEquals(0, result.metadata().calls());
    }
    @Test void requestShapeAndMissingUsage() throws Exception {
        var c = new LlmClient(settings(), request -> {
            assertEquals("POST", request.method());
            assertEquals("Bearer test-sentinel-key", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(60, request.timeout().orElseThrow().toSeconds());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            var bytes = new java.io.ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer b) { byte[] data = new byte[b.remaining()]; b.get(data); bytes.writeBytes(data); }
                public void onError(Throwable t) { fail(t); }
                public void onComplete() {}
            });
            try {
                var root = JSON.readTree(bytes.toByteArray());
                assertEquals(LlmSettings.DEFAULT_MODEL, root.path("model").asText());
                assertEquals(1024, root.path("max_tokens").asInt());
                assertEquals("json_object", root.path("response_format").path("type").asText());
                assertEquals(2, root.path("messages").size());
                return exchange(200, response(assessment()));
            } catch (Exception e) { throw new AssertionError(e); }
        }, () -> 0, false);
        var result = assertInstanceOf(LlmClient.Success.class, c.assess("evidence"));
        assertEquals(LlmClient.Verdict.CONCERN, result.assessment().verdict());
        assertNull(result.metadata().usage()); assertNull(result.metadata().returnedModel());
        assertNotNull(result.metadata().correlationId());
    }
    @Test void capturesActualModelAndUsage() throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(response(assessment()));
        root.put("model", "actual-provider-model");
        root.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 2).put("total_tokens", 12);
        var result = assertInstanceOf(LlmClient.Success.class, client(JSON.writeValueAsBytes(root)).assess("evidence"));
        assertEquals("actual-provider-model", result.metadata().returnedModel());
        assertEquals(12, result.metadata().usage().totalTokens());
    }
    @Test void malformedTrailingAndWrongSchemaFailClosed() throws Exception {
        for (byte[] bytes : List.of("not json".getBytes(StandardCharsets.UTF_8), "{} {}".getBytes(StandardCharsets.UTF_8),
                response(assessment() + " {}"), response("{}"), response("{\"verdict\":\"YES\",\"summary\":\"x\",\"risks\":[]}"),
                response("{\"verdict\":\"SUPPORT\",\"summary\":\"x\",\"risks\":[],\"extra\":true}"))) {
            assertEquals(LlmClient.Failure.INVALID_RESPONSE, assertInstanceOf(LlmClient.Failed.class, client(bytes).assess("evidence")).reason());
        }
    }
    @Test void unknownProviderAndIncompleteFinishFailClosed() throws Exception {
        for (String raw : List.of("{}", "{\"error\":{}}", "{\"choices\":[]}",
                new String(response(assessment()), StandardCharsets.UTF_8).replace("stop", "length"))) {
            assertInstanceOf(LlmClient.Failed.class, client(raw.getBytes(StandardCharsets.UTF_8)).assess("evidence"));
        }
    }
    @Test void retriesHaveHardCallCap() {
        AtomicInteger calls = new AtomicInteger();
        var c = new LlmClient(settings(), r -> { calls.incrementAndGet(); return exchange(429, new byte[0]); }, () -> 0, true);
        assertEquals(LlmClient.Failure.HTTP_STATUS, assertInstanceOf(LlmClient.Failed.class, c.assess("evidence")).reason());
        assertEquals(2, calls.get());
    }
    @Test void defaultDoesNotRetryOrFollowRedirect() {
        AtomicInteger calls = new AtomicInteger();
        var c = new LlmClient(settings(), r -> { calls.incrementAndGet(); return exchange(302, new byte[0]); }, () -> 0, false);
        assertInstanceOf(LlmClient.Failed.class, c.assess("evidence")); assertEquals(1, calls.get());
    }
    @Test void deadlineIncludesTransportBodyAndPreventsRetry() {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger(); AtomicBoolean canceled = new AtomicBoolean();
        var c = new LlmClient(settings(), r -> {
            calls.incrementAndGet(); clock.set(LlmClient.BUDGET + 1);
            return new LlmClient.Exchange() {
                public CompletableFuture<LlmClient.Reply> future() { return CompletableFuture.completedFuture(new LlmClient.Reply(503, new byte[0])); }
                public void cancel() { canceled.set(true); }
            };
        }, clock::get, true);
        assertEquals(LlmClient.Failure.TIMEOUT, assertInstanceOf(LlmClient.Failed.class, c.assess("evidence")).reason());
        assertEquals(1, calls.get()); assertTrue(canceled.get());
    }
    @Test void bodyCapEnforcedBeforeCopyAndCancelBeforeSubscribe() {
        var body = new LlmClient.BoundedBody(); AtomicBoolean canceled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { canceled.set(true); } });
        body.onNext(List.of(ByteBuffer.allocate(LlmClient.MAX_BODY + 1)));
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally()); assertTrue(canceled.get());
        var early = new LlmClient.BoundedBody(); early.cancel(); canceled.set(false);
        early.onSubscribe(new Flow.Subscription() { public void request(long n) { fail(); } public void cancel() { canceled.set(true); } });
        assertTrue(canceled.get());
    }
    @Test void oversizedAndDeepBodiesFail() throws Exception {
        assertEquals(LlmClient.Failure.BODY_LIMIT, assertInstanceOf(LlmClient.Failed.class, client(new byte[LlmClient.MAX_BODY + 1]).assess("evidence")).reason());
        assertInstanceOf(LlmClient.Failed.class, client(("[".repeat(25) + "0" + "]".repeat(25)).getBytes(StandardCharsets.UTF_8)).assess("evidence"));
    }
    @Test void asynchronousHttpTimeoutsAreTypedAndCanceled() {
        for (Exception timeout : List.of(new java.net.http.HttpTimeoutException("test-sentinel-key"),
                new java.net.http.HttpConnectTimeoutException("private-prompt"))) {
            AtomicBoolean canceled = new AtomicBoolean();
            var c = new LlmClient(settings(), r -> new LlmClient.Exchange() {
                final CompletableFuture<LlmClient.Reply> pending = CompletableFuture.failedFuture(
                        new CompletionException(timeout));
                public CompletableFuture<LlmClient.Reply> future() { return pending; }
                public void cancel() { canceled.set(true); }
            }, () -> 0, false);
            var result = assertInstanceOf(LlmClient.Failed.class, c.assess("private-prompt"));
            assertEquals(LlmClient.Failure.TIMEOUT, result.reason());
            assertEquals(1, result.metadata().calls());
            assertTrue(canceled.get());
            assertFalse(result.toString().contains("test-sentinel-key"));
            assertFalse(result.toString().contains("private-prompt"));
        }
    }
    @Test void asynchronousNonTimeoutIsTransportEvenForIllegalArgument() {
        var c = new LlmClient(settings(), r -> new LlmClient.Exchange() {
            public CompletableFuture<LlmClient.Reply> future() {
                return CompletableFuture.failedFuture(new CompletionException(new IllegalArgumentException("secret")));
            }
            public void cancel() {}
        }, () -> 0, false);
        assertEquals(LlmClient.Failure.TRANSPORT,
                assertInstanceOf(LlmClient.Failed.class, c.assess("evidence")).reason());
    }
    @Test void diagnosticFormsDoNotExposePromptKeyOrError() {
        var c = new LlmClient(settings(), r -> { throw new IllegalArgumentException("test-sentinel-key private-prompt"); }, () -> 0, false);
        var result = assertInstanceOf(LlmClient.Failed.class, c.assess("private-prompt"));
        assertEquals(LlmClient.Failure.TRANSPORT, result.reason());
        assertEquals(1, result.metadata().calls());
        String diagnostic = result.toString();
        assertFalse(diagnostic.contains("test-sentinel-key")); assertFalse(diagnostic.contains("private-prompt"));
    }
}
