package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;import com.fasterxml.jackson.databind.*;import java.io.IOException;import java.util.*;
/** Decodes the signed immutable copy, never the current source credential. */
public record IntegratedAgendaSnapshot(String cid,String round,String opener,long number,String kind,String url,String fingerprint,long version,String digest,String head,List<File> files,boolean blocked,Delivery delivery) {
 public record File(String path,String blob){}
 public record Delivery(String commit,String issueDigest,long commentId,java.time.Instant triggerAt){}
 public IntegratedAgendaSnapshot{files=List.copyOf(files);}
 private static ValueOuterClass.Record safeRecord(ValueOuterClass.Record r,String label)throws IOException{var v=IntakeLedger.field(r,label);if(!v.hasRecord())throw new IOException("snapshot record type");return v.getRecord();}
 public static IntegratedAgendaSnapshot decode(EventOuterClass.CreatedEvent event)throws IOException {
  var r=event.getCreateArguments();String opener=IntakeLedger.field(r,"opener").getParty(),round=IntakeLedger.text(r,"roundId");
  if(!IntakeLedger.field(r,"opener").hasParty()||!IntakeLedger.field(safeRecord(r,"subject"),"url").hasText()||opener.isBlank()||round.isBlank()||!event.getSignatoriesList().equals(List.of(opener))||!event.getWitnessPartiesList().contains(opener))throw new IOException("agenda issuer/witness mismatch");
  var optional=IntakeLedger.field(r,"sourceSnapshot");if(!optional.hasOptional()||!optional.getOptional().hasValue())return null;
  if(!optional.getOptional().getValue().hasRecord())throw new IOException("malformed snapshot record");var s=optional.getOptional().getValue().getRecord();
  var sourceCid=IntakeLedger.field(s,"sourceCid");if(!sourceCid.hasContractId()||sourceCid.getContractId().isBlank())throw new IOException("source CID missing");
  var observation=IntakeLedger.field(s,"observation").getRecord();var o=SourceCatalogueCodec.observation(observation);long version=IntakeLedger.number(s,"version");String fingerprint=IntakeLedger.text(s,"fingerprint"),digest=IntakeLedger.text(s,"evidenceDigest");
  var previous=IntakeLedger.field(s,"previousDigest");if(!previous.hasOptional()||(previous.getOptional().hasValue()&&!previous.getOptional().getValue().hasText()))throw new IOException("snapshot previous digest type");String prior=previous.getOptional().hasValue()?previous.getOptional().getValue().getText():null;
  if((version==1)!=(prior==null)||(prior!=null&&!prior.matches("[0-9a-f]{64}")))throw new IOException("snapshot lineage mismatch");
  long original=IntakeLedger.number(s,"originalProposal");var previousRound=IntakeLedger.field(s,"previousRound");if(!previousRound.hasOptional())throw new IOException("previous round type");if(previousRound.getOptional().hasValue()){var value=previousRound.getOptional().getValue();if(!value.hasText()||value.getText().isBlank()||value.getText().equals(round))throw new IOException("previous round shape");}
  if(original<1||(o.kind()==SourceObservation.Kind.PROPOSAL?original!=o.number():o.parentPR()==null||original!=o.parentPR()))throw new IOException("source original identity mismatch");
  if(!opener.equals(IntakeLedger.field(s,"opener").getParty())||!round.equals(IntakeLedger.text(s,"roundId"))||!fingerprint.equals(SourceCatalogueDigest.digest(observation,version,prior))||!digest.equals(o.bodySha256())||version<1)throw new IOException("agenda immutable snapshot mismatch");
  var subject=IntakeLedger.field(r,"subject").getRecord();if(!IntakeLedger.text(subject,"url").equals(o.url())||!IntakeLedger.text(subject,"body").equals(fingerprint))throw new IOException("agenda subject/snapshot mismatch");
  var pin=IntakeLedger.field(s,"pin");if(!pin.hasVariant())throw new IOException("agenda pin malformed");String constructor=pin.getVariant().getConstructor();boolean milestone=o.kind()==SourceObservation.Kind.MILESTONE;var files=new ArrayList<File>();String head="";
  if(constructor.equals("ProposalPin")&&!milestone){var p=pin.getVariant().getValue().getRecord();head=IntakeLedger.text(p,"headCommit");if(!head.matches("[0-9a-f]{40}"))throw new IOException("invalid immutable head");var paths=new HashSet<String>();for(var f:IntakeLedger.field(p,"files").getList().getElementsList()){String path=IntakeLedger.text(f.getRecord(),"path"),blob=IntakeLedger.text(f.getRecord(),"blob");if(!paths.add(path)||!blob.matches("[0-9a-f]{40}")||path.isBlank())throw new IOException("invalid immutable file pin");files.add(new File(path,blob));}if(files.isEmpty())throw new IOException("empty immutable pin");}
  else if((constructor.equals("DeliveryPin")&&!milestone)||(!constructor.equals("BlockedPin")&&!constructor.equals("DeliveryPin")))throw new IOException("source kind/pin mismatch");
  boolean scope=milestone?ConcordiaDemoScope.milestone(ConcordiaDemoScope.REPO,o.parentPR()==null?0:o.parentPR()):ConcordiaDemoScope.proposal(o.number(),ConcordiaDemoScope.REPO,new ObjectMapper().valueToTree(List.of(head,files.stream().map(f->List.of(f.path(),"modified",f.blob(),"")).toList())));
  if(!scope)return null;
  Delivery deliveryPin=null;
  if(milestone&&constructor.equals("DeliveryPin")){var delivery=pin.getVariant().getValue().getRecord();var trigger=IntakeLedger.field(delivery,"voteCommentAt");if(!trigger.hasTimestamp()||!IntakeLedger.text(delivery,"deliveryCommit").matches("[0-9a-f]{40}")||!IntakeLedger.text(delivery,"issueDigest").equals(digest)||IntakeLedger.number(delivery,"voteCommentId")<1||SourceCatalogueCodec.timestamp(trigger).isBefore(o.updatedAt()))throw new IOException("invalid milestone immutable pin");deliveryPin=new Delivery(IntakeLedger.text(delivery,"deliveryCommit"),digest,IntakeLedger.number(delivery,"voteCommentId"),SourceCatalogueCodec.timestamp(trigger));}
  if(constructor.equals("BlockedPin")){String reason=IntakeLedger.text(pin.getVariant().getValue().getRecord(),"reason");if(reason.isBlank()||reason.length()>512)throw new IOException("invalid blocked reason");}
  return new IntegratedAgendaSnapshot(event.getContractId(),round,opener,o.number(),milestone?"milestone":"proposal",o.url(),fingerprint,version,digest,head,files,constructor.equals("BlockedPin"),deliveryPin);
 }
}
