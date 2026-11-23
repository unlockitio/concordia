// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** End-to-end read-only evidence collector. Never submits or opens an agenda. */
public final class SourceEvidenceCollector {
 public static final String REPO="canton-foundation/canton-dev-fund";
 private static final String ROOT="https://api.github.com/repos/"+REPO;
 private final SourceEvidenceFetch fetch;
 private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 public record Capture(JsonNode data,Instant retrievedAt,boolean complete,String status) {}
 public record Batch(List<SourceEvidence.Entry> entries,List<String> gaps,Instant replayAsOf,Instant retrievedAt) {
  public Batch {entries=List.copyOf(entries);gaps=List.copyOf(gaps);}
  public byte[] manifest(){return SourceEvidence.manifest(replayAsOf,retrievedAt,entries);}
 }
 public SourceEvidenceCollector(SourceEvidenceFetch fetch){this.fetch=Objects.requireNonNull(fetch);}
 /** Runtime credential provisioning is explicit and uses the existing file-backed helper. */
 public static SourceEvidenceCollector configured(Map<String,String> env,Clock clock)throws IOException {
  return new SourceEvidenceCollector(new SourceEvidenceFetch(IntakeGitHub.configured(env).evidenceTransport(),clock));
 }
 private Capture object(String path)throws IOException {
  var pages=fetch.pages(ROOT+path,1,1,Duration.ofSeconds(10));
  if(pages.pages().isEmpty())return new Capture(json.nullNode(),Instant.EPOCH,false,pages.status());
  var p=pages.pages().getFirst();return new Capture(json.readTree(p.body()),p.retrievedAt(),pages.status().equals("complete"),pages.status());
 }
 private Capture array(String path)throws IOException {
  var pages=fetch.pages(ROOT+path,20,20,Duration.ofSeconds(60));var rows=json.createArrayNode();Instant retrieved=Instant.EPOCH;
  for(var p:pages.pages()){var data=json.readTree(p.body());if(!data.isArray())throw new IOException("invalid evidence array");data.forEach(rows::add);retrieved=p.retrievedAt();}
  return new Capture(rows,retrieved,pages.status().equals("complete"),pages.status());
 }
 public Batch proposal(long number,Instant asOf)throws IOException {
  var metadata=object("/pulls/"+positive(number));var files=array("/pulls/"+number+"/files?per_page=100");
  String head=metadata.data().path("head").path("sha").asText();
  Capture tree=head.matches("[0-9a-f]{40}")?object("/git/trees/"+head+"?recursive=1"):new Capture(json.nullNode(),Instant.EPOCH,false,"missing_head");
  var reread=fetch.fresh(ROOT+"/pulls/"+number);
  var current=json.readTree(reread.body());
  if(!head.equals(current.path("head").path("sha").asText()))
    return proposal(number,asOf,new Capture(current,reread.retrievedAt(),false,"head_changed"),new Capture(files.data(),files.retrievedAt(),false,"head_changed"),tree);
  return proposal(number,asOf,new Capture(current,reread.retrievedAt(),metadata.complete(),"uncached_head_confirmed"),files,tree);
 }
 /** Trusted offline Capture seam; caller establishes capture provenance/completeness.
  * Failed/partial inputs are nonauthoritative and cannot produce ready pins. */
 public Batch proposal(long number,Instant asOf,Capture metadata,Capture files,Capture tree) {
  var gaps=new ArrayList<String>();var rows=new ArrayList<SourceEvidence.Entry>();
  String head=metadata.data().path("head").path("sha").asText();var membership=new TreeMap<String,String>();
  boolean complete=metadata.complete()&&files.complete()&&tree.complete()&&!tree.data().path("truncated").asBoolean(true);
  for(var node:tree.data().path("tree"))if(node.path("type").asText().equals("blob"))membership.put(node.path("path").asText(),node.path("sha").asText());
  var proposal=SourceEvidence.proposal(number,head,files.data(),membership,complete);
  if(!complete)gaps.add("incomplete metadata/files/head tree; no executable pin");
  Instant at=time(metadata.data(),"created_at");
  if(!at.isAfter(asOf)) {
   var payload=new TreeMap<String,Object>();payload.put("eligible",proposal.eligible());payload.put("pin_status",proposal.status());payload.put("head",head);
   payload.put("files",proposal.files().stream().map(f->List.of(f.path(),f.status(),f.blob(),f.previousPath())).toList());
   payload.put("vote_result","unresolved");payload.put("amendment","unproven");
   payload.put("provider_state",metadata.data().path("state").asText());payload.put("merged_is_vote",false);
   payload.put("source_retrievals",List.of(List.of("metadata",metadata.retrievedAt().toString(),metadata.status()),List.of("files",files.retrievedAt().toString(),files.status()),List.of("tree",tree.retrievedAt().toString(),tree.status())));
   rows.add(new SourceEvidence.Entry(at,"proposal",REPO,number,number,REPO+"/pull/"+number,"",payload));
  }
  return new Batch(rows,gaps,asOf,max(metadata.retrievedAt(),files.retrievedAt(),tree.retrievedAt()));
 }
 public Batch milestone(long number,Instant asOf)throws IOException {
  var issue=object("/issues/"+positive(number));var comments=array("/issues/"+number+"/comments?per_page=100");var events=array("/issues/"+number+"/events?per_page=100");
  return milestone(number,asOf,issue,comments,events,Map.of(),Map.of());
 }
 public Batch milestone(long number,Instant asOf,Capture issue,Capture comments,Capture events,
       Map<String,SourceEvidence.CommitProof> commits,Map<String,SourceEvidence.CommitProof> historicalBranches) {
  var gaps=new ArrayList<String>();var rows=new ArrayList<SourceEvidence.Entry>();var parsed=new ArrayList<SourceEvidence.Comment>();
  var byId=new HashMap<Long,JsonNode>();
  for(var c:comments.data()) {long id=c.path("id").asLong();if(id<=0||byId.put(id,c)!=null)throw new IllegalArgumentException("duplicate/invalid evidence source ID");
   if(!commentSource(c,number)) {gaps.add("foreign or malformed comment source");continue;}
   parsed.add(new SourceEvidence.Comment(id,c.path("body").asText(),time(c,"created_at"),time(c,"updated_at"),false));}
  var labels=new ArrayList<SourceEvidence.LabelEvent>();for(var e:events.data())if(e.path("event").asText().equals("labeled"))labels.add(new SourceEvidence.LabelEvent(e.path("id").asLong(),e.path("label").path("name").asText(),time(e,"created_at")));
  boolean complete=issue.complete()&&comments.complete()&&events.complete();
  if(!complete)gaps.add("partial issue/comments/events; trigger enumeration incomplete");
  if(!issue.complete() || !comments.complete())return new Batch(List.of(),gaps,asOf,max(issue.retrievedAt(),comments.retrievedAt(),events.retrievedAt()));
  var triggers=SourceEvidence.triggers(REPO,number,issue.data().path("body").asText(),time(issue.data(),"updated_at"),parsed,labels,asOf);
  for(var trigger:triggers) {
   var payload=new TreeMap<String,Object>();payload.put("trigger_status",trigger.status());payload.put("enumeration_complete",complete);
   payload.put("issue_digest",trigger.bodyDigest());payload.put("source_comment_id",trigger.id());
   var delivery=complete?SourceEvidence.delivery(trigger,parsed,commits,historicalBranches)
     :new SourceEvidence.Delivery("",trigger.bodyDigest(),"blocked_pin","incomplete issue/comments/events capture");
   payload.put("pin_status",delivery.status());payload.put("delivery_commit",delivery.commit());payload.put("pin_reason",delivery.reason());
   JsonNode command=byId.get(trigger.id());boolean editedCommand=command!=null&&!time(command,"created_at").equals(time(command,"updated_at"));
   payload.put("command_uncertainty",editedCommand?"edited_command_history_unknown":"observed_command_not_authorized");
   payload.put("body_uncertainty",trigger.bodyDigest().isEmpty()?"historical_body_unknown":"observed_body");
   payload.put("author_login",command==null?"":command.path("user").path("login").asText());payload.put("author_authorized",false);
   payload.put("source_retrievals",List.of(List.of("issue",issue.retrievedAt().toString(),issue.status()),List.of("comments",comments.retrievedAt().toString(),comments.status()),List.of("events",events.retrievedAt().toString(),events.status())));
   var result=editedCommand?unresolved():trustedResult(trigger,triggers,byId,asOf,comments.complete());
   payload.put("result_uncertainty",editedCommand?"edited_trigger_unknown":"interval_inferred_not_explicit_link");
   payload.put("result",List.of(result.status(),result.favor(),result.against(),result.abstain(),result.notVoted(),result.percent(),result.at().toString(),result.sourceId()));
   rows.add(new SourceEvidence.Entry(trigger.at(),trigger.type(),REPO,number,trigger.id(),trigger.roundId(),"",payload));
  }
  return new Batch(rows,gaps,asOf,max(issue.retrievedAt(),comments.retrievedAt(),events.retrievedAt()));
 }
 private static boolean commentSource(JsonNode c,long number) {
  return c.path("issue_url").asText().equals(ROOT+"/issues/"+number)
    && c.path("html_url").asText().equals("https://github.com/"+REPO+"/issues/"+number+"#issuecomment-"+c.path("id").asLong());
 }
 private static boolean trustedBot(JsonNode c) {
  // GitVote app identity, not body text or human-chosen display name.
  return c.path("user").path("type").asText().equals("Bot") && c.path("user").path("login").asText().equals("git-vote[bot]")
   && c.path("user").path("id").asLong()==106591916L;
 }
 private static SourceEvidence.Result unresolved(){return new SourceEvidence.Result("unresolved",0,0,0,0,"",Instant.EPOCH,0);}
 private static SourceEvidence.Result trustedResult(SourceEvidence.Trigger trigger,List<SourceEvidence.Trigger> triggers,Map<Long,JsonNode> comments,Instant asOf,boolean complete) {
  if(!complete)return unresolved();
  JsonNode command=comments.get(trigger.id());
  if(command!=null&&!time(command,"created_at").equals(time(command,"updated_at")))return unresolved();
  if(comments.values().stream().anyMatch(c->!time(c,"created_at").isAfter(asOf)&&!time(c,"created_at").equals(time(c,"updated_at"))
      &&Math.abs(Duration.between(trigger.at(),time(c,"created_at")).getSeconds())<=300))return unresolved();
  Instant next=triggers.stream().filter(t->t.at().isAfter(trigger.at())).map(SourceEvidence.Trigger::at).min(Comparator.naturalOrder()).orElse(Instant.MAX);
  var interval=comments.values().stream().filter(c->trustedBot(c)&&!time(c,"created_at").isBefore(trigger.at())&&time(c,"created_at").isBefore(next)&&!time(c,"created_at").isAfter(asOf))
   .sorted(Comparator.comparing((JsonNode c)->time(c,"created_at")).thenComparingLong(c->c.path("id").asLong())).toList();
  var created=interval.stream().filter(c->c.path("body").asText().startsWith("## Vote created")
    && c.path("body").asText().contains("(#"+trigger.number()+")")).toList();
  if(created.size()!=1 || !time(created.getFirst(),"created_at").equals(time(created.getFirst(),"updated_at")))return unresolved();
  // A unique, immediately following app creation anchors the interval. Long gaps or
  // concurrent commands are ambiguous; never associate solely by result title.
  if(Duration.between(trigger.at(),time(created.getFirst(),"created_at")).compareTo(Duration.ofMinutes(5))>0)return unresolved();
  if(triggers.stream().anyMatch(t->t.id()!=trigger.id()&&t.at().equals(trigger.at())))return unresolved();
  var closes=interval.stream().filter(c->c.path("body").asText().startsWith("## Vote closed")).toList();
  if(closes.isEmpty())return new SourceEvidence.Result("open",0,0,0,0,"",time(created.getFirst(),"created_at"),created.getFirst().path("id").asLong());
  if(closes.size()!=1 || !time(closes.getFirst(),"created_at").equals(time(closes.getFirst(),"updated_at")))return unresolved();
  var close=closes.getFirst();String body=close.path("body").asText();
  var counts=Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*$").matcher(body);
  var percent=Pattern.compile("In favor: `([0-9.]+)%`").matcher(body);
  if(!counts.find()||!percent.find())return unresolved();
  String status=body.contains("**passed**")?"passed":body.toLowerCase(Locale.ROOT).contains("not pass")||body.contains("**failed**")?"failed":"unresolved";
  try{return SourceEvidence.correlate(trigger.id(),List.of(new SourceEvidence.ResultEvidence(trigger.id(),new SourceEvidence.Result(status,Long.parseLong(counts.group(1)),Long.parseLong(counts.group(2)),Long.parseLong(counts.group(3)),Long.parseLong(counts.group(4)),percent.group(1),time(close,"created_at"),close.path("id").asLong()))));}
  catch(RuntimeException malformed){return unresolved();}
 }
 /** Trusted-input legacy test value only; runtime amendment output requires VerifiedAmendment. */
 record Amendment(long proposal,long original,String manifestDigest,long evidenceId,String declaration) {}
 public static SourceEvidence.Entry amendmentEntry(VerifiedAmendment verified,Instant at) {
  return new SourceEvidence.Entry(at,"proposal",verified.repo(),verified.amending(),verified.evidenceId(),verified.repo()+"/pull/"+verified.amending(),"",
   Map.of("amends_original",verified.original(),"original_pin",verified.originalPin(),"amending_pin",verified.amendingPin(),"source_id",verified.evidenceId()));
 }
 static SourceEvidence.Entry amendmentEntry(Amendment amendment,Map<Long,SourceEvidence.Proposal> proposals,byte[] checkedManifest,Instant at) {
  if(!amendment(amendment,proposals,checkedManifest))throw new IllegalArgumentException("unverified amendment manifest");
  return new SourceEvidence.Entry(at,"proposal",REPO,amendment.proposal(),amendment.evidenceId(),REPO+"/pull/"+amendment.proposal(),"",
    Map.of("amends_original",amendment.original(),"checked_manifest_sha256",amendment.manifestDigest(),"source_id",amendment.evidenceId()));
 }
 static boolean amendment(Amendment a,Map<Long,SourceEvidence.Proposal> proposals,byte[] checkedManifest) {
  return a.proposal()>0&&a.original()>0&&a.proposal()!=a.original()&&a.evidenceId()>0&&a.declaration().equals("amends:"+a.original())
   &&proposals.containsKey(a.proposal())&&proposals.containsKey(a.original())&&proposals.get(a.proposal()).eligible()&&proposals.get(a.original()).eligible()
   &&a.manifestDigest().equals(SourceEvidence.digest(checkedManifest));
 }
 public static List<SourceEvidence.Entry> appendCorrection(List<SourceEvidence.Entry> prior,SourceEvidence.Entry correction,String expectedPreviousDigest) {
  if(!correction.type().equals("correction")||!correction.supersedes().equals(expectedPreviousDigest)||!expectedPreviousDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid correction identity");
  var previous=prior.stream().filter(e->e.roundId().equals(correction.roundId())).max(Comparator.comparing(SourceEvidence.Entry::at).thenComparingLong(SourceEvidence.Entry::numericId)).orElseThrow();
  String actual=entryDigest(previous);
  if(!actual.equals(expectedPreviousDigest))throw new IllegalArgumentException("correction supersedes digest mismatch");
  if(prior.stream().anyMatch(e->e.type().equals("correction")&&e.supersedes().equals(expectedPreviousDigest)))throw new IllegalArgumentException("correction fork");
  if(!previous.repo().equals(correction.repo())||previous.number()!=correction.number()||correction.at().isBefore(previous.at()))throw new IllegalArgumentException("correction changes round identity");
  var out=new ArrayList<>(prior);out.add(correction);return List.copyOf(out);
 }
 public static String entryDigest(SourceEvidence.Entry entry) {return SourceEvidence.digest(SourceEvidence.manifest(Instant.EPOCH,Instant.EPOCH,List.of(entry)));}
 private static long positive(long n){if(n<1)throw new IllegalArgumentException("invalid source number");return n;}
 private static Instant time(JsonNode n,String field){try{return Instant.parse(n.path(field).asText());}catch(RuntimeException bad){throw new IllegalArgumentException("missing evidence timestamp");}}
 private static Instant max(Instant... times){return Arrays.stream(times).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);}
}
