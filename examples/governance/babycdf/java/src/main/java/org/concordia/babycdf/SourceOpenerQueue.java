// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Durable exact-evidence opener. No ballot, resolution, receipt/head archive or retry API. */
public final class SourceOpenerQueue implements AutoCloseable {
 public enum State { queued,submission_uncertain,proven_open,completed,blocked }
 public record Binding(String repo,long number,String kind,String credentialCid,long version,String fingerprint,
       String evidenceDigest,String roundId,Instant eventAt,long sourceId,String pinJson,String previousRound,String amendmentOriginal) {}
 public record AmendmentCommitment(String repo,long original,long amending,String originalRound,String originalPin,String amendingPin,String manifestDigest,long evidenceId) {}
 private final Map<String,AmendmentCommitment> amendmentCommitments=new HashMap<>();
 public synchronized void commitAmendment(String round,AmendmentCommitment commitment)throws IOException {
  if(!commitment.repo().equals(SourceEvidenceCollector.REPO)||commitment.original()==commitment.amending()||commitment.evidenceId()<1||commitment.originalRound().isBlank()||!commitment.manifestDigest().matches("[0-9a-f]{64}"))throw new IOException("invalid amendment commitment");
  var old=amendmentCommitments.putIfAbsent(round,commitment);if(old!=null&&!old.equals(commitment))throw new IOException("persisted amendment commitment changed");persist();
 }
 public record Row(Binding binding,State state,String reason) {}
 public record Proof(String roundId,String bindingDigest,boolean completed) {}
 public interface Ledger {
  /** Must attest active receipt plus linked action (historical if needed), never proposal alone. */
  Optional<Proof> proof(Binding binding)throws IOException;
  /** Coherent bounded active proposal scan: pending foreign/unbound rounds block opening. */
  boolean anyActiveRound()throws IOException;
  /** Exact active registered credential and predecessor/head checks, never silently substitute. */
  void validate(Binding binding)throws IOException;
  default Binding prepare(Binding binding)throws IOException{return binding;}
  /** Exactly one submission; caller durably latched uncertainty before invoking. */
  void open(Binding binding,int windowSeconds)throws IOException;
 }
 private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
   .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
   .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private final Path root;private final Ledger ledger;
 private final FileChannel lockChannel;private final java.nio.channels.FileLock lock;
 private final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
 private final Set<String> verifiedThisProcess=new HashSet<>();
 public SourceOpenerQueue(Path root,Ledger ledger)throws IOException {
  this.root=IntakeLedger.validateJournal(root);this.ledger=Objects.requireNonNull(ledger);
  lockChannel=FileChannel.open(this.root.resolve("source-opener.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
  try{lock=lockChannel.tryLock();if(lock==null){lockChannel.close();throw new IOException("opener writer already active");}}
  catch(java.nio.channels.OverlappingFileLockException conflict){lockChannel.close();throw new IOException("opener writer already active");}
  catch(IOException|RuntimeException failure){lockChannel.close();throw failure;}
  try{load();}catch(IOException invalid){lock.release();lockChannel.close();throw invalid;}
 }
 public static String digest(Binding binding) {
  return SourceObservation.hash(encode(binding));
 }
 private static String encode(Binding b) {
  try{return JSON.writeValueAsString(List.of(b.repo(),b.number(),b.kind(),b.credentialCid(),b.version(),b.fingerprint(),b.evidenceDigest(),b.roundId(),b.eventAt().toString(),b.sourceId(),b.pinJson(),b.previousRound(),b.amendmentOriginal()));}
  catch(Exception e){throw new IllegalArgumentException("invalid opener binding");}
 }
 private static Binding decode(JsonNode n)throws IOException {
  if(!n.isArray()||n.size()!=13)throw new IOException("invalid opener binding schema");
  for(int i:new int[]{0,2,3,5,6,7,8,10,11,12})if(!n.get(i).isTextual())throw new IOException("invalid binding field type");
  for(int i:new int[]{1,4,9})if(!n.get(i).isIntegralNumber()||!n.get(i).canConvertToLong())throw new IOException("invalid binding integer");
  try {var b=new Binding(n.get(0).textValue(),n.get(1).longValue(),n.get(2).textValue(),n.get(3).textValue(),n.get(4).longValue(),n.get(5).textValue(),n.get(6).textValue(),n.get(7).textValue(),Instant.parse(n.get(8).textValue()),n.get(9).longValue(),n.get(10).textValue(),n.get(11).textValue(),n.get(12).textValue());valid(b);return b;}
  catch(RuntimeException invalid){throw new IOException("invalid opener binding");}
 }
 public void close()throws IOException {lock.release();lockChannel.close();}
 private static void valid(Binding b) {
  if(!b.repo().equals(SourceEvidenceCollector.REPO)||b.number()<1||!Set.of("proposal","milestone").contains(b.kind())||b.credentialCid().isBlank()||b.version()<1
   ||!b.fingerprint().matches("[0-9a-f]{64}")||!b.evidenceDigest().matches("[0-9a-f]{64}")||b.roundId().isBlank()||b.roundId().length()>1024||b.sourceId()<1||b.pinJson().length()>131072
   ||b.previousRound()==null||b.amendmentOriginal()==null)throw new IllegalArgumentException("invalid opener binding");
  try{var pin=JSON.readTree(b.pinJson());
   if(b.kind().equals("milestone")){if(!pin.isArray()||pin.size()!=4||!pin.get(0).asText().matches("[0-9a-f]{40}")||!pin.get(1).asText().equals(b.evidenceDigest())||!pin.get(2).isIntegralNumber()||pin.get(2).asLong()!=b.sourceId()||!Instant.parse(pin.get(3).asText()).equals(b.eventAt()))throw new IOException();return;}
   if(!pin.isArray()||pin.size()!=2||!pin.get(0).isTextual()||!pin.get(0).textValue().matches("[0-9a-f]{40}")||!pin.get(1).isArray()||pin.get(1).isEmpty())throw new IOException();
   var paths=new HashSet<String>();for(var file:pin.get(1)){if(!file.isArray()||file.size()!=4)throw new IOException();for(var value:file)if(!value.isTextual())throw new IOException();String path=file.get(0).textValue();
    if(path.length()>1024||!path.matches("[A-Za-z0-9_.\\-/]+")||path.contains("..")||!paths.add(path)||!Set.of("added","modified","renamed","changed","copied").contains(file.get(1).textValue())||!file.get(2).textValue().matches("[0-9a-f]{40}")||(!file.get(3).textValue().isEmpty()&&!file.get(3).textValue().matches("[A-Za-z0-9_.\\-/]+")))throw new IOException();}
  }catch(IOException invalid){throw new IllegalArgumentException("invalid durable pin schema");}
 }
 private void load()throws IOException {
  Path file=root.resolve("source-opener-v1.json");if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
  if(Files.isSymbolicLink(file)||Files.size(file)>4_000_000)throw new IOException("unsafe opener journal");
  JsonNode document=JSON.readTree(Files.readAllBytes(file));
  if(!document.isArray()||document.size()!=3||!document.get(0).asText().equals("babycdf-opener/v2")||!document.get(1).isArray())throw new IOException("legacy/malformed opener journal");
  if(!document.get(2).isObject())throw new IOException("invalid amendment commitment schema");
  var entries=document.get(2).fields();while(entries.hasNext()){var e=entries.next();var c=e.getValue();if(!c.isArray()||c.size()!=8)throw new IOException("invalid amendment commitment");for(int i:new int[]{0,3,4,5,6})if(!c.get(i).isTextual())throw new IOException("invalid amendment commitment text");for(int i:new int[]{1,2,7})if(!c.get(i).isIntegralNumber()||!c.get(i).canConvertToLong()||c.get(i).longValue()<1)throw new IOException("invalid amendment commitment integer");if(!c.get(0).asText().equals(SourceEvidenceCollector.REPO)||c.get(1).asLong()==c.get(2).asLong()||c.get(3).asText().isBlank()||!c.get(6).asText().matches("[0-9a-f]{64}")||c.get(4).asText().length()>131072||c.get(5).asText().length()>131072)throw new IOException("invalid amendment commitment identity");amendmentCommitments.put(e.getKey(),new AmendmentCommitment(c.get(0).asText(),c.get(1).asLong(),c.get(2).asLong(),c.get(3).asText(),c.get(4).asText(),c.get(5).asText(),c.get(6).asText(),c.get(7).asLong()));}
  if(document.get(1).size()>1000)throw new IOException("opener queue bound");
  for(var row:document.get(1)){if(!row.isArray()||row.size()!=4||!row.get(1).isTextual()||!row.get(2).isTextual()||!row.get(3).isTextual())throw new IOException("invalid opener row");var b=decode(row.get(0));
   try{var value=new Row(b,State.valueOf(row.get(1).textValue()),row.get(2).textValue());if(!digest(b).equals(row.get(3).asText())||rows.put(b.roundId(),value)!=null)throw new IOException("conflicting opener journal");}
   catch(IllegalArgumentException bad){throw new IOException("invalid opener state");}}
 }
 private void persist()throws IOException {
  var values=new ArrayList<Object>();for(var row:ordered())values.add(List.of(JSON.readTree(encode(row.binding())),row.state().name(),row.reason(),digest(row.binding())));
  var commitments=new TreeMap<String,Object>();for(var e:amendmentCommitments.entrySet()){var c=e.getValue();commitments.put(e.getKey(),List.of(c.repo(),c.original(),c.amending(),c.originalRound(),c.originalPin(),c.amendingPin(),c.manifestDigest(),c.evidenceId()));}
  byte[] bytes=JSON.writeValueAsBytes(List.of("babycdf-opener/v2",values,commitments));if(bytes.length>4_000_000)throw new IOException("opener journal bound");
  Path temporary=Files.createTempFile(root,"opener-pending-",".json",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
  try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
  Path target=root.resolve("source-opener-v1.json");if(Files.isSymbolicLink(target))throw new IOException("unsafe opener journal target");
  Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
  try(var directory=FileChannel.open(root,StandardOpenOption.READ)){directory.force(true);}
 }
 private static boolean sameEvidence(Binding a,Binding b){return new Binding(a.repo(),a.number(),a.kind(),a.credentialCid(),a.version(),a.fingerprint(),a.evidenceDigest(),a.roundId(),a.eventAt(),a.sourceId(),a.pinJson(),b.previousRound(),a.amendmentOriginal()).equals(b);}
 private List<Row> ordered(){return rows.values().stream().sorted(Comparator.comparing((Row r)->r.binding().eventAt()).thenComparingInt(r->r.binding().kind().equals("proposal")?0:1).thenComparing(r->r.binding().repo()).thenComparingLong(r->r.binding().number()).thenComparingLong(r->r.binding().sourceId()).thenComparing(r->r.binding().roundId())).toList();}
 /** Only collector-private Ready capability enters executable queue; public Entry/status cannot. */
 public synchronized void enqueue(SourceEvidenceCollector.Ready ready,Binding binding)throws IOException {
  valid(binding);if(!ready.matches(binding))throw new IOException("collector capability/binding mismatch");verifiedThisProcess.add(binding.roundId());
  var old=rows.get(binding.roundId());if(old!=null){if(!old.binding().equals(binding)&&!(binding.kind().equals("milestone")&&sameEvidence(old.binding(),binding)))throw new IOException("round evidence conflict");return;}
  if(rows.size()>=1000)throw new IOException("opener queue bound");rows.put(binding.roundId(),new Row(binding,State.queued,""));persist();
 }
 public synchronized void retainEvidence(SourceEvidenceCollector.Batch batch)throws IOException {
  byte[] bytes=batch.manifest();if(bytes.length>2_000_000)throw new IOException("informational evidence bound");
  String hash=SourceEvidence.digest(bytes);Path target=root.resolve("evidence-"+hash+".json");
  if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(Files.isSymbolicLink(target)||!Arrays.equals(Files.readAllBytes(target),bytes))throw new IOException("informational evidence conflict");return;}
  long count;try(var files=Files.list(root)){count=files.filter(p->p.getFileName().toString().startsWith("evidence-")).count();}if(count>=1000)throw new IOException("informational evidence inventory bound");
  Path temporary=Files.createTempFile(root,"evidence-pending-",".json");try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);try(var directory=FileChannel.open(root,StandardOpenOption.READ)){directory.force(true);}
 }
 public synchronized void retainBlocked(Binding binding,String category)throws IOException {
  valid(binding);if(!Set.of("blocked_pin","unresolved","incomplete","unproven_amendment").contains(category))throw new IOException("invalid blocked category");
  var old=rows.get(binding.roundId());if(old!=null){if(!old.binding().equals(binding))throw new IOException("blocked evidence conflict");return;}
  rows.put(binding.roundId(),new Row(binding,State.blocked,category));persist();
 }
 public synchronized Map<String,AmendmentCommitment> commitments(){return Map.copyOf(amendmentCommitments);}
 public synchronized List<Row> snapshot(){return List.copyOf(ordered());}
 public synchronized String tick()throws IOException {
  for(var row:ordered()) {
   if(row.state()==State.blocked)continue;
   if(row.state()==State.completed){var completedProof=ledger.proof(row.binding());if(completedProof.isEmpty()||!completedProof.get().roundId().equals(row.binding().roundId())||!completedProof.get().bindingDigest().equals(digest(row.binding())))throw new IOException("completed receipt reset/dangling: reconciliation blocked");continue;}
   var proof=ledger.proof(row.binding());
   if(proof.isPresent()) {
    var p=proof.get();if(!p.roundId().equals(row.binding().roundId())||!p.bindingDigest().equals(digest(row.binding())))throw new IOException("receipt/action binding mismatch");
    rows.put(row.binding().roundId(),new Row(row.binding(),p.completed()?State.completed:State.proven_open,""));persist();
    if(!p.completed())return "proven-open";continue;
   }
   if(row.state()==State.submission_uncertain||row.state()==State.proven_open)throw new IOException("uncertain/dangling round: proof only, no replay");
  }
  if(ledger.anyActiveRound())return "active-round";
  for(var row:ordered())if(row.state()==State.queued) {
   if(!verifiedThisProcess.contains(row.binding().roundId()))throw new IOException("queued evidence needs collector revalidation, not deserialized ready trust");
   Binding prepared=ledger.prepare(row.binding());
   if(!prepared.equals(row.binding())){if(!prepared.kind().equals("milestone")||!sameEvidence(prepared,row.binding()))throw new IOException("prepare altered immutable evidence");rows.put(prepared.roundId(),new Row(prepared,State.queued,""));persist();row=new Row(prepared,State.queued,"");}
   try{ledger.validate(row.binding());}catch(IOException invalid){rows.put(row.binding().roundId(),new Row(row.binding(),State.blocked,"exact credential/pin/head validation failed"));persist();return "blocked";}
   rows.put(row.binding().roundId(),new Row(row.binding(),State.submission_uncertain,""));persist();
   ledger.open(row.binding(),120); // Failure intentionally retains durable uncertainty.
   var proof=ledger.proof(row.binding());if(proof.isEmpty())throw new IOException("submission uncertain; authoritative receipt/action proof required");
   if(!proof.get().roundId().equals(row.binding().roundId())||!proof.get().bindingDigest().equals(digest(row.binding())))throw new IOException("receipt/action binding mismatch");
   rows.put(row.binding().roundId(),new Row(row.binding(),proof.get().completed()?State.completed:State.proven_open,""));persist();return "opened";
  }return "idle";
 }
}
