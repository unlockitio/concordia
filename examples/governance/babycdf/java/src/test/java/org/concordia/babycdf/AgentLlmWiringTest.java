package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentLlmWiringTest {
    @Test void bothAgentsInvokeTransportOnceForStablePinnedEvidence() throws Exception {
        var evidence = new AdvisoryTest();
        var clause = new Advisory.Clause("C1","anchor","CAP","conflict","wrong");
        var fixture = new Advisory.Fixture(7,List.of(clause));
        Reviewer.LedgerRuleGateway ledger=()->List.of(new Reviewer.EffectiveRule("ledger-cid","architecture-review",7,List.of(clause)));
        for (boolean reviewer : List.of(true,false)) {
            var calls=new AtomicInteger(); var captured=new ArrayList<java.net.http.HttpRequest>();
            var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","sentinel-key"));
            LlmClient.Transport transport=request->{
                calls.incrementAndGet(); captured.add(request);
                var assessment=Advisory.JSON.createObjectNode().put("commentary","Check execution evidence; sentinel-key");
                assessment.putArray("verdicts").addObject().put("id","C1").put("verdict","INCONCLUSIVE")
                        .put("rationale","missing execution detail").putArray("quotes");
                var response=Advisory.JSON.createObjectNode();
                var choice=response.putArray("choices").addObject().put("finish_reason","stop");
                choice.putObject("message").put("role","assistant").put("content",assessment.toString());
                return new LlmClient.Exchange() {
                    public CompletableFuture<LlmClient.Reply> future() { return CompletableFuture.completedFuture(new LlmClient.Reply(200,response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
                    public void cancel() {}
                };
            };
            var log=new ByteArrayOutputStream();
            try(var supplemental=new SupplementalAssessment(reviewer?"Reviewer":"Advisory",settings,new LlmClient(settings,transport,System::nanoTime,false),null,new PrintStream(log))) {
                var original=reviewer ? Reviewer.review(ledger,evidence.api()) : Advisory.pollOnce(evidence.api(),fixture,new HashSet<>());
                var actual=reviewer ? Reviewer.review(ledger,evidence.api(),supplemental) : Advisory.pollOnce(evidence.api(),fixture,new HashSet<>(),supplemental);
                for(int i=0;i<actual.path("reviews").size();i++) assertEquals(original.path("reviews").get(i).path("findings"),actual.path("reviews").get(i).path("findings"));
                if(reviewer) Reviewer.review(ledger,evidence.api(),supplemental);
                else Advisory.pollOnce(evidence.api(),fixture,new HashSet<>(),supplemental);
                assertEquals(2,calls.get());
                assertTrue(actual.toString().contains("supplemental_assessment"));
                assertTrue(log.toString().contains("LLM rule check")); assertFalse(log.toString().contains("sentinel-key"));
                assertEquals(2,captured.size());
                evidence.proposal += " changed";
                if(reviewer) Reviewer.review(ledger,evidence.api(),supplemental);
                else Advisory.pollOnce(evidence.api(),fixture,new HashSet<>(),supplemental);
                assertEquals(3,calls.get());
                evidence.corrupt=true;
                if(reviewer) Reviewer.review(ledger,evidence.api(),supplemental);
                else Advisory.pollOnce(evidence.api(),fixture,new HashSet<>(),supplemental);
                assertEquals(3,calls.get()); evidence.corrupt=false;
            }
        }
    }
}
