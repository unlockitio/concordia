package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
class EvidenceBundleTest {
    static final String HEAD="c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521";
    EvidenceBundle.File file(String content) { return new EvidenceBundle.File("proposal.md",EvidenceBundle.blob(content),content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,content); }
    @Test void goldenFixtureAndBundle() throws Exception {
        Path golden=Path.of("../demo/fixtures/pr184-concordia.md");
        String content=Files.readString(golden); assertEquals(28024,Files.size(golden)); assertEquals(360,content.lines().count());
        assertEquals("f591787a772848e4537aba5d8ba02be1764647da",EvidenceBundle.blob(content));
        var collected=Advisory.collect(new OfflineFile(golden),true);
        var bundle=Advisory.JSON.treeToValue(java.util.stream.StreamSupport.stream(collected.path("inputs").spliterator(),false).filter(n->n.path("kind").asText().equals("proposal_pr")).findFirst().orElseThrow().path("evidence_bundle"),EvidenceBundle.class);
        assertEquals(content,bundle.files().getFirst().content()); assertTrue(bundle.completeness()); assertTrue(bundle.verified(HEAD));
        var issue=Advisory.JSON.readTree(Files.readString(Path.of("../demo/fixtures/issue540.json")));
        var issueBundle=EvidenceBundle.issue(issue.path("html_url").asText(),issue.path("title").asText(),issue.path("body").asText());
        assertFalse(issueBundle.completeness()); assertTrue(issueBundle.files().isEmpty()); assertFalse(issueBundle.body().isBlank());
    }
    @Test void tamperMissingExtraAndBudget() {
        var good=file("evidence support");
        var tampered=new EvidenceBundle.File(good.path(),good.blobSha(),good.bytes(),"Evidence support");
        assertFalse(EvidenceBundle.proposal("url",HEAD,List.of(tampered),Set.of(good.path()),true).completeness());
        assertFalse(EvidenceBundle.proposal("url",HEAD,List.of(),Set.of(good.path()),true).completeness());
        assertFalse(EvidenceBundle.proposal("url",HEAD,List.of(good),Set.of(),true).completeness());
        var large=file("x".repeat(EvidenceBundle.MAX_EVIDENCE_BYTES));
        var bundle=EvidenceBundle.proposal("url",HEAD,List.of(large),Set.of(large.path()),true);
        assertTrue(bundle.completeness()); assertEquals(large.content(),bundle.evidenceText());
        var over=file(large.content()+"x"); assertFalse(EvidenceBundle.proposal("url",HEAD,List.of(over),Set.of(over.path()),true).completeness());
    }
    @Test void cacheComponents() {
        var f=file("evidence support"); var base=EvidenceBundle.proposal("url",HEAD,List.of(f),Set.of(f.path()),true);
        for(var changed:List.of(new EvidenceBundle("other",HEAD,base.files(),null,null,true),new EvidenceBundle("url","d".repeat(40),base.files(),null,null,true),new EvidenceBundle("url",HEAD,List.of(file("other evidence")),null,null,true),new EvidenceBundle("url",HEAD,base.files(),null,null,false))) assertNotEquals(base.cacheIdentity(),changed.cacheIdentity());
    }
    @Test void fullBudgetReachesMockTransportUntruncated() throws Exception {
        String content="evidence support "+"x".repeat(EvidenceBundle.MAX_EVIDENCE_BYTES-17);
        var f=file(content); var bundle=EvidenceBundle.proposal("https://github.com/canton-foundation/canton-dev-fund/pull/184",HEAD,List.of(f),Set.of(f.path()),true);
        var item=SupplementalAssessmentTest.item(); item.set("evidence_bundle",Advisory.JSON.valueToTree(bundle));
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic")); var calls=new java.util.concurrent.atomic.AtomicInteger();
        byte[] reply=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("PASS", ""));
        var client=new LlmClient(settings,request->{
            calls.incrementAndGet(); try {
                var root=Advisory.JSON.readTree(SupplementalAssessmentTest.body(request));
                var prompt=Advisory.JSON.readTree(root.path("messages").get(1).path("content").asText());
                assertFalse(prompt.has("untrusted_public_pinned_text"));
                assertEquals(content,Advisory.JSON.readTree(prompt.path("untrusted_evidence_bundle").asText()).path("files").get(0).path("content").asText());
            } catch(Exception e) { throw new AssertionError(e); }
            return SupplementalAssessmentTest.reply(200,reply);
        },System::nanoTime,false);
        new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(new ByteArrayOutputStream())).assess(item,content,SupplementalAssessmentTest.rule(1),"review");
        assertEquals(1,calls.get());
    }
}
