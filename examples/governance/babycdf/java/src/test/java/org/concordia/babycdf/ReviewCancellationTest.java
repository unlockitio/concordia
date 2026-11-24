package org.concordia.babycdf;

import com.daml.ledger.api.v2.EventOuterClass;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReviewCancellationTest {
    @Test void canceledCycleDoesNotPublishOrAdvanceState()throws Exception {
        var reads=new AtomicInteger();var writes=new AtomicInteger();
        var gateway=new Reviewer.LedgerRuleGateway(){public List<Reviewer.EffectiveRule> activeRules(){return List.of();}public List<EventOuterClass.CreatedEvent> agendaEvents()throws InterruptedException{reads.incrementAndGet();throw new InterruptedException("synthetic cancellation");}};
        var review=new AgendaFollowingReview(path->{throw new AssertionError("no HTTP after cancellation");});
        try{
            assertThrows(InterruptedException.class,()->review.cycle(gateway));assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,reads.get());
            assertThrows(java.io.InterruptedIOException.class,()->new IntegratedReviewOutput(null).publish(Advisory.JSON.createObjectNode(),value->writes.incrementAndGet()));assertEquals(0,writes.get());
        }finally{Thread.interrupted();}
    }
    @Test void canceledEvaluationCannotPopulateMemoryCache()throws Exception {
        var cache=new RuleVerdictCache(null);var evaluations=new AtomicInteger();
        try{assertThrows(java.io.InterruptedIOException.class,()->cache.get("c".repeat(64),()->{evaluations.incrementAndGet();Thread.currentThread().interrupt();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("synthetic"),""),"model");}));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
        var hit=cache.get("c".repeat(64),()->{evaluations.incrementAndGet();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("synthetic"),""),"model");});assertFalse(hit.cached());assertEquals(2,evaluations.get());
    }
    @Test void canceledDiskEvaluationCannotPersistEntry()throws Exception {
        var root=java.nio.file.Path.of("cancel-cache-"+java.util.UUID.randomUUID()).toAbsolutePath();var cache=new RuleVerdictCache(root);var evaluations=new AtomicInteger();
        try{assertThrows(java.io.InterruptedIOException.class,()->cache.get("d".repeat(64),()->{evaluations.incrementAndGet();Thread.currentThread().interrupt();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("synthetic"),""),"model");}));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
        var hit=cache.get("d".repeat(64),()->{evaluations.incrementAndGet();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("synthetic"),""),"model");});assertFalse(hit.cached());assertEquals(2,evaluations.get());if(java.nio.file.Files.exists(root))try(var files=java.nio.file.Files.list(root)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".json")));}
    }
    @Test void safeErrorCategoryChangesIdentityButDiagnosticTextDoesNot()throws Exception {
        var result=Advisory.JSON.createObjectNode().put("status","partial");result.putArray("reviews");var error=result.putArray("errors").addObject().put("contract_id","round").put("category","rule_selection").put("detail","diagnostic one");String first=IntegratedReviewOutput.identity(result);error.put("detail","diagnostic two");assertEquals(first,IntegratedReviewOutput.identity(result));error.put("category","evidence_lookup");assertNotEquals(first,IntegratedReviewOutput.identity(result));
    }
    @Test void canceledPollingCannotPublishOrSleep()throws Exception {
        var cycles=new AtomicInteger();var publications=new AtomicInteger();var sleeps=new AtomicInteger();
        try{AgentPolling.run(1,()->{cycles.incrementAndGet();try{throw new InterruptedException("synthetic cancellation");}catch(InterruptedException canceled){Thread.currentThread().interrupt();return;}},millis->sleeps.incrementAndGet());assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,cycles.get());assertEquals(0,publications.get());assertEquals(0,sleeps.get());}finally{Thread.interrupted();}
    }
    @Test void cachePublicationGuardPreservesInterrupt() {
        Thread.currentThread().interrupt();try{assertThrows(java.io.InterruptedIOException.class,ReviewDeadline::check);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
    }
}
