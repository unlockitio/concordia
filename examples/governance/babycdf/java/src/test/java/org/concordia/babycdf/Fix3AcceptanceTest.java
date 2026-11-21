package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
class Fix3AcceptanceTest {
    Reviewer.EffectiveRule rule() { return new Reviewer.EffectiveRule("cid","architecture-review",1,List.of(new Advisory.Clause("c1","a","CAP evidence","b","wrong"))); }
    @Test void reviewEntryRejectsTamperedCollectedBundle() throws Exception {
        var item=SupplementalAssessmentTest.item().put("kind","proposal_pr").put("head_commit","a".repeat(40)).put("_review_text","CAP evidence");
        var file=new EvidenceBundle.File("f",EvidenceBundle.blob("CAP evidence"),12,"bad evidence");
        item.set("evidence_bundle",Advisory.JSON.valueToTree(new EvidenceBundle("url","a".repeat(40),List.of(file),null,null,true)));
        var collected=Advisory.JSON.createObjectNode();collected.putArray("errors");collected.putArray("inputs").add(item);
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();
        var client=new LlmClient(settings,r->{calls.incrementAndGet();throw new AssertionError("provider called");},System::nanoTime,false);
        var session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(new ByteArrayOutputStream()));
        var result=Reviewer.review(()->List.of(rule()),path->{throw new AssertionError("unexpected API");},session,()->collected);
        var review=result.path("reviews").get(0);
        assertTrue(review.path("pinned").asBoolean());assertFalse(review.path("supplemental_valid").asBoolean());assertEquals("bundle_verification_failed",review.path("evidence_warning").asText());assertEquals(0,calls.get());
    }
    @Test void sharedPatNeverReachesOutputsOrGateway(@TempDir Path root) throws Exception {
        String sentinel="synthetic-shared-pat-sentinel-only";
        Path secret=root.resolve("secret");Files.writeString(secret,sentinel+"\n");
        var env=Map.of("INTAKE_GITHUB_TOKEN_FILE",secret.toString());
        var github=new Advisory.GitHub(env);
        assertEquals(HttpClient.Redirect.NEVER,github.redirectPolicy());
        var auth=IntakeGitHub.configured(env);
        URI api=URI.create("https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls/184");
        var request=HttpRequest.newBuilder(api);auth.authorizeRead(request,api);
        assertEquals("Bearer "+sentinel,request.GET().build().headers().firstValue("Authorization").orElseThrow());
        URI redirected=URI.create("https://example.invalid/redirect");var target=HttpRequest.newBuilder(redirected);
        var error=assertThrows(IOException.class,()->auth.authorizeRead(target,redirected));
        assertTrue(target.GET().build().headers().firstValue("Authorization").isEmpty());
        assertFalse(error.toString().contains(sentinel));
        var invalid=assertThrows(IllegalArgumentException.class,()->new Advisory.GitHub(Map.of("INTAKE_GITHUB_TOKEN_FILE",root.resolve("missing").toString())));
        assertFalse(invalid.toString().contains(sentinel));
        var logs=new ByteArrayOutputStream();var requests=new ArrayList<String>();
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic-llm-key"));
        byte[] response=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("PASS",",\"commentary\":\"public advice\""));
        var client=new LlmClient(settings,r->{requests.add(new String(SupplementalAssessmentTest.body(r),java.nio.charset.StandardCharsets.UTF_8));return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);
        Path cache=root.resolve("cache");
        var session=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs),cache);
        var stub=new EvidencePollingTest.Api() {
            public com.fasterxml.jackson.databind.JsonNode get(String path) throws IOException {
                URI uri=URI.create("https://api.github.com/"+path);
                var builder=HttpRequest.newBuilder(uri);auth.authorizeRead(builder,uri);
                assertEquals("Bearer "+sentinel,builder.GET().build().headers().firstValue("Authorization").orElseThrow());
                return super.get(path);
            }
        };
        var collected=Advisory.collect(stub,true);
        var item=(com.fasterxml.jackson.databind.node.ObjectNode)collected.path("inputs").get(0);
        String evidence=item.path("_review_text").asText();item.remove("_review_text");
        session.assess(item,evidence,rule(),"review");Advisory.stripBundleContents(item);
        assertEquals(1,requests.size());assertFalse(item.toString().contains(sentinel));assertFalse(logs.toString().contains(sentinel));
        for(String body:requests) assertFalse(body.contains(sentinel));
        try(var files=Files.list(cache)) {for(Path path:files.toList()) assertFalse(Files.readString(path).contains(sentinel));}
        assertFalse(auth.toString().contains(sentinel));assertFalse(github.toString().contains(sentinel));
    }
}
