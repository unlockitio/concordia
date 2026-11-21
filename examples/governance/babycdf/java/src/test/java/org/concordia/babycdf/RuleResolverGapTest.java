package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RuleResolverGapTest {
    static final String COMMIT="a".repeat(40);
    static final byte[] DOCUMENT=("{\"schema\":\"babycdf.rule/1\",\"version\":1,\"clauses\":[{\"id\":\"c1\",\"kind\":\"PRESENCE_REQUIRED\",\"text\":\"criterion\",\"expectedAnchor\":\"a\",\"expected\":\"support\",\"conflictAnchor\":\"b\",\"conflict\":\"negative\",\"indicators\":[\"security audit\"]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Test void pinnedUrlsUseContentsApi() throws Exception {
        for(String url:List.of("https://github.com/owner/repo/blob/"+COMMIT+"/rules/r.json","https://raw.githubusercontent.com/owner/repo/"+COMMIT+"/rules/r.json")) {
            List<String> paths=new ArrayList<>();
            Advisory.Api api=path->{paths.add(path);return Advisory.JSON.createObjectNode().put("encoding","base64").put("content",Base64.getEncoder().encodeToString(DOCUMENT));};
            var ref=new RuleDocument.Ref(url,COMMIT,RuleDocument.sha(DOCUMENT));
            assertTrue(RuleDocument.resolve(ref,Path.of("."),r->RuleDocument.github(r,api)).verified());
            assertEquals(List.of("repos/owner/repo/contents/rules/r.json?ref="+COMMIT),paths);
        }
        assertEquals(java.net.http.HttpClient.Redirect.NEVER,new Advisory.GitHub(Map.of()).redirectPolicy());
    }
    @Test void remoteFailuresAreOpaque() throws Exception {
        String sentinel="PAT_SENTINEL_DO_NOT_DISCLOSE";
        for(String url:List.of("https://github.com/owner/repo/blob/main/r.json","https://evil.example/owner/repo/"+COMMIT+"/r.json","https://github.com/owner/repo/blob/"+COMMIT+"/r.json?x=y","https://github.com/owner/repo/blob/"+COMMIT+"/r&x.json","https://github.com/owner/repo/blob/"+COMMIT+"/r%3F.json")) {
            var ref=new RuleDocument.Ref(url,null,RuleDocument.sha(DOCUMENT));
            assertEquals("rule_unverified",RuleDocument.resolve(ref,Path.of("."),r->RuleDocument.github(r,path->{fail("API must not be called");return null;})).reason());
        }
        var ref=new RuleDocument.Ref("https://github.com/owner/repo/blob/"+COMMIT+"/r.json",COMMIT,RuleDocument.sha(DOCUMENT));
        for(Advisory.Api api:List.<Advisory.Api>of(path->{throw new java.io.IOException(sentinel);},path->Advisory.JSON.createObjectNode().put("encoding","base64").put("content",Base64.getEncoder().encodeToString(new byte[262145])))) {
            var result=RuleDocument.resolve(ref,Path.of("."),r->RuleDocument.github(r,api));
            assertEquals("rule_unverified",result.reason());assertFalse(result.toString().contains(sentinel));
        }
    }
    @Test void unverifiedRuleIsOverallOnlyWithoutProvider() throws Exception {
        var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var client=new LlmClient(settings,r->{calls.incrementAndGet();throw new AssertionError("must not call");},System::nanoTime,false);
        for(String cause:List.of("missing","hash_mismatch","oversize","malformed_rule","unknown_scheme","traversal","symlink")) {
            var item=Advisory.JSON.createObjectNode();
            var rule=new Reviewer.EffectiveRule("contract","architecture-review",1,List.of(),new RuleDocument.Ref("rule.json",null,"0".repeat(64)),cause);
            assertEquals(rule,Reviewer.select(List.of(rule)));
            new SupplementalAssessment("Reviewer",settings,client,null,new java.io.PrintStream(new java.io.ByteArrayOutputStream())).assess(item,"",rule,"review");
            var exact=item.path("supplemental_assessment").path("exact");
            assertEquals("INCONCLUSIVE",exact.path("overall").asText());assertEquals("rule_unverified",exact.path("reason").asText());assertTrue(exact.path("verdicts").isEmpty());
        }
        assertEquals(0,calls.get());
    }
    @Test void localFailureMatrix(@TempDir Path root) throws Exception {
        Files.write(root.resolve("good.json"),DOCUMENT);
        Files.write(root.resolve("large.json"),new byte[262145]);
        Files.writeString(root.resolve("invalid.json"),"{}");
        Files.createSymbolicLink(root.resolve("link.json"),root.resolve("good.json"));
        Files.createDirectories(root.resolve("actual"));Files.write(root.resolve("actual/r.json"),DOCUMENT);
        Files.createSymbolicLink(root.resolve("linked"),root.resolve("actual"));
        for(String path:List.of("missing.json","large.json","invalid.json","ftp:rule","../good.json","link.json","linked/r.json")) {
            String hash=path.equals("invalid.json")?RuleDocument.sha("{}".getBytes()):RuleDocument.sha(DOCUMENT);
            assertFalse(RuleDocument.local(new RuleDocument.Ref(path,null,hash),root).verified(),path);
        }
        assertEquals("rule_unverified",RuleDocument.local(new RuleDocument.Ref("good.json",null,"0".repeat(64)),root).reason());
    }
}
