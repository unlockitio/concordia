// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.nio.file.Path;import java.time.*;import java.util.*;import java.io.IOException;import java.util.concurrent.TimeUnit;
import static org.concordia.babycdf.SourceCatalogueCodec.*;

/** Production Ledger API adapter; all reads bounded, no receipt/head archive operation. */
public final class SourceOpenerLedger implements SourceOpenerQueue.Ledger,AutoCloseable {
 static final String RULE="Cap.Examples.BabyCdf.RuleVote",CREDENTIAL="Cap.Examples.BabyCdf.SourceObservationCredential";
 interface Rpc {List<EventOuterClass.CreatedEvent> active(String module,String entity)throws IOException;void submit(CommandServiceOuterClass.SubmitAndWaitRequest request)throws IOException;Instant now();}
 private final String party,impl,governor,user;private final Rpc rpc;private final ManagedChannel channel;private final IntakeLedger proofLedger;
 public SourceOpenerLedger(int port,String party,String impl,String demo,String governor,String user,Path journal) {
  if(!impl.matches("[0-9a-f]{64}")||party.isBlank()||governor.isBlank())throw new IllegalArgumentException("explicit opener ledger configuration required");
  this.party=party;this.impl=impl;this.governor=governor;this.user=user;
  channel=NettyChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().maxInboundMessageSize(20_000_000).build();
  proofLedger=new IntakeLedger(port,party,impl,demo,governor,user,journal);
  rpc=new Rpc(){public Instant now(){return Instant.now();}
   public List<EventOuterClass.CreatedEvent> active(String module,String entity)throws IOException {
    try {var service=StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS);long offset=service.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
     var filters=TransactionFilterOuterClass.Filters.newBuilder().addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder().setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder().setTemplateId(IntakeLedger.id(IntakeLedger.IMPL_PACKAGE_NAME,module,entity))));
     var stream=service.getActiveContracts(StateServiceOuterClass.GetActiveContractsRequest.newBuilder().setActiveAtOffset(offset).setEventFormat(TransactionFilterOuterClass.EventFormat.newBuilder().putFiltersByParty(party,filters.build()).setVerbose(true)).build());
     var out=new ArrayList<EventOuterClass.CreatedEvent>();int count=0,bytes=0;
     while(stream.hasNext()){var row=stream.next();bytes+=row.getSerializedSize();if(++count>10000||bytes>20_000_000||row.hasIncompleteAssigned()||row.hasIncompleteUnassigned())throw new IOException("bounded opener ACS unavailable");
      if(row.hasActiveContract()){var e=row.getActiveContract().getCreatedEvent();IntakeLedger.verifyActiveEvent(e,IntakeLedger.id(impl,module,entity),party);if(!e.getSignatoriesList().equals(List.of(party)))throw new IOException("foreign opener signatory");out.add(e);}}
     return out;
    }catch(io.grpc.StatusRuntimeException failure){throw new IOException("opener ACS unavailable");}
   }
   public void submit(CommandServiceOuterClass.SubmitAndWaitRequest request)throws IOException {try{CommandServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).submitAndWait(request);}catch(io.grpc.StatusRuntimeException failure){System.err.println("Automatic source submission status="+failure.getStatus().getCode().name());throw new IOException("opener submission uncertain; proof only");}}
  };
 }
 SourceOpenerLedger(String party,String impl,String governor,String user,Rpc rpc,IntakeLedger proofLedger){this.party=party;this.impl=impl;this.governor=governor;this.user=user;this.rpc=rpc;this.proofLedger=proofLedger;channel=null;}
 static ValueOuterClass.Value none(){return ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()).build();}
 static ValueOuterClass.Value optionalText(String s){return s.isEmpty()?none():ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(text(s))).build();}
 static ValueOuterClass.Value pin(SourceOpenerQueue.Binding b)throws IOException {
  var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.pinJson());
  if(b.kind().equals("milestone"))return ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor("DeliveryPin").setValue(record("deliveryCommit",text(json.get(0).asText()),"issueDigest",text(json.get(1).asText()),"voteCommentId",integer(json.get(2).asLong()),"voteCommentAt",timestamp(Instant.parse(json.get(3).asText()))))).build();
  if(!json.isArray()||json.size()!=2||!json.get(0).asText().matches("[0-9a-f]{40}")||!json.get(1).isArray())throw new IOException("invalid queue pin");
  var files=ValueOuterClass.List.newBuilder();for(var f:json.get(1)){if(!f.isArray()||f.size()!=4||!f.get(2).asText().matches("[0-9a-f]{40}")||f.get(1).asText().equals("removed"))throw new IOException("invalid queue file pin");files.addElements(record("path",text(f.get(0).asText()),"blob",text(f.get(2).asText())));}
  if(files.getElementsCount()==0)throw new IOException("empty queue pin");
  return ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor("ProposalPin").setValue(record("headCommit",text(json.get(0).asText()),"files",ValueOuterClass.Value.newBuilder().setList(files).build()))).build();
 }
 static ValueOuterClass.Value source(SourceOpenerQueue.Binding b)throws IOException {return record("credentialCid",ValueOuterClass.Value.newBuilder().setContractId(b.credentialCid()).build(),"expectedFingerprint",text(b.fingerprint()),"expectedVersion",integer(b.version()),"pin",pin(b),"originalProposal",integer(b.kind().equals("milestone")?Long.parseLong(b.amendmentOriginal()):b.number()),"previousRound",optionalText(b.previousRound()));}
 static ValueOuterClass.Value agenda(SourceOpenerQueue.Binding b){return ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder().setConstructor(b.kind().equals("proposal")?"Grant":"Milestone").setValue(record("kind",text(b.kind()),"url",text("https://github.com/"+b.repo()+(b.kind().equals("proposal")?"/pull/":"/issues/")+b.number()),"contentVersion",text(b.fingerprint())))).build();}
 private final Map<String,VerifiedAmendment> amendments=new HashMap<>();
 private final Map<String,SourceOpenerQueue.AmendmentCommitment> commitments=new HashMap<>();
 public SourceOpenerQueue.AmendmentCommitment commitment(String round)throws IOException {var c=commitments.get(round);if(c==null)throw new IOException("amendment commitment unavailable");return c;}
 public void restoreCommitment(String round,SourceOpenerQueue.AmendmentCommitment c)throws IOException {var old=commitments.putIfAbsent(round,c);if(old!=null&&!old.equals(c))throw new IOException("amendment commitment conflict");}
 public SourceOpenerQueue.Binding bindAmendment(SourceEvidenceCollector.Ready ready,VerifiedAmendment verified)throws IOException {
  if(verified.amending()!=ready.number())throw new IOException("amendment source mismatch");
  var b=bind(ready);amendments.put(b.roundId(),verified);
  var originals=rpc.active(RULE,"AgendaRoundReceipt").stream().filter(e->{try{var s=IntakeLedger.field(e.getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();return IntakeLedger.number(IntakeLedger.field(s,"observation").getRecord(),"number")==verified.original();}catch(IOException invalid){return false;}}).toList();
  if(originals.size()!=1)throw new IOException("unique active original receipt required");String originalRound=IntakeLedger.text(originals.getFirst().getCreateArguments(),"roundId");
  var originalSnapshot=IntakeLedger.field(originals.getFirst().getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();
  String originalEncoded=Base64.getEncoder().encodeToString(normalized(IntakeLedger.field(originalSnapshot,"pin")).toByteArray());String amendingEncoded=Base64.getEncoder().encodeToString(normalized(pin(b)).toByteArray());
  var c=new SourceOpenerQueue.AmendmentCommitment(verified.repo(),verified.original(),verified.amending(),originalRound,originalEncoded,amendingEncoded,verified.manifestDigest(),verified.evidenceId());restoreCommitment(b.roundId(),c);
  return new SourceOpenerQueue.Binding(b.repo(),b.number(),b.kind(),b.credentialCid(),b.version(),b.fingerprint(),b.evidenceDigest(),b.roundId(),b.eventAt(),b.sourceId(),b.pinJson(),"",Long.toString(verified.original()));
 }
 private IntakePlan plan(SourceOpenerQueue.Binding b)throws IOException {return IntakePlan.bound(new IntakeSource("source-"+b.number(),b.kind().equals("proposal")?IntakeSource.AgendaConstructor.Grant:IntakeSource.AgendaConstructor.Milestone,b.kind(),"https://github.com/"+b.repo()+(b.kind().equals("proposal")?"/pull/":"/issues/")+b.number(),b.fingerprint(),IntakeSource.Evidence.CURATED_UNVERIFIED),b.roundId(),agenda(b),source(b));}
 public List<Long> demoInventory(String kind)throws IOException {
  if(!Set.of("proposal","milestone").contains(kind))throw new IOException("invalid inventory kind");var selected=new TreeSet<Long>();var seen=new HashSet<Long>();var rows=rpc.active(CREDENTIAL,"SourceObservationCredential");long bytes=0;
  for(var event:rows){bytes+=event.getSerializedSize();if(seen.size()>=10000||bytes>20_000_000)throw new IOException("bounded opener ACS unavailable");var credential=CanonicalSourceCredential.decode(event,party);var o=credential.observation();if(!seen.add(o.number()))throw new IntegrationStageFailure(IntegrationStageFailure.Stage.credential_decode);if(o.kind()==(kind.equals("proposal")?SourceObservation.Kind.PROPOSAL:SourceObservation.Kind.MILESTONE)&&ConcordiaDemoScope.candidate(ConcordiaDemoScope.REPO,o.kind(),o.number()))selected.add(o.number());}
  if(selected.size()>50)throw new IntegrationStageFailure(IntegrationStageFailure.Stage.inventory_scope_limit);return List.copyOf(selected);
 }
 public List<Long> inventory(String kind)throws IOException {
  var numbers=new TreeSet<Long>();for(var e:rpc.active(CREDENTIAL,"SourceObservationCredential")){var o=CanonicalSourceCredential.decode(e,party).observation();if(o.kind()==(kind.equals("proposal")?SourceObservation.Kind.PROPOSAL:SourceObservation.Kind.MILESTONE))numbers.add(o.number());}
  if(numbers.size()>50)throw new IOException("automatic inventory batch bound; reconcile inventory partition");return List.copyOf(numbers);
 }
 public void retainMilestoneEvidence(SourceEvidenceCollector.Batch batch)throws IOException {
  for(var e:batch.entries())if(!e.type().equals("command")&&!e.type().equals("label"))throw new IOException("unexpected milestone evidence type");
  // Read-only validation of latest head/receipt integrity; never archives either.
  for(var head:rpc.active(RULE,"SourceRoundHead")){
   long number=IntakeLedger.number(head.getCreateArguments(),"number");String latest=IntakeLedger.text(head.getCreateArguments(),"latest");
   var receipts=rpc.active(RULE,"AgendaRoundReceipt").stream().filter(r->latest.equals(safe(r,"roundId"))).toList();
   if(receipts.size()!=1)throw new IOException("dangling milestone head/reset; reconciliation blocked");
   var copied=IntakeLedger.field(receipts.getFirst().getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();
   var observation=IntakeLedger.field(copied,"observation").getRecord();
   if(!IntakeLedger.field(copied,"opener").getParty().equals(party)||!IntakeLedger.field(observation,"kind").getEnum().getConstructor().equals("SourceMilestone")||IntakeLedger.number(observation,"number")!=number)throw new IOException("foreign milestone head receipt");
  }
 }
 public Optional<SourceOpenerQueue.Proof> proof(SourceOpenerQueue.Binding b)throws IOException {
  var receipts=rpc.active(RULE,"AgendaRoundReceipt").stream().filter(e->b.roundId().equals(safe(e,"roundId"))).toList();if(receipts.size()>1)throw new IOException("duplicate opener receipt");
  if(receipts.isEmpty())return Optional.empty();
  if(b.kind().equals("proposal")&&!b.amendmentOriginal().isEmpty()) {
   var relations=rpc.active("Cap.Examples.BabyCdf.SourceAgenda","SourceAmendmentRound").stream().filter(e->b.roundId().equals(safe(e,"roundId"))).toList();
   if(relations.size()!=1)throw new IOException("amendment durable relation missing/conflicting");
   var relation=IntakeLedger.field(relations.getFirst().getCreateArguments(),"relation").getRecord();
   var expected=commitment(b.roundId());
   if(!IntakeLedger.text(relation,"originalRound").equals(expected.originalRound())||!IntakeLedger.text(relation,"manifestDigest").equals(expected.manifestDigest())||IntakeLedger.number(relation,"evidenceId")!=expected.evidenceId())throw new IOException("amendment durable commitment mismatch");
   var expectedAmending=pin(b);if(!Base64.getEncoder().encodeToString(normalized(IntakeLedger.field(relation,"amendingPin")).toByteArray()).equals(expected.amendingPin())||!normalized(IntakeLedger.field(relation,"amendingPin")).equals(normalized(expectedAmending)))throw new IOException("amending pin swapped");
   var originalRows=rpc.active(RULE,"AgendaRoundReceipt").stream().filter(e->expected.originalRound().equals(safe(e,"roundId"))).toList();if(originalRows.size()!=1)throw new IOException("original receipt missing/reset");
   var originalSnapshot=IntakeLedger.field(originalRows.getFirst().getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();
   if(!Base64.getEncoder().encodeToString(normalized(IntakeLedger.field(relation,"originalPin")).toByteArray()).equals(expected.originalPin())||!normalized(IntakeLedger.field(relation,"originalPin")).equals(normalized(IntakeLedger.field(originalSnapshot,"pin"))))throw new IOException("original pin swapped");
   if(IntakeLedger.number(relation,"originalNumber")!=expected.original()||IntakeLedger.number(relation,"amendingNumber")!=expected.amending()||IntakeLedger.number(relation,"originalNumber")!=Long.parseLong(b.amendmentOriginal())||IntakeLedger.number(relation,"amendingNumber")!=b.number()||!IntakeLedger.text(relation,"repo").equals(b.repo()))throw new IOException("amendment durable identity mismatch");
  }
  var request=new Intake.Request("queue",party,"automatic",b.roundId(),"source-"+b.number(),120,0);
  if(!proofLedger.proof(request,plan(b)))throw new IOException("receipt lacks linked action proof");
  boolean active=rpc.active(RULE,"AgendaProposal").stream().anyMatch(e->b.roundId().equals(safe(e,"roundId")));
  return Optional.of(new SourceOpenerQueue.Proof(b.roundId(),SourceOpenerQueue.digest(b),!active));
 }
 private static ValueOuterClass.Value normalized(ValueOuterClass.Value v){
  var b=v.toBuilder();if(v.hasRecord()){var r=v.getRecord().toBuilder().clearRecordId().clearFields();for(var f:v.getRecord().getFieldsList())r.addFields(f.toBuilder().setValue(normalized(f.getValue())));b.setRecord(r);}else if(v.hasVariant())b.setVariant(v.getVariant().toBuilder().clearVariantId().setValue(normalized(v.getVariant().getValue())));else if(v.hasList()){var l=v.getList().toBuilder().clearElements();for(var e:v.getList().getElementsList())l.addElements(normalized(e));b.setList(l);}return b.build();
 }
 private static String safe(EventOuterClass.CreatedEvent e,String label){try{return IntakeLedger.text(e.getCreateArguments(),label);}catch(IOException invalid){return "";}}
 public boolean anyActiveRound()throws IOException {return !rpc.active(RULE,"AgendaProposal").isEmpty();}
 public SourceOpenerQueue.Binding bind(SourceEvidenceCollector.Ready ready)throws IOException {
  var candidates=rpc.active(CREDENTIAL,"SourceObservationCredential").stream().filter(e->{try{return IntakeLedger.number(IntakeLedger.field(e.getCreateArguments(),"observation").getRecord(),"number")==ready.number();}catch(IOException bad){return false;}}).toList();
  if(candidates.size()!=1)throw new IOException("exact registered source unavailable");var e=candidates.getFirst();var r=e.getCreateArguments();var observation=SourceCatalogueCodec.observation(IntakeLedger.field(r,"observation").getRecord());
  String previous="",original="";
  if(ready.kind().equals("milestone")){if(observation.parentPR()==null)throw new IOException("milestone original proposal missing");original=Long.toString(observation.parentPR());var heads=rpc.active(RULE,"SourceRoundHead").stream().filter(h->{try{return IntakeLedger.number(h.getCreateArguments(),"number")==ready.number();}catch(IOException bad){return false;}}).toList();if(heads.size()>1)throw new IOException("duplicate milestone head");if(!heads.isEmpty())previous=IntakeLedger.text(heads.getFirst().getCreateArguments(),"latest");retainMilestoneEvidence(new SourceEvidenceCollector.Batch(List.of(),List.of(),Instant.EPOCH,Instant.EPOCH));}
  return new SourceOpenerQueue.Binding(SourceEvidenceCollector.REPO,ready.number(),ready.kind(),e.getContractId(),IntakeLedger.number(r,"version"),IntakeLedger.text(r,"fingerprint"),observation.bodySha256(),ready.roundId(),ready.eventAt(),ready.sourceId(),ready.pinJson(),previous,original);
 }
 public SourceOpenerQueue.Binding prepare(SourceOpenerQueue.Binding b)throws IOException {
  if(!b.kind().equals("milestone"))return b;
  retainMilestoneEvidence(new SourceEvidenceCollector.Batch(List.of(),List.of(),Instant.EPOCH,Instant.EPOCH));
  var heads=rpc.active(RULE,"SourceRoundHead").stream().filter(h->{try{return IntakeLedger.number(h.getCreateArguments(),"number")==b.number();}catch(IOException invalid){return false;}}).toList();if(heads.size()>1)throw new IOException("duplicate milestone head");String previous=heads.isEmpty()?"":IntakeLedger.text(heads.getFirst().getCreateArguments(),"latest");
  return new SourceOpenerQueue.Binding(b.repo(),b.number(),b.kind(),b.credentialCid(),b.version(),b.fingerprint(),b.evidenceDigest(),b.roundId(),b.eventAt(),b.sourceId(),b.pinJson(),previous,b.amendmentOriginal());
 }
 public void validate(SourceOpenerQueue.Binding b)throws IOException {
  var rows=rpc.active(CREDENTIAL,"SourceObservationCredential").stream().filter(e->e.getContractId().equals(b.credentialCid())).toList();if(rows.size()!=1)throw new IOException("pinned credential archived/revised");
  var r=rows.getFirst().getCreateArguments();var o=IntakeLedger.field(r,"observation");var observed=SourceCatalogueCodec.observation(o.getRecord());var previous=r.getFieldsList().stream().filter(f->f.getLabel().equals("previousDigest")).findFirst().map(f->f.getValue()).orElse(none());
  String prior=previous.getOptional().hasValue()?previous.getOptional().getValue().getText():null;
  if(!IntakeLedger.text(r,"repo").equals(b.repo())||!IntakeLedger.field(r,"opener").getParty().equals(party)||observed.number()!=b.number()||observed.kind()!=(b.kind().equals("proposal")?SourceObservation.Kind.PROPOSAL:SourceObservation.Kind.MILESTONE)
    ||IntakeLedger.number(r,"version")!=b.version()||!IntakeLedger.text(r,"fingerprint").equals(b.fingerprint())||!observed.bodySha256().equals(b.evidenceDigest())||!SourceCatalogueDigest.digest(o.getRecord(),b.version(),prior).equals(b.fingerprint()))throw new IOException("exact credential evidence mismatch");
  pin(b);if(b.kind().equals("proposal")&&!b.previousRound().isEmpty())throw new IOException("proposal predecessor requires verified amendment capability");
  if(b.kind().equals("milestone")){var heads=rpc.active(RULE,"SourceRoundHead").stream().filter(h->{try{return IntakeLedger.number(h.getCreateArguments(),"number")==b.number();}catch(IOException invalid){return false;}}).toList();String latest=heads.isEmpty()?"":IntakeLedger.text(heads.getFirst().getCreateArguments(),"latest");if(heads.size()>1||!latest.equals(b.previousRound()))throw new IOException("milestone latest head changed/reset");retainMilestoneEvidence(new SourceEvidenceCollector.Batch(List.of(),List.of(),Instant.EPOCH,Instant.EPOCH));}
  if(rpc.active(RULE,"AgendaGovernor").stream().noneMatch(e->e.getContractId().equals(governor)&&party.equals(e.getSignatories(0))))throw new IOException("governor unavailable");
 }
 public void open(SourceOpenerQueue.Binding b,int seconds)throws IOException {
  if(seconds!=120)throw new IOException("automatic window must be120");validate(b);
  var args=record("proposer",ValueOuterClass.Value.newBuilder().setParty(party).build(),"roundId",text(b.roundId()),"agenda",agenda(b),"baseVersion",integer(0),"closesAt",timestamp(rpc.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(seconds)),"source",ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.newBuilder().setValue(source(b))).build());
  String choice="ProposeAgenda";
  if(b.kind().equals("proposal")&&!b.amendmentOriginal().isEmpty()) {
   var verified=amendments.get(b.roundId());if(verified==null)throw new IOException("verified amendment must be restored, never journal assertion");
   var originals=rpc.active(RULE,"AgendaRoundReceipt").stream().filter(e->{try{var s=IntakeLedger.field(e.getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();return IntakeLedger.number(IntakeLedger.field(s,"observation").getRecord(),"number")==verified.original();}catch(IOException invalid){return false;}}).toList();
   if(originals.size()!=1)throw new IOException("amendment original receipt ambiguous/missing");var original=originals.getFirst();var snapshot=IntakeLedger.field(original.getCreateArguments(),"sourceSnapshot").getOptional().getValue().getRecord();
   var originalPin=IntakeLedger.field(snapshot,"pin");var fieldsPin=originalPin.getVariant().getValue().getRecord();var filePins=new ArrayList<SourceEvidence.FilePin>();for(var file:IntakeLedger.field(fieldsPin,"files").getList().getElementsList())filePins.add(new SourceEvidence.FilePin(IntakeLedger.text(file.getRecord(),"path"),"modified",IntakeLedger.text(file.getRecord(),"blob"),""));
   // S2 pin commits statuses as well. A receipt without matching full pin metadata
   // cannot establish the checked original; do not silently weaken the commitment.
   String receiptHead=IntakeLedger.text(fieldsPin,"headCommit");
   if(!receiptHead.equals(verified.originalProposal().head())||!VerifiedAmendment.pin(verified.originalProposal()).equals(verified.originalPin()))throw new IOException("original receipt head/manifest mismatch");
   var expectedFiles=verified.originalProposal().files().stream().map(f->f.path()+":"+f.blob()).sorted().toList();var receiptFiles=filePins.stream().map(f->f.path()+":"+f.blob()).sorted().toList();if(!expectedFiles.equals(receiptFiles))throw new IOException("original receipt files differ from checked manifest");
   var relation=record("repo",text(b.repo()),"originalNumber",integer(verified.original()),"amendingNumber",integer(b.number()),"originalRound",text(IntakeLedger.text(original.getCreateArguments(),"roundId")),"originalPin",IntakeLedger.field(snapshot,"pin"),"amendingPin",pin(b),"manifestDigest",text(verified.manifestDigest()),"evidenceId",integer(verified.evidenceId()));
   var fields=args.getRecord().toBuilder();for(int i=0;i<fields.getFieldsCount();i++)if(fields.getFields(i).getLabel().equals("source"))fields.setFields(i,fields.getFields(i).toBuilder().setValue(source(b)));
   fields.addFields(ValueOuterClass.RecordField.newBuilder().setLabel("relation").setValue(relation));args=ValueOuterClass.Value.newBuilder().setRecord(fields).build();choice="ProposeAmendment";
  }
  var command=CommandsOuterClass.Command.newBuilder().setExercise(CommandsOuterClass.ExerciseCommand.newBuilder().setTemplateId(IntakeLedger.id(impl,RULE,"AgendaGovernor")).setContractId(governor).setChoice(choice).setChoiceArgument(args));
  rpc.submit(CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder().setCommands(CommandsOuterClass.Commands.newBuilder().setUserId(user).addActAs(party).setCommandId("babycdf-auto-"+SourceOpenerQueue.digest(b)).addCommands(command)).build());
 }
 public void close(){proofLedger.close();if(channel!=null)channel.shutdownNow();}
}
