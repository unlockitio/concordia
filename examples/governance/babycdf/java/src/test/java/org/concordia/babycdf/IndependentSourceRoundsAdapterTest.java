package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.daml.ledger.api.v2.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;

class IndependentSourceRoundsAdapterTest {
 @TempDir Path root;
 static ValueOuterClass.Value party(){return ValueOuterClass.Value.newBuilder().setParty("opener").build();}
 static ValueOuterClass.Value some(ValueOuterClass.Value v){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(v)).build();}
 static ValueOuterClass.Value none(){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder()).build();}
 static ValueOuterClass.Value cid(String v){return ValueOuterClass.Value.newBuilder().setContractId(v).build();}
 static EventOuterClass.CreatedEvent event(String id,ValueOuterClass.Value value){return EventOuterClass.CreatedEvent.newBuilder().setContractId(id).setTemplateId(IntakeLedger.id(SourceCatalogueLedgerTest.PKG,"Cap.Examples.BabyCdf.RuleVote","AgendaProposal")).setCreateArguments(value.getRecord()).addSignatories("opener").addWitnessParties("opener").build();}
 @Test void threeIndependentSubjectsRemainPendingAndRestartDoesNotResubmit()throws Exception {
  root=root.toRealPath();String pkg=SourceCatalogueLedgerTest.PKG,repo=SourceEvidenceCollector.REPO;
  Instant now=Instant.parse("2026-11-23T00:00:00.123456789Z");String content="same Concordia immutable proposal\n";String head="a".repeat(40),blob=EvidenceBundle.blob(content);
  var rows=new HashMap<String,List<EventOuterClass.CreatedEvent>>();var sent=new ArrayList<CommandServiceOuterClass.SubmitAndWaitRequest>();
  rows.put("AgendaGovernor",List.of(event("gov",record("opener",party()))));
  var observations=new HashMap<Long,SourceObservation>();
  for(long number:new long[]{473,475,540}){
   boolean milestone=number==540;
   observations.put(number,new SourceObservation(number,milestone?SourceObservation.Kind.MILESTONE:SourceObservation.Kind.PROPOSAL,"https://github.com/"+repo+(milestone?"/issues/":"/pull/")+number,"Subject "+number,SourceObservation.hash("body"),"open",Instant.parse("2026-11-21T00:00:00Z"),Instant.parse("2026-11-21T00:00:00Z"),milestone?184L:null,"",false,SourceObservation.hash("body"),false));
  }
  var server=new SourceCatalogueLedgerTest.Server();
  try(var catalogue=new SourceCatalogueLedger("opener",pkg,"user",root,server)){
   new JavaSourceIngestion(catalogue,c->new JavaSourceIngestion.Page(List.copyOf(observations.values()),"",true),s->{}).cycle();
  }
  rows.put("SourceObservationCredential",server.active.stream().filter(e->e.getTemplateId().getEntityName().equals("SourceObservationCredential")).toList());
  var collector=new SourceEvidenceCollector(new SourceEvidenceFetch((u,e)->{
   String path=u.getPath();String body;
   if(path.endsWith("comments"))body="[{\"id\":1,\"issue_url\":\"https://api.github.com/repos/"+repo+"/issues/540\",\"html_url\":\"https://github.com/"+repo+"/issues/540#issuecomment-1\",\"body\":\""+head+"\",\"created_at\":\"2026-11-22T01:00:00Z\",\"updated_at\":\"2026-11-22T01:00:00Z\"},{\"id\":2,\"issue_url\":\"https://api.github.com/repos/"+repo+"/issues/540\",\"html_url\":\"https://github.com/"+repo+"/issues/540#issuecomment-2\",\"body\":\"/vote\",\"created_at\":\"2026-11-22T02:00:00Z\",\"updated_at\":\"2026-11-22T02:00:00Z\"}]";
   else if(path.endsWith("events"))body="[]";
   else if(path.contains("commits"))body="{\"sha\":\""+head+"\",\"commit\":{\"committer\":{\"date\":\"2026-11-21T00:00:00Z\"}}}";
   else if(path.endsWith("files"))body="[{\"filename\":\"proposals/concordia-new.md\",\"status\":\"added\",\"sha\":\""+blob+"\"}]";
   else if(path.contains("trees"))body="{\"truncated\":false,\"tree\":[{\"path\":\"proposals/concordia-new.md\",\"type\":\"blob\",\"sha\":\""+blob+"\"}]}";
   else body="{\"body\":\"body\",\"updated_at\":\"2026-11-21T00:00:00Z\",\"created_at\":\"2026-01-01T00:00:00Z\",\"head\":{\"sha\":\""+head+"\"}}";
   return new SourceEvidenceFetch.Response(200,body.getBytes(),"","",Map.of());
  },Clock.fixed(now,ZoneOffset.UTC)));
  var rpc=new SourceOpenerLedger.Rpc(){
   public Instant now(){return now;}
   public List<EventOuterClass.CreatedEvent> active(String module,String entity){return rows.getOrDefault(entity,List.of());}
   void append(String entity,EventOuterClass.CreatedEvent value){var list=new ArrayList<>(rows.getOrDefault(entity,List.of()));list.add(value);rows.put(entity,list);}
   public void submit(CommandServiceOuterClass.SubmitAndWaitRequest command)throws java.io.IOException {
    sent.add(command);var args=command.getCommands().getCommands(0).getExercise().getChoiceArgument().getRecord();String round=IntakeLedger.text(args,"roundId");
    var source=IntakeLedger.field(args,"source").getOptional().getValue().getRecord();
    String credentialCid=IntakeLedger.field(source,"credentialCid").getContractId();
    var credential=rows.get("SourceObservationCredential").stream().filter(c->c.getContractId().equals(credentialCid)).findFirst().orElseThrow();
    var credentialArgs=credential.getCreateArguments();var wire=IntakeLedger.field(credentialArgs,"observation");
    long number=round.contains("/issues/540/")?540:round.endsWith("/473")?473:475;
    var copied=record("sourceCid",IntakeLedger.field(source,"credentialCid"),"opener",party(),"observation",wire,"fingerprint",IntakeLedger.field(credentialArgs,"fingerprint"),"version",integer(1),"previousDigest",none(),"evidenceDigest",text(SourceObservation.hash("body")),"pin",IntakeLedger.field(source,"pin"),"roundId",text(round),"originalProposal",integer(number==540?184:number),"previousRound",IntakeLedger.field(source,"previousRound"));
    String suffix="-"+sent.size();
    append("AgendaProposal",event("pending"+suffix,record("opener",party(),"proposer",IntakeLedger.field(args,"proposer"),"actionCid",cid("action"+suffix),"roundId",text(round),"closesAt",IntakeLedger.field(args,"closesAt"),"subject",record("url",text(observations.get(number).url()),"body",IntakeLedger.field(credentialArgs,"fingerprint")),"sourceSnapshot",some(copied))));
    append("AgendaRoundReceipt",event("receipt"+suffix,record("opener",party(),"proposer",party(),"roundId",text(round),"baseVersion",integer(0),"agenda",IntakeLedger.field(args,"agenda"),"actionCid",cid("action"+suffix),"sourceSnapshot",some(copied))));
    append("AgendaAction",event("action"+suffix,record("opener",party(),"baseVersion",integer(0),"agenda",IntakeLedger.field(args,"agenda"),"sourceSnapshot",some(copied))));
   }
  };
  Path journal=Files.createDirectory(root.resolve("queue"));var logs=new ArrayList<String>();
  java.util.concurrent.Callable<IntakeLedger> proofs=()->new IntakeLedger("opener",pkg,pkg,"gov","user",journal,r->fail("proof only"),(p,m,e)->rows.getOrDefault(e,List.of()),false);
  try(var opener=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proofs.call());var queue=new SourceOpenerQueue(journal,opener,Duration.ofDays(21),Clock.fixed(now,ZoneOffset.UTC),logs::add)){
   var scheduler=new SourceOpenerScheduler(collector,opener,queue,Clock.fixed(now,ZoneOffset.UTC),SourceOpenerScheduler.callerScope("--integrated-demo"));
   for(int i=1;i<=3;i++){assertEquals("opened",scheduler.automaticCycle(null));assertEquals(i,sent.size());assertEquals(i,rows.get("AgendaProposal").size());}
  }
  byte[] before=Files.readAllBytes(journal.resolve("source-opener-v1.json"));
  var originals=List.copyOf(rows.get("AgendaProposal"));
  for(String fieldName:List.of("actionCid","subject","subjectBody","sourceSnapshot","opener","proposer","closesAt")){
   var original=originals.getFirst();var changed=original.getCreateArguments().toBuilder();
   for(int index=0;index<changed.getFieldsCount();index++)if(changed.getFields(index).getLabel().equals(fieldName.equals("subjectBody")?"subject":fieldName)){
    var bad=switch(fieldName){case "actionCid"->cid("wrong-action");case "subject"->record("url",text("https://wrong.invalid"),"body",text("wrong"));case "subjectBody"->record("url",text("https://github.com/"+repo+"/pull/473"),"body",text("wrong-fingerprint"));case "sourceSnapshot"->none();case "closesAt"->ValueOuterClass.Value.newBuilder().setTimestamp(1).build();default->ValueOuterClass.Value.newBuilder().setParty("wrong-party").build();};
    changed.setFields(index,changed.getFields(index).toBuilder().setValue(bad));
   }
   var altered=new ArrayList<>(originals);altered.set(0,original.toBuilder().setCreateArguments(changed).build());rows.put("AgendaProposal",altered);
   var rejectedLogs=new ArrayList<String>();
   try(var opener=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proofs.call());var queue=new SourceOpenerQueue(journal,opener,Duration.ofDays(21),Clock.fixed(now,ZoneOffset.UTC),rejectedLogs::add)){
    assertThrows(java.io.IOException.class,queue::tick,fieldName);assertTrue(rejectedLogs.isEmpty());assertEquals(3,sent.size());
   }
  }
  rows.put("AgendaProposal",List.of(originals.getFirst(),originals.getFirst()));
  try(var opener=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proofs.call());var queue=new SourceOpenerQueue(journal,opener,Duration.ofDays(21),Clock.fixed(now,ZoneOffset.UTC),message->fail("ambiguous proof logged"))){assertThrows(java.io.IOException.class,queue::tick);}
  rows.put("AgendaProposal",originals);
  var legacyPath=Files.createDirectory(root.resolve("legacy-queue"));
  var legacyJson=(com.fasterxml.jackson.databind.node.ArrayNode)Advisory.JSON.readTree(before);legacyJson.set(0,Advisory.JSON.getNodeFactory().textNode("babycdf-opener/v2"));legacyJson.remove(3);Files.write(legacyPath.resolve("source-opener-v1.json"),Advisory.JSON.writeValueAsBytes(legacyJson));
  try(var opener=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proofs.call());var queue=new SourceOpenerQueue(legacyPath,opener,Duration.ofDays(21),Clock.fixed(now,ZoneOffset.UTC),message->fail("unknown deadline logged"))){assertThrows(java.io.IOException.class,queue::tick);assertEquals(3,sent.size());}
  try(var opener=new SourceOpenerLedger("opener",pkg,"gov","user",rpc,proofs.call());var queue=new SourceOpenerQueue(journal,opener,Duration.ofHours(2),Clock.fixed(now,ZoneOffset.UTC),logs::add)){
   assertEquals("proven-open",queue.tick());assertEquals(3,sent.size());
  }
  assertArrayEquals(before,Files.readAllBytes(journal.resolve("source-opener-v1.json")));assertEquals(3,logs.size());
  var rounds=new HashSet<String>();for(var command:sent)rounds.add(IntakeLedger.text(command.getCommands().getCommands(0).getExercise().getChoiceArgument().getRecord(),"roundId"));assertEquals(3,rounds.size());
  assertFalse(rows.containsKey("AgendaBallot"));
  var ruleBytes=Files.readAllBytes(Path.of("../rules/rule-v2.json"));
  var rule=event("effective-rule",record("draft",record("ruleId",text("architecture-review"),"version",integer(2),"ruleRef",record("location",text("rules/rule-v2.json"),"commit",none(),"sha256",text(RuleDocument.sha(ruleBytes)))))).toBuilder().setTemplateId(IntakeLedger.id(pkg,"Cap.Examples.BabyCdf.RuleVote","EffectiveRule")).build();
  var registry=event("registry",record("opener",party(),"version",integer(2),"members",ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder().addElements(party())).build())).toBuilder().setTemplateId(IntakeLedger.id(pkg,"Cap.Examples.BabyCdf.RuleVote","RuleRegistry")).build();
  Reviewer.LocalSandboxGateway.SnapshotRpc reviewRpc=new Reviewer.LocalSandboxGateway.SnapshotRpc(){
   public long ledgerEnd(){return 42;}
   public List<EventOuterClass.CreatedEvent> events(long offset,String entity){assertEquals(42,offset);return switch(entity){case "AgendaProposal"->rows.get("AgendaProposal");case "EffectiveRule"->List.of(rule);case "RuleRegistry"->List.of(registry);default->throw new AssertionError(entity);};}
  };
  var reviewGateway=new Reviewer.LocalSandboxGateway(1,"opener",pkg,reviewRpc);
  Advisory.Api evidence=path->Advisory.JSON.readTree(path.contains("trees")?"{\"truncated\":false,\"tree\":[{\"path\":\"proposals/concordia-new.md\",\"type\":\"blob\",\"sha\":\""+blob+"\"}]}":path.contains("commits")?"{\"sha\":\""+head+"\",\"commit\":{\"committer\":{\"date\":\"2026-11-21T00:00:00Z\"}}}":"{\"encoding\":\"base64\",\"content\":\""+Base64.getEncoder().encodeToString(content.getBytes())+"\"}");
  var result=new AgendaFollowingReview(evidence).cycle(reviewGateway);
  assertEquals(0,result.path("errors").size(),result.toString());assertEquals(3,result.path("reviews").size(),result.toString());
  var reviewedNumbers=new HashSet<Integer>();
  for(var review:result.path("reviews")){assertTrue(review.path("pinned").asBoolean(),review.toString());assertEquals(2,review.path("rule_version_evaluated").asInt(),review.toString());assertTrue(review.path("immutable_snapshot_verified").asBoolean(),review.toString());reviewedNumbers.add(review.path("number").asInt());}
  assertEquals(Set.of(473,475,540),reviewedNumbers);
  var snapshot=reviewGateway.cycleSnapshot();
  assertTrue(snapshot.registryBacked());assertEquals(3,snapshot.agendaEvents().size());assertEquals(1,snapshot.activeRules().size());assertEquals(1,snapshot.registryEvents().size());
  var reviewedUrls=new HashSet<String>();
  for(var proposal:snapshot.agendaEvents())reviewedUrls.add(IntakeLedger.text(IntakeLedger.field(proposal.getCreateArguments(),"subject").getRecord(),"url"));
  assertEquals(Set.of("https://github.com/"+repo+"/pull/473","https://github.com/"+repo+"/pull/475","https://github.com/"+repo+"/issues/540"),reviewedUrls);
 }
}
