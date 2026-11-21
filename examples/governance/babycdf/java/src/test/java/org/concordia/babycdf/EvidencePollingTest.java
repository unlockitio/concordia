package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.io.*;
import java.time.Duration;
class EvidencePollingTest {
    static class Api implements Advisory.Api {
        String text="CAP evidence support"; int blobs,fresh; boolean change;
        public com.fasterxml.jackson.databind.JsonNode fresh(String path) throws IOException { if(path.endsWith("/pulls/184"))fresh++;var p=get(path);if(change&&path.endsWith("/pulls/184"))((com.fasterxml.jackson.databind.node.ObjectNode)p.path("head")).put("sha","b".repeat(40));return p; }
        public com.fasterxml.jackson.databind.JsonNode get(String path) throws IOException {
            String sha=EvidenceBundle.blob(text);
            if(path.startsWith("search/issues?")) return Advisory.JSON.readTree(path.contains("is%3Apr")?"{\"items\":[{\"number\":184}]}":"{\"items\":[]}");
            if(path.endsWith("/pulls/184")) {var p=Advisory.JSON.createObjectNode().put("number",184).put("html_url","https://github.com/canton-foundation/canton-dev-fund/pull/184");p.putObject("head").put("sha","a".repeat(40));return p;}
            if(path.contains("/files?")) {var files=Advisory.JSON.createArrayNode();files.addObject().put("filename","proposals/concordia.md").put("sha",sha);return files;}
            if(path.contains("/git/blobs/")) {blobs++;return Advisory.JSON.createObjectNode().put("sha",sha).put("encoding","base64").put("content",Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            throw new IOException("unexpected");
        }
    }
    @Test void repeatedPollsReuseBlobsAndBundles() throws Exception {
        var disabled=new Api(); Advisory.collect(disabled,false);int first=disabled.blobs;
        for(int i=0;i<5;i++) Advisory.collect(disabled,false);
        assertEquals(1,first);assertEquals(first,disabled.blobs);assertEquals(0,disabled.fresh);
        var enabled=new Api();Advisory.collect(enabled,true);assertEquals(1,enabled.blobs);assertEquals(1,enabled.fresh);
        for(int i=0;i<5;i++) Advisory.collect(enabled,true);
        assertEquals(1,enabled.blobs);assertEquals(1,enabled.fresh);
    }
    @Test void changedHeadInvalidatesSupplementalOnly() throws Exception {
        var api=new Api();api.change=true;var item=Advisory.collect(api,true).path("inputs").get(0);
        assertTrue(item.path("pinned").asBoolean());assertFalse(item.path("supplemental_valid").asBoolean());assertFalse(item.path("evidence_bundle").path("completeness").asBoolean());
    }
    @Test void tamperedBundleBlocksProvider() throws Exception {
        var item=SupplementalAssessmentTest.item().put("kind","proposal_pr").put("head_commit","a".repeat(40));
        var file=new EvidenceBundle.File("f",EvidenceBundle.blob("original evidence"),17,"tampered evidence");
        item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(file),null,null,true)));
        Reviewer.verifyBundle(item);
        assertTrue(item.path("pinned").asBoolean());assertFalse(item.path("supplemental_valid").asBoolean());assertEquals("bundle_verification_failed",item.path("evidence_warning").asText());
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        var client=new LlmClient(settings,r->{throw new AssertionError("provider called");},System::nanoTime,false);
        new SupplementalAssessment("Reviewer",settings,client,null,System.out).assess(item,"tampered evidence",SupplementalAssessmentTest.rule(1),"review");
    }
    @Test void forgedCrossFileCacheIsMiss(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var a=new EvidenceBundle.File("a",EvidenceBundle.blob("first evidence"),14,"first evidence");
        var b=new EvidenceBundle.File("b",EvidenceBundle.blob("second evidence"),15,"second evidence");
        var bundle=new EvidenceBundle("url","a".repeat(40),List.of(a,b),null,null,true);
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        var session=new SupplementalAssessment("Reviewer",settings,null,null,System.out);
        var exact=new RuleVerdict.Exact(RuleVerdict.Verdict.PASS,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.PASS,"ok",List.of("evidence second"))),null);
        var forged=new RuleVerdictCache.Entry(new RuleVerdict.Output(exact,""),"model");
        String key=RuleVerdictCache.key("v","m","r","e");
        java.nio.file.Files.write(root.resolve(key+".json"),RuleVerdict.JSON.writeValueAsBytes(forged));
        var called=new java.util.concurrent.atomic.AtomicInteger();
        String context=Advisory.JSON.writeValueAsString(SupplementalAssessmentTest.rule(1));
        var hit=new RuleVerdictCache(root).get(key,e->session.trusted(e,context,bundle.evidenceText(),bundle),()->{
            called.incrementAndGet();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("test"),""),"unknown");
        });
        assertFalse(hit.cached());assertEquals(1,called.get());
    }
    @Test void nearCapEscapedReservation() throws Exception {
        String evidence="\\\"".repeat(100000); // 200000 evidence bytes; escaping expands the actual request.
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        var captured=new java.util.ArrayList<byte[]>();
        byte[] response=SupplementalAssessmentTest.response("{\"verdicts\":[{\"id\":\"c1\",\"verdict\":\"INCONCLUSIVE\",\"rationale\":\"unknown\",\"quotes\":[]}]}");
        var client=new LlmClient(settings,r->{captured.add(SupplementalAssessmentTest.body(r));return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
        var session=new SupplementalAssessment("Reviewer",settings,client,null,new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        session.assess(SupplementalAssessmentTest.item(),evidence,SupplementalAssessmentTest.rule(1),"review");
        assertEquals(1,captured.size());assertEquals(captured.getFirst().length+2048,session.reservedTokens());
        assertTrue(captured.getFirst().length>evidence.length());
    }
    @Test void slowBodyDeadline() {
        var stream=new InputStream() {
            boolean closed;
            public synchronized int read() throws IOException { while(!closed) try {wait();}catch(InterruptedException e){throw new IOException();} throw new IOException("closed"); }
            public synchronized void close(){closed=true;notifyAll();}
        };
        assertThrows(IOException.class,()->Advisory.readBounded(stream,Duration.ofMillis(20)));
    }
}
