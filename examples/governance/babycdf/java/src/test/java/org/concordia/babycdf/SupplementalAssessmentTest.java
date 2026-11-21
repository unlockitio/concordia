package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class SupplementalAssessmentTest {
    static byte[] body(java.net.http.HttpRequest request) {
        var out=new ByteArrayOutputStream(); request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] bytes=new byte[b.remaining()]; b.get(bytes); out.writeBytes(bytes); }
            public void onError(Throwable t) { throw new AssertionError(t); } public void onComplete() {}
        }); return out.toByteArray();
    }
    static LlmClient.Exchange reply(int status,byte[] bytes) { return new LlmClient.Exchange() {
        public CompletableFuture<LlmClient.Reply> future() { return CompletableFuture.completedFuture(new LlmClient.Reply(status,bytes)); }
        public void cancel() {}
    }; }
    static byte[] response(String content) throws Exception {
        var response=Advisory.JSON.createObjectNode().put("model","returned-model");
        response.putArray("choices").addObject().put("finish_reason","stop").putObject("message").put("role","assistant").put("content",content);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }
    static Reviewer.EffectiveRule rule(int version) { return new Reviewer.EffectiveRule("cid","rule",version,List.of(new Advisory.Clause("c1","a","CAP evidence","b","wrong"))); }
    static com.fasterxml.jackson.databind.node.ObjectNode item() { return Advisory.JSON.createObjectNode().put("pinned",true).put("kind","PR").put("number",184); }
    static String valid(String verdict,String commentary) { return "{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\""+verdict+"\",\"rationale\":\"supported\",\"quotes\":[\"CAP evidence\"]}]"+commentary+"}"; }
    @Test void requestBoundariesBudgetAndSanitization(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","sentinel-key"));
        var calls=new AtomicInteger(); var requests=new ArrayList<byte[]>(); var log=new ByteArrayOutputStream();
        byte[] response=response(valid("FAIL",",\"commentary\":"+Advisory.JSON.writeValueAsString("sentinel-key\u001b\u202e\u2028"+"x".repeat(3000))));
        var client=new LlmClient(settings,r->{calls.incrementAndGet();requests.add(body(r));return reply(200,response);},System::nanoTime,false);
        var session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(log),root);
        var first=item(); session.assess(first,"CAP evidence",rule(2),"review","ledger_effective_rule");
        int reserved=session.reservedTokens(); assertTrue(reserved>0);
        var second=item(); session.assess(second,"CAP evidence",rule(2),"review","ledger_effective_rule");
        for(int i=0;i<10;i++) session.assess(item(),"CAP evidence",rule(2),"review","ledger_effective_rule");
        assertEquals(first,second); assertEquals(1,calls.get()); assertEquals(reserved,session.reservedTokens()); assertEquals(1,session.assessments());
        var request=Advisory.JSON.readTree(requests.getFirst());
        assertEquals(0,request.path("temperature").asInt());
        assertEquals(requests.getFirst().length+2048,reserved);
        var data=Advisory.JSON.readTree(request.path("messages").get(1).path("content").asText());
        assertEquals("ledger_effective_rule",data.path("rule_context_source").asText());
        assertEquals("CAP evidence",data.path("untrusted_public_pinned_text").asText());
        String logs=log.toString(); assertTrue(logs.contains("PR #184 rule v2: FAIL (c1=FAIL) model=returned-model cached=false"));
        assertEquals(1,logs.lines().filter(l->l.contains("LLM rule check")).count()); assertEquals(1,logs.lines().filter(l->l.contains("comment (advisory")).count()); assertTrue(logs.contains("comment (advisory, not verified)")); assertFalse(logs.contains("sentinel-key"));
        assertFalse(logs.replace("\n","").matches("(?s).*[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}].*"));
        assertTrue(first.path("supplemental_assessment").path("commentary").asText().length()<=240);
        var other=item().put("number",185);
        session.assess(other,"CAP evidence",rule(2),"review","ledger_effective_rule");
        assertEquals(1,calls.get());
        assertEquals(2,log.toString().lines().filter(l->l.contains("LLM rule check")).count());
        assertEquals(2,log.toString().lines().filter(l->l.contains("comment (advisory")).count());
        try(var files=Files.list(root)) { for(Path p:files.toList()) assertFalse(Files.readString(p).contains("sentinel-key")); }
    }
    @Test void ledgerReadbackFailuresPrecedeEvidenceStateAndProviderCalls() throws Exception {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")); var calls=new AtomicInteger();
        var session=new SupplementalAssessment("Advisory",settings,new LlmClient(settings,r->{calls.incrementAndGet();return reply(500,new byte[0]);},System::nanoTime,false),null,System.out);
        var fixture=new Advisory.Fixture(7,rule(7).clauses()); var state=new HashSet<>(Set.of("existing"));
        Reviewer.LedgerRuleGateway ledger=()->{throw new IOException("unavailable");};
        assertThrows(IOException.class,()->Advisory.pollWithLedger(path->{throw new AssertionError();},fixture,state,session,ledger));
        assertEquals(Set.of("existing"),state); assertEquals(0,calls.get());
    }
    @Test void enabledReadOnlyLedgerContextIsExplicitAndReadOnce() throws Exception {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")); var reads=new AtomicInteger(); var calls=new AtomicInteger();
        var session=new SupplementalAssessment("Advisory",settings,new LlmClient(settings,r->{calls.incrementAndGet();return reply(500,new byte[0]);},System::nanoTime,false),null,System.out);
        Advisory.pollWithLedger(new AdvisoryTest().api(),new Advisory.Fixture(7,rule(7).clauses()),new HashSet<>(),session,()->{reads.incrementAndGet();return List.of(new Reviewer.EffectiveRule("cid","architecture-review",7,List.of(new Advisory.Clause("C","a","CAP evidence","b","wrong"))));});
        assertEquals(1,reads.get()); assertEquals(2,calls.get());
    }
    @Test void escapedRetryInputActuallyExhaustsTokenReservationBeforeAttemptCap() {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")); var calls=new AtomicInteger();
        var session=new SupplementalAssessment("Reviewer",settings,new LlmClient(settings,r->{calls.incrementAndGet();return reply(500,new byte[0]);},System::nanoTime,true),null,System.out);
        var item=item(); session.assess(item,"\u0001".repeat(EvidenceBundle.MAX_EVIDENCE_BYTES+1),new Advisory.Fixture(1,List.of(new Advisory.Clause("c1","a","x".repeat(10000),"b","z"))),"review");
        assertEquals(0,calls.get()); assertEquals(0,session.reservedTokens());
        assertEquals("INCONCLUSIVE",item.path("supplemental_assessment").path("exact").path("overall").asText());
    }
    @Test void retryReservationsAndStableFailureDedup() {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")); var calls=new AtomicInteger();
        var session=new SupplementalAssessment("Reviewer",settings,new LlmClient(settings,r->{calls.incrementAndGet();return reply(500,new byte[0]);},System::nanoTime,true),null,System.out);
        var first=item(); session.assess(first,"CAP evidence",rule(1),"review"); int reserved=session.reservedTokens();
        for(int i=0;i<9;i++) session.assess(item(),"CAP evidence",rule(1),"review");
        assertEquals(2,calls.get()); assertEquals(reserved,session.reservedTokens());
    }
    @Test void disabledEmptyAndAmbiguousLedgerNeverChangesDeterministicResults() throws Exception {
        var evidence=new AdvisoryTest(); var fixture=new Advisory.Fixture(7,rule(7).clauses()); var settings=LlmSettings.fromEnvironment(Map.of());
        var disabled=new SupplementalAssessment("Advisory",settings,null,"missing_api_key",System.out);
        var original=Advisory.pollOnce(evidence.api(),fixture,new HashSet<>());
        var actual=Advisory.pollWithLedger(evidence.api(),fixture,new HashSet<>(),disabled,()->List.of());
        assertEquals(original.path("reviews"),actual.path("reviews"));
        var enabled=new SupplementalAssessment("Advisory",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")),null,null,System.out);
        assertEquals(2,Advisory.pollWithLedger(evidence.api(),fixture,new HashSet<>(),enabled,()->List.of()).path("reviews").size());
    }
    @Test void cacheFailureIsExplicit(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path file=root.resolve("file"); Files.writeString(file,"not a directory");
        var session=new SupplementalAssessment("Reviewer",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","key")),null,null,System.out,file);
        var item=item(); session.assess(item,"CAP evidence",rule(1),"review");
        assertEquals("INCONCLUSIVE",item.path("supplemental_assessment").path("exact").path("overall").asText());
        assertEquals("cache_unavailable",item.path("supplemental_assessment").path("exact").path("reason").asText());
    }
}
