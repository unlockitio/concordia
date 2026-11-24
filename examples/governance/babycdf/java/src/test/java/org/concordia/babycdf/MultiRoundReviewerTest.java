package org.concordia.babycdf;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiRoundReviewerTest {
    private static EventOuterClass.CreatedEvent effectiveRuleEvent(int version)throws Exception {
        var path=java.nio.file.Path.of("../rules/rule-v"+version+".json");var ref=record("location",text("rules/rule-v"+version+".json"),"commit",none(),"sha256",text(RuleDocument.sha(java.nio.file.Files.readAllBytes(path))));
        return EventOuterClass.CreatedEvent.newBuilder().setContractId("rule-"+version).setTemplateId(IntakeLedger.id("d".repeat(64),"Cap.Examples.BabyCdf.RuleVote","EffectiveRule")).addSignatories("opener").addWitnessParties("opener").setCreateArguments(record("draft",record("version",integer(version),"ruleId",text("architecture-review"),"ruleRef",ref)).getRecord()).build();
    }
    private static Reviewer.EffectiveRule rule(int version)throws Exception {
        var path=java.nio.file.Path.of("../rules/rule-v"+version+".json");var ref=record("location",text("rules/rule-v"+version+".json"),"commit",none(),"sha256",text(RuleDocument.sha(java.nio.file.Files.readAllBytes(path))));
        var event=EventOuterClass.CreatedEvent.newBuilder().setContractId("rule-"+version).setCreateArguments(record("draft",record("version",integer(version),"ruleId",text("architecture-review"),"ruleRef",ref)).getRecord()).build();
        var rule=Reviewer.LocalSandboxGateway.decode(event);assertNull(rule.ruleFailure());return rule;
    }
    private static final Instant NOW=Instant.parse("2026-11-23T00:00:00Z");
    private static ValueOuterClass.Value none(){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()).build();}
    private static ValueOuterClass.Value party(){return ValueOuterClass.Value.newBuilder().setParty("opener").build();}
    private static EventOuterClass.CreatedEvent proposal(int number,String head,String path,String content)throws java.io.IOException{
        var source=new SourceObservation(number,number==540?SourceObservation.Kind.valueOf("MILESTONE"):SourceObservation.Kind.PROPOSAL,"https://github.com/"+ConcordiaDemoScope.REPO+(number==540?"/issues/":"/pull/")+number,"Concordia",SourceObservation.hash("body-"+number),"open",NOW,NOW,number==540?184L:null,"",false,SourceObservation.hash("body-"+number),false);
        var wire=observation(source);var fingerprint=SourceCatalogueDigest.digest(wire.getRecord());
        var files=ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder().addElements(record("path",text(path),"blob",text(EvidenceBundle.blob(content))))).build();
        var pin=ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor("ProposalPin").setValue(record("headCommit",text(head),"files",files))).build();
        var snapshot=record("sourceCid",ValueOuterClass.Value.newBuilder().setContractId("credential").build(),"opener",party(),"observation",wire,"fingerprint",text(fingerprint),"version",integer(1),"previousDigest",none(),"evidenceDigest",text(source.bodySha256()),"pin",pin,"roundId",text("round-"+number),"originalProposal",integer(number),"previousRound",none());
        var some=ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(snapshot)).build();
        return EventOuterClass.CreatedEvent.newBuilder().setContractId("agenda-"+number).setTemplateId(IntakeLedger.id("d".repeat(64),"Cap.Examples.BabyCdf.RuleVote","AgendaProposal")).addSignatories("opener").addWitnessParties("opener").setCreateArguments(record("opener",party(),"roundId",text("round-"+number),"subject",record("url",text(source.url()),"body",text(fingerprint)),"sourceSnapshot",some).getRecord()).build();
    }
    private static EventOuterClass.CreatedEvent milestone(String content)throws Exception {
        var proposal=proposal(540,"c".repeat(40),"docs/delivery.md",content);
        var args=proposal.getCreateArguments();var snapshotValue=args.getFieldsList().stream().filter(f->f.getLabel().equals("sourceSnapshot")).findFirst().orElseThrow().getValue().getOptional().getValue();
        var snapshot=snapshotValue.getRecord().toBuilder();
        var pin=ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor("DeliveryPin").setValue(record("deliveryCommit",text("c".repeat(40)),"issueDigest",text(SourceObservation.hash("body-540")),"voteCommentId",integer(123),"voteCommentAt",timestamp(NOW)))).build();
        for(int i=0;i<snapshot.getFieldsCount();i++){var field=snapshot.getFields(i);if(field.getLabel().equals("pin"))snapshot.setFields(i,field.toBuilder().setValue(pin));if(field.getLabel().equals("originalProposal"))snapshot.setFields(i,field.toBuilder().setValue(integer(184)));}
        var out=args.toBuilder();for(int i=0;i<out.getFieldsCount();i++){var field=out.getFields(i);if(field.getLabel().equals("sourceSnapshot"))out.setFields(i,field.toBuilder().setValue(ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(ValueOuterClass.Value.newBuilder().setRecord(snapshot)))));}
        return proposal.toBuilder().setCreateArguments(out).build();
    }
    @Test void sameImmutablePinDifferentSubjectsRemainIndependent()throws Exception {
        String content="shared immutable pin with distinct subjects\n",head="a".repeat(40),path="proposals/concordia-a.md",blob=EvidenceBundle.blob(content);var calls=new java.util.concurrent.atomic.AtomicInteger();
        Advisory.Api api=request->{calls.incrementAndGet();return Advisory.JSON.readTree(request.contains("trees")?"{\"truncated\":false,\"tree\":[{\"path\":\""+path+"\",\"type\":\"blob\",\"sha\":\""+blob+"\"}]}":"{\"encoding\":\"base64\",\"content\":\""+Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"}");};
        var events=List.of(proposal(473,head,path,content),proposal(475,head,path,content));var gateway=new Reviewer.LedgerRuleGateway(){public List<EventOuterClass.CreatedEvent> agendaEvents(){return events;}public List<Reviewer.EffectiveRule> activeRules()throws Exception{return List.of(rule(1));}};
        var review=new AgendaFollowingReview(api);var first=review.cycle(gateway);assertEquals(2,first.path("reviews").size());assertEquals(4,calls.get());assertNotEquals(first.path("reviews").get(0).path("evidence_identity"),first.path("reviews").get(1).path("evidence_identity"));review.cycle(gateway);assertEquals(4,calls.get());
    }
    @Test void firstOfThreeInterruptedEvidenceStopsEveryLaterRound()throws Exception {
        String a="cancellation evidence A\n",b="cancellation evidence B\n";var events=List.of(proposal(473,"a".repeat(40),"proposals/concordia-a.md",a),proposal(475,"b".repeat(40),"proposals/concordia-b.md",b),milestone("delivery cancellation evidence\n"));var calls=new java.util.concurrent.atomic.AtomicInteger();
        Advisory.Api api=path->{calls.incrementAndGet();throw new InterruptedException("synthetic cancellation");};var gateway=new Reviewer.LedgerRuleGateway(){public List<EventOuterClass.CreatedEvent> agendaEvents(){return events;}public List<Reviewer.EffectiveRule> activeRules()throws Exception{return List.of(rule(1));}};
        try{assertThrows(InterruptedException.class,()->new AgendaFollowingReview(api).cycle(gateway));assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,calls.get());}finally{Thread.interrupted();}
    }
    @Test void productionGatewayUsesOneOffsetAndRejectsForeignEvents()throws Exception {
        var ends=new java.util.concurrent.atomic.AtomicInteger();var offsets=new ArrayList<Long>();var entities=new ArrayList<String>();String packageId="d".repeat(64);
        var rpc=new Reviewer.LocalSandboxGateway.SnapshotRpc(){public long ledgerEnd(){ends.incrementAndGet();return 42;}public List<EventOuterClass.CreatedEvent> events(long offset,String entity){offsets.add(offset);entities.add(entity);return List.of();}};
        var snapshot=new Reviewer.LocalSandboxGateway(1,"opener",packageId,rpc).cycleSnapshot();assertTrue(snapshot.agendaEvents().isEmpty());assertEquals(1,ends.get());assertEquals(List.of(42L,42L,42L),offsets);assertEquals(List.of("AgendaProposal","EffectiveRule","RuleRegistry"),entities);
        var foreign=new Reviewer.LocalSandboxGateway.SnapshotRpc(){public long ledgerEnd(){return 42;}public List<EventOuterClass.CreatedEvent> events(long offset,String entity){return List.of(EventOuterClass.CreatedEvent.newBuilder().setContractId("foreign").build());}};
        assertThrows(java.io.IOException.class,()->new Reviewer.LocalSandboxGateway(1,"opener",packageId,foreign).cycleSnapshot());
    }
    @Test void snapshotCapturesRulesAndRegistryOnceBeforeRoundEvaluation()throws Exception {
        var reads=new java.util.concurrent.atomic.AtomicInteger();var registryReads=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new Reviewer.LedgerRuleGateway(){public List<EventOuterClass.CreatedEvent> agendaEvents(){return List.of(EventOuterClass.CreatedEvent.newBuilder().setContractId("malformed-a").build(),EventOuterClass.CreatedEvent.newBuilder().setContractId("malformed-b").build());}public List<Reviewer.EffectiveRule> activeRules(){reads.incrementAndGet();return List.of();}public List<EventOuterClass.CreatedEvent> registryEvents(){registryReads.incrementAndGet();return List.of();}};
        new AgendaFollowingReview(path->{throw new AssertionError("no evidence expected");}).cycle(gateway);assertEquals(1,reads.get());assertEquals(1,registryReads.get());
    }
    @Test void lateEvidenceCannotPublishSuccessAndOtherRoundsRemainExplicit()throws Exception {
        String content="immutable late evidence\n";var clock=new java.util.concurrent.atomic.AtomicLong();
        var events=List.of(proposal(473,"a".repeat(40),"proposals/concordia-a.md",content),proposal(475,"b".repeat(40),"proposals/concordia-b.md",content));
        var calls=new java.util.concurrent.atomic.AtomicInteger();Advisory.Api api=path->{calls.incrementAndGet();clock.set(java.time.Duration.ofSeconds(60).toNanos());return Advisory.JSON.createObjectNode();};
        var gateway=new Reviewer.LedgerRuleGateway(){public List<EventOuterClass.CreatedEvent> agendaEvents(){return events;}public List<Reviewer.EffectiveRule> activeRules(){return List.of(new Reviewer.EffectiveRule("rule-1","architecture-review",1,List.of(new Advisory.Clause("criterion","anchor","immutable","conflict","forbidden")),null,null));}};
        var result=new AgendaFollowingReview(api,null,clock::get).cycle(gateway);assertEquals("partial",result.path("status").asText());assertEquals(2,result.path("reviews").size());for(var row:result.path("reviews")){assertEquals("UNEVALUATED_PENDING",row.path("status").asText());assertFalse(row.path("pinned").asBoolean());}assertEquals(1,calls.get());
    }
    @Test void distinctPinnedSubjectsHaveIndependentEvidenceAndEveryRuleReevaluates()throws Exception {
        String a="hello immutable evidence A\n",b="hello immutable evidence B\n",c="distinct immutable delivery evidence\n";
        var events=List.of(proposal(473,"a".repeat(40),"proposals/concordia-a.md",a),proposal(475,"b".repeat(40),"proposals/concordia-b.md",b),milestone(c));
        var calls=new ArrayList<String>();
        Advisory.Api api=path->{calls.add(path);if(path.contains("/commits/"))return Advisory.JSON.readTree("{\"sha\":\""+"c".repeat(40)+"\",\"commit\":{\"committer\":{\"date\":\"2026-11-22T00:00:00Z\"}}}");String content=path.contains("c".repeat(40))||path.endsWith(EvidenceBundle.blob(c))?c:path.contains("a".repeat(40))||path.endsWith(EvidenceBundle.blob(a))?a:b;String name=content.equals(c)?"docs/delivery.md":content.equals(a)?"proposals/concordia-a.md":"proposals/concordia-b.md";if(path.contains("trees"))return Advisory.JSON.readTree("{\"truncated\":false,\"tree\":[{\"type\":\"blob\",\"path\":\""+name+"\",\"sha\":\""+EvidenceBundle.blob(content)+"\"}]}");return Advisory.JSON.readTree("{\"encoding\":\"base64\",\"content\":\""+Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\"}");};
        class Gateway implements Reviewer.LedgerRuleGateway {int version=1;public List<EventOuterClass.CreatedEvent> agendaEvents(){return events;}public List<Reviewer.EffectiveRule> activeRules()throws Exception{return List.of(rule(version));}}
        var ledger=new Gateway();var providerCalls=new java.util.concurrent.atomic.AtomicInteger();var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic-offline"));
        var supplemental=new SupplementalAssessment("Reviewer",settings,new LlmClient(settings,request->{providerCalls.incrementAndGet();String[] ids=ledger.version==1?new String[]{"first-release-scope","independent-security-audit"}:new String[]{"compose-with-asset-layer","auditable-state","acceptance-criteria"};var verdict=Advisory.JSON.createObjectNode();var entries=verdict.putArray("verdicts");for(String id:ids){var entry=entries.addObject().put("id",id).put("verdict","INCONCLUSIVE").put("rationale","offline evidence is insufficient");entry.putArray("quotes");}try{return SupplementalAssessmentTest.reply(200,SupplementalAssessmentTest.response(Advisory.JSON.writeValueAsString(verdict)));}catch(Exception invalid){throw new IllegalStateException(invalid);}},System::nanoTime,false),null,System.out);
        var registryMembers=ValueOuterClass.Value.newBuilder().setList(ValueOuterClass.List.newBuilder().addElements(party())).build();
        var rpc=new Reviewer.LocalSandboxGateway.SnapshotRpc(){public long ledgerEnd(){return 42;}public List<EventOuterClass.CreatedEvent> events(long offset,String entity)throws java.io.IOException{assertEquals(42,offset);try{return switch(entity){case "AgendaProposal"->events;case "EffectiveRule"->List.of(effectiveRuleEvent(ledger.version));case "RuleRegistry"->List.of(EventOuterClass.CreatedEvent.newBuilder().setContractId("registry-"+ledger.version).setTemplateId(IntakeLedger.id("d".repeat(64),"Cap.Examples.BabyCdf.RuleVote","RuleRegistry")).addSignatories("opener").addWitnessParties("opener").setCreateArguments(record("opener",party(),"version",integer(ledger.version),"members",registryMembers).getRecord()).build());default->throw new AssertionError(entity);};}catch(Exception invalid){throw new java.io.IOException(invalid);}}};
        var production=new Reviewer.LocalSandboxGateway(1,"opener","d".repeat(64),rpc);
        var following=new AgendaFollowingReview(api,supplemental);var first=following.cycle(production);assertEquals(3,first.path("reviews").size());assertNotEquals(first.path("reviews").get(0).path("evidence_identity"),first.path("reviews").get(1).path("evidence_identity"));assertEquals("milestone_issue",first.path("reviews").get(2).path("kind").asText());assertEquals("c".repeat(40),first.path("reviews").get(2).path("head_commit").asText());assertEquals(8,calls.size());
        ledger.version=2;var second=following.cycle(production);for(var row:second.path("reviews"))assertEquals(2,row.path("rule_version_evaluated").asInt());assertEquals(8,calls.size());assertNotEquals(IntegratedReviewOutput.identity(first),IntegratedReviewOutput.identity(second));assertEquals(6,providerCalls.get());following.cycle(production);assertEquals(6,providerCalls.get());
        var invalid=EventOuterClass.CreatedEvent.newBuilder().setContractId("invalid-round").build();var mixed=new Reviewer.LedgerRuleGateway(){public List<Reviewer.EffectiveRule> activeRules()throws Exception{return ledger.activeRules();}public List<EventOuterClass.CreatedEvent> agendaEvents(){var all=new ArrayList<>(events);all.add(invalid);return all;}};var partial=following.cycle(mixed);assertEquals("partial",partial.path("status").asText());assertEquals(4,partial.path("reviews").size());assertEquals(1,partial.path("errors").size());assertEquals(6,providerCalls.get());
        var budgetEvents=new ArrayList<>(events);budgetEvents.add(proposal(476,"a".repeat(40),"proposals/concordia-a.md",a));budgetEvents.add(proposal(477,"b".repeat(40),"proposals/concordia-b.md",b));var budgetGateway=new Reviewer.LedgerRuleGateway(){public List<Reviewer.EffectiveRule> activeRules()throws Exception{return ledger.activeRules();}public List<EventOuterClass.CreatedEvent> agendaEvents(){return budgetEvents;}};var exhausted=following.cycle(budgetGateway);assertEquals(5,exhausted.path("reviews").size());assertEquals("partial",exhausted.path("status").asText());assertTrue(java.util.stream.StreamSupport.stream(exhausted.path("reviews").spliterator(),false).anyMatch(row->"UNEVALUATED_PENDING".equals(row.path("status").asText())&&"provider_budget_exhausted".equals(row.path("reason").asText())));assertEquals(7,providerCalls.get());
    }
}
