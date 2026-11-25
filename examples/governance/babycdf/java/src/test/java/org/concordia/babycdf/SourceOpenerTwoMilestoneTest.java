package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.time.*;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class SourceOpenerTwoMilestoneTest {
 @TempDir Path root;
 @Test void togetherThenRestartBindsSecondToLatestOnlyBeforeSubmission()throws Exception {
  Instant now=Instant.parse("2026-11-23T00:00:00Z");String sha="a".repeat(40),repo=SourceEvidenceCollector.REPO;
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var comments=json.createArrayNode();
  for(int i=1;i<=3;i++){String time="2026-11-22T0"+i+":00:00Z";comments.add(json.createObjectNode().put("id",i).put("issue_url","https://api.github.com/repos/"+repo+"/issues/540").put("html_url","https://github.com/"+repo+"/issues/540#issuecomment-"+i).put("body",i==1?sha:"/vote").put("created_at",time).put("updated_at",time));}
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{String body=u.getPath().endsWith("comments")?comments.toString():u.getPath().endsWith("events")?"[]":u.getPath().contains("commits")?"{\"sha\":\""+sha+"\",\"commit\":{\"committer\":{\"date\":\"2026-11-21T00:00:00Z\"}}}":"{\"body\":\"body\",\"updated_at\":\"2026-11-21T00:00:00Z\"}";return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());},Clock.fixed(now,ZoneOffset.UTC)));
  var ready=collector.collectMilestones(540,now).ready();assertEquals(2,ready.size());
  class Ledger implements SourceOpenerQueue.Ledger {String latest="";boolean completed;int sends;Map<String,SourceOpenerQueue.Proof> proofs=new HashMap<>();
   public Optional<SourceOpenerQueue.Proof> proof(SourceOpenerQueue.Binding b){var p=proofs.get(b.roundId());return p==null?Optional.empty():Optional.of(new SourceOpenerQueue.Proof(p.roundId(),p.bindingDigest(),completed));}
   public boolean anyActiveRound(){return !proofs.isEmpty()&&!completed;}
   public SourceOpenerQueue.Binding prepare(SourceOpenerQueue.Binding b){return new SourceOpenerQueue.Binding(b.repo(),b.number(),b.kind(),b.credentialCid(),b.version(),b.fingerprint(),b.evidenceDigest(),b.roundId(),b.eventAt(),b.sourceId(),b.pinJson(),latest,b.amendmentOriginal());}
   public void validate(SourceOpenerQueue.Binding b)throws IOException{if(!b.previousRound().equals(latest))throw new IOException("head race");}
   public void openAt(SourceOpenerQueue.Binding b,Instant deadline){assertNotNull(deadline);open(b,0);}
   public void open(SourceOpenerQueue.Binding b,int seconds){sends++;assertEquals(latest,b.previousRound());latest=b.roundId();proofs.put(b.roundId(),new SourceOpenerQueue.Proof(b.roundId(),SourceOpenerQueue.digest(b),false));}
  }
  var ledger=new Ledger();java.util.function.Function<SourceEvidenceCollector.Ready,SourceOpenerQueue.Binding> bind=r->new SourceOpenerQueue.Binding(repo,540,"milestone","credential",1,"b".repeat(64),SourceObservation.hash("body"),r.roundId(),r.eventAt(),r.sourceId(),r.pinJson(),"","184");
  try(var q=new SourceOpenerQueue(root.toRealPath(),ledger)){for(var r:ready)q.enqueue(r,bind.apply(r));assertEquals("opened",q.tick());assertEquals(1,ledger.sends);assertEquals("opened",q.tick());assertEquals(2,ledger.sends);assertFalse(ledger.completed);assertEquals(2,ledger.proofs.size());}
  assertFalse(ledger.completed);
  try(var q=new SourceOpenerQueue(root.toRealPath(),ledger)){for(var r:ready)q.enqueue(r,bind.apply(r));assertEquals("proven-open",q.tick());assertEquals(2,ledger.sends);assertEquals(ready.get(0).roundId(),q.snapshot().get(1).binding().previousRound());}
 }
}
