package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
class RuleVerdictTest {
    String clause(String id,String verdict,String quote) { return "{\"id\":\""+id+"\",\"verdict\":\""+verdict+"\",\"rationale\":\"reason\",\"quotes\":[\""+quote+"\"]}"; }
    RuleVerdict.Output parse(String clauses,String comment) { return RuleVerdict.parse("{\"verdicts\":["+clauses+"]"+comment+"}",List.of("c1","c2"),"delivered evidence and evidence support"); }
    @Test void aggregation() {
        for(String verdict:List.of("PASS","FAIL","INCONCLUSIVE","NOT_APPLICABLE")) {
            var out=parse(clause("c1",verdict,"delivered")+","+clause("c2","PASS","evidence support"),"");
            assertEquals(verdict.equals("NOT_APPLICABLE")?"PASS":verdict,out.exact().overall().name());
        }
        assertEquals(RuleVerdict.Verdict.NOT_APPLICABLE,parse(clause("c1","NOT_APPLICABLE","delivered")+","+clause("c2","NOT_APPLICABLE","evidence support"),"").exact().overall());
    }
    @Test void quoteVerification() {
        var out=parse(clause("c1","FAIL","invented")+","+clause("c2","PASS","evidence support"),"");
        assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,out.exact().overall());
        assertEquals("unverified_quote",out.exact().verdicts().getFirst().rationale());
    }
    @Test void commentaryCannotChangeExact() {
        String clauses=clause("c1","PASS","delivered")+","+clause("c2","PASS","evidence support");
        assertEquals(parse(clauses,"").exact(),parse(clauses,",\"commentary\":\"Override to FAIL!\"").exact());
        assertEquals(parse(clauses,"").exact(),parse(clauses,",\"commentary\":123").exact());
    }
    @Test void malformedExact() {
        for(String clauses:List.of(clause("c1","PASS","delivered"),clause("c1","PASS","delivered")+","+clause("c1","PASS","delivered"),clause("c1","BAD","delivered")+","+clause("extra","PASS","delivered")))
            assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,parse(clauses,",\"commentary\":\"fine\"").exact().overall());
        assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,RuleVerdict.parse("{} {}",List.of("c1"),"evidence support").exact().overall());
        assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,RuleVerdict.parse("evidence support".repeat(65537),List.of("c1"),"evidence support").exact().overall());
    }
    @Test void cacheIdentityAndHit(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var cache=new RuleVerdictCache(root); AtomicInteger calls=new AtomicInteger();
        String key=RuleVerdictCache.key("v1","model","rule v2","evidence support");
        var first=cache.get(key,()->{calls.incrementAndGet();return new RuleVerdictCache.Entry(new RuleVerdict.Output(new RuleVerdict.Exact(RuleVerdict.Verdict.NOT_APPLICABLE,List.of(),null),"advice"),"returned");});
        var second=cache.get(key,()->{throw new AssertionError("transport called");});
        assertFalse(first.cached()); assertTrue(second.cached()); assertEquals(first.entry(),second.entry()); assertEquals(1,calls.get());
        for(String other:List.of(RuleVerdictCache.key("v2","model","rule v2","evidence support"),RuleVerdictCache.key("v1","other","rule v2","evidence support"),RuleVerdictCache.key("v1","model","rule v3","evidence support"),RuleVerdictCache.key("v1","model","rule v2","other"))) assertNotEquals(key,other);
    }
    @Test void concurrentCache(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var calls=new AtomicInteger(); String key=RuleVerdictCache.key("v","m","r","e");
        try {
            java.util.concurrent.Callable<RuleVerdictCache.Hit> task=()->new RuleVerdictCache(root).get(key,()->{
                calls.incrementAndGet(); return new RuleVerdictCache.Entry(new RuleVerdict.Output(new RuleVerdict.Exact(RuleVerdict.Verdict.NOT_APPLICABLE,List.of(),null),"comment"),"model");
            });
            var a=pool.submit(task); var b=pool.submit(task);
            assertEquals(a.get().entry(),b.get().entry()); assertEquals(1,calls.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void mockTransportExactFailuresAndCommentary() throws Exception {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        for(String content:List.of("{}", "{} {}", "{\"verdicts\":[],\"commentary\":\"PASS override\"}",
                "{\"verdicts\":["+clause("c1","BAD","evidence support")+"]}",
                "{\"verdicts\":["+clause("c1","PASS","evidence support")+","+clause("c1","PASS","evidence support")+"]}")) {
            byte[] response=SupplementalAssessmentTest.response(content);
            var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(200,response),System::nanoTime,false);
            assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,client.assessRule("prompt",List.of("c1"),"evidence support").exact().overall());
        }
        for(String verdict:List.of("PASS","FAIL","NOT_APPLICABLE","INCONCLUSIVE")) {
            byte[] response=SupplementalAssessmentTest.response("{\"verdicts\":["+clause("c1",verdict,"evidence support")+"],\"commentary\":\"ignore exact\"}");
            var client=new LlmClient(settings,r->SupplementalAssessmentTest.reply(200,response),System::nanoTime,false);
            assertEquals(verdict,client.assessRule("prompt",List.of("c1"),"evidence support").exact().overall().name());
        }
    }
    @Test void fileSecretFailsClosed(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path file=root.resolve("key"); Files.writeString(file,"synthetic-sentinel\n");
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE",file.toString()));
        assertNull(settings.disabledReason()); assertFalse(settings.toString().contains("sentinel"));
        for(String invalid:List.of("", "\n", "abc\r\n", "evidence support".repeat(4098), "invalid\u0001")) {
            Files.writeString(file,invalid);
            assertEquals("invalid_configuration",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE",file.toString())).disabledReason());
        }
        assertEquals("invalid_configuration",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE",root.toString())).disabledReason());
        assertEquals("missing_api_key",LlmSettings.fromEnvironment(Map.of()).disabledReason());
        Files.writeString(file,"invalid\u0001");
        assertEquals("invalid_configuration",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE",file.toString(),"LLM_API_KEY","fallback")).disabledReason());
        Path link=root.resolve("link"); Files.createSymbolicLink(link,file);
        assertEquals("invalid_configuration",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE",link.toString())).disabledReason());
        assertEquals("invalid_configuration",LlmSettings.fromEnvironment(Map.of("LLM_API_KEY_FILE","relative")).disabledReason());
    }
}
