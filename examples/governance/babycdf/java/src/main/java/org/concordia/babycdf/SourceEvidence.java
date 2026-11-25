// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;

/** Pure evidence enrichment. No ledger, queue, opener or voting authority. */
public final class SourceEvidence {
 private SourceEvidence() {}
 public record FilePin(String path,String status,String blob,String previousPath) {}
 public record Proposal(long number,String head,List<FilePin> files,boolean eligible,String status) {
  public Proposal { files=List.copyOf(files); }
 }
 public record Trigger(String roundId,String repo,long number,long id,Instant at,String type,String bodyDigest,String status) {}
 public record Delivery(String commit,String issueDigest,String status,String reason) {}
 public record Result(String status,long favor,long against,long abstain,long notVoted,String percent,Instant at,long sourceId) {}
 public record Comment(long id,String body,Instant createdAt,Instant updatedAt,boolean deleted) {}
 public record LabelEvent(long id,String label,Instant at) {}
 private static final Pattern SHA=Pattern.compile("(?i)(?<![a-z0-9])[0-9a-f]{40}(?![a-z0-9])");
 private static boolean eligiblePath(String p) {return p.startsWith("proposals/") || p.startsWith("rfps/");}
 /** Trusted-input pure seam: completeness/tree provenance must be established by collector. */
 public static Proposal proposal(long number,String head,JsonNode files,Map<String,String> headTree,boolean complete) {
  if(!head.matches("[0-9a-f]{40}") || !complete || !files.isArray())return new Proposal(number,head,List.of(),false,"partial_files");
  var pins=new ArrayList<FilePin>();boolean eligible=false;String status="ready";
  for(var file:files) {
   String path=file.path("filename").asText(),kind=file.path("status").asText(),blob=file.path("sha").asText(),previous=file.path("previous_filename").asText();
   eligible |= eligiblePath(path) || (kind.equals("renamed") && eligiblePath(previous));
   if(!Set.of("added","modified","renamed","removed","changed","copied").contains(kind) || path.isBlank())status="blocked_pin";
   // Removed files have no head membership. Require a future explicit base-tree proof;
   // never pretend their old blob belongs to the head tree.
   if(kind.equals("removed") || !blob.matches("[0-9a-f]{40}") || !blob.equals(headTree.get(path)))status="blocked_pin";
   pins.add(new FilePin(path,kind,blob,previous));
  }
  pins.sort(Comparator.comparing(FilePin::path).thenComparing(FilePin::status).thenComparing(FilePin::blob));
  if(pins.stream().map(FilePin::path).distinct().count()!=pins.size())status="blocked_pin";
  return new Proposal(number,head,pins,eligible,eligible?status:"ineligible");
 }
 /** Standalone command lines only, outside quoted lines and fenced code. */
 public static boolean voteCommand(String body) {return command(body,false);}
 public static boolean milestoneVoteCommand(String body) {return command(body,true);}
 private static boolean command(String body,boolean milestone) {
  char fence=0;int length=0;
  for(String line:body.split("\\R",-1)) {
   // Commands in nested quotes/list containers/indented code are never standalone.
   if(line.startsWith("    ")||line.startsWith("\t"))continue;
   String s=line.stripLeading();if(s.startsWith(">")||s.matches("(?:[-+*]|[0-9]+[.)])\\s+.*"))continue;
   int run=0;char marker=s.isEmpty()?0:s.charAt(0);
   if(marker=='`'||marker=='~'){while(run<s.length()&&s.charAt(run)==marker)run++;}
   if(fence!=0){if(marker==fence&&run>=length&&s.substring(run).isBlank()){fence=0;length=0;}continue;}
   if(run>=3){fence=marker;length=run;continue;}
   if(s.stripTrailing().equals("/vote")||(milestone&&s.stripTrailing().equals("/git-vote")))return true;
  }return false;
 }
 public static List<Trigger> triggers(String repo,long issue,String currentBody,Instant issueUpdated,List<Comment> comments,List<LabelEvent> labels,Instant asOf) {
   return triggers(repo,issue,currentBody,issueUpdated,comments,labels,asOf,false);
  }
  public static List<Trigger> milestoneTriggers(String repo,long issue,String currentBody,Instant issueUpdated,List<Comment> comments,List<LabelEvent> labels,Instant asOf) {
   return triggers(repo,issue,currentBody,issueUpdated,comments,labels,asOf,true);
  }
  private static List<Trigger> triggers(String repo,long issue,String currentBody,Instant issueUpdated,List<Comment> comments,List<LabelEvent> labels,Instant asOf,boolean milestone) {
  var out=new ArrayList<Trigger>();var ids=new HashSet<Long>();
  for(var c:comments) {
   if(!ids.add(c.id()))throw new IllegalArgumentException("duplicate comment identity");
   if(c.createdAt().isAfter(asOf) || c.deleted() || !(milestone?milestoneVoteCommand(c.body()):voteCommand(c.body())))continue;
   String status=c.updatedAt().isAfter(c.createdAt())?"historical_command_unknown":"observed_command";
   String digest=issueUpdated.isAfter(c.createdAt())||c.updatedAt().isAfter(c.createdAt())?"":SourceObservation.hash(currentBody);
   if(digest.isEmpty()&&!c.updatedAt().isAfter(c.createdAt()))status="historical_issue_unknown";
   out.add(new Trigger(repo+"/issues/"+issue+"/comments/"+c.id(),repo,issue,c.id(),c.createdAt(),"command",digest,status));
  }
  // Label fallback only when there is no command evidence at all. Deleted or edited
  // commands cannot justify inventing a label-triggered replacement.
  boolean anyCommand=comments.stream().anyMatch(c->voteCommand(c.body())||c.deleted()||c.updatedAt().isAfter(c.createdAt()));
  if(!anyCommand) {
   var candidates=labels.stream().filter(e->e.label().equalsIgnoreCase("vote") && !e.at().isAfter(asOf)).toList();
   if(candidates.size()==1) {var e=candidates.getFirst();String digest=issueUpdated.isAfter(e.at())?"":SourceObservation.hash(currentBody);
    out.add(new Trigger(repo+"/issues/"+issue+"/labels/"+e.id(),repo,issue,e.id(),e.at(),"label",digest,digest.isEmpty()?"historical_issue_unknown":"label_fallback"));}
  }
  out.sort(Comparator.comparing(Trigger::at).thenComparing(Trigger::repo).thenComparingLong(Trigger::number).thenComparingLong(Trigger::id));return List.copyOf(out);
 }
 public record CommitProof(String sha,Instant committerAt,boolean historicallyReachable) {}
 public static Delivery delivery(Trigger trigger,List<Comment> comments,Map<String,CommitProof> commits,Map<String,CommitProof> historicalBranches) {
  if(trigger.bodyDigest().isEmpty())return new Delivery("","","blocked_pin","trigger-time issue body unavailable");
  var preceding=comments.stream().filter(c->!c.deleted() && !c.createdAt().isAfter(trigger.at()) && !c.updatedAt().isAfter(trigger.at()))
   .sorted(Comparator.comparing(Comment::createdAt).thenComparingLong(Comment::id).reversed()).toList();
  for(var c:preceding) {var matcher=SHA.matcher(c.body());var found=new LinkedHashSet<String>();while(matcher.find())found.add(matcher.group().toLowerCase(Locale.ROOT));
   if(found.size()>1)return new Delivery("",trigger.bodyDigest(),"blocked_pin","ambiguous explicit commits");
   if(found.size()==1){String sha=found.iterator().next();var proof=commits.get(sha);
    if(proof!=null && proof.sha().equals(sha) && !proof.committerAt().isAfter(trigger.at()))return new Delivery(sha,trigger.bodyDigest(),"pinned","explicit full SHA");
    return new Delivery("",trigger.bodyDigest(),"blocked_pin","explicit commit proof unavailable or after trigger");}
  }
  // Reachability plus a backdated committer timestamp is NOT historical branch proof.
  // Branch fallback is disabled: only an explicit preceding full SHA is eligible.

  return new Delivery("",trigger.bodyDigest(),"blocked_pin","no unambiguous historical delivery proof; current head forbidden");
 }
 /** Results must carry explicit trigger identity; parser input is provider evidence, not title inference. */
 public record ResultEvidence(long triggerId,Result result) {}
 /** Trusted-input text parser ONLY: does not establish bot identity or authoritative correlation. */
 public static List<ResultEvidence> gitVoteResults(List<Comment> comments) {
  var sorted=comments.stream().sorted(Comparator.comparing(Comment::createdAt).thenComparingLong(Comment::id)).toList();
  var out=new ArrayList<ResultEvidence>();Long current=null;boolean ambiguous=false;
  Pattern counts=Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*$");
  for(var c:sorted) {
   if(c.deleted() || c.updatedAt().isAfter(c.createdAt()))continue;
   if(voteCommand(c.body())) {if(current!=null)ambiguous=true;current=c.id();continue;}
   if(current==null)continue;
   if(c.body().startsWith("## Vote closed")) {
    var m=counts.matcher(c.body());String status=c.body().contains("**passed**")?"passed":c.body().toLowerCase(Locale.ROOT).contains("not pass")||c.body().contains("**failed**")?"failed":"unresolved";
    var percent=Pattern.compile("In favor: `([0-9.]+)%`").matcher(c.body());String share=percent.find()?percent.group(1):"";
    if(m.find()&&!ambiguous)out.add(new ResultEvidence(current,new Result(status,Long.parseLong(m.group(1)),Long.parseLong(m.group(2)),Long.parseLong(m.group(3)),Long.parseLong(m.group(4)),share,c.createdAt(),c.id())));
    current=null;ambiguous=false;
   }
  }return List.copyOf(out);
 }
 public static Result correlate(long triggerId,List<ResultEvidence> evidence) {
  var matches=evidence.stream().filter(e->e.triggerId()==triggerId).map(ResultEvidence::result).distinct().toList();
  if(matches.size()!=1)return new Result("unresolved",0,0,0,0,"",Instant.EPOCH,0);
  var r=matches.getFirst();if(!Set.of("passed","failed","open").contains(r.status()) || r.favor()<0 || r.against()<0 || r.abstain()<0 || r.notVoted()<0
   || (!r.percent().isEmpty() && !r.percent().matches("(?:100|[0-9]{1,2})(?:\\.[0-9]+)?")))return new Result("unresolved",0,0,0,0,"",Instant.EPOCH,0);
  return r;
 }
 public record Entry(Instant at,String type,String repo,long number,long numericId,String roundId,String supersedes,Map<String,Object> payload) {
  public Entry {payload=Collections.unmodifiableMap(new TreeMap<>(payload));}
 }
 private static int precedence(String type){return switch(type){case "proposal"->0;case "command"->1;case "label"->2;case "delivery"->3;case "result"->4;case "correction"->5;default->6;};}
 public static byte[] manifest(Instant asOf,Instant retrievedAt,List<Entry> entries) {
  try {var sorted=new ArrayList<>(entries);sorted.sort(Comparator.comparing(Entry::at).thenComparingInt(e->precedence(e.type())).thenComparing(Entry::repo)
    .thenComparingLong(Entry::number).thenComparingLong(Entry::numericId).thenComparing(Entry::roundId).thenComparing(Entry::supersedes).thenComparing(e->new ObjectMapper().valueToTree(e.payload()).toString()));
   var mapper=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
   var rows=new ArrayList<Object>();for(var e:sorted)rows.add(List.of(e.at().toString(),e.type(),e.repo(),e.number(),e.numericId(),e.roundId(),e.supersedes(),e.payload()));
   return mapper.writeValueAsBytes(List.of("babycdf-source-evidence/v1",asOf.toString(),retrievedAt.toString(),rows));
  }catch(Exception e){throw new IllegalArgumentException("invalid evidence manifest");}
 }
 public static String digest(byte[] manifest){return SourceObservation.hash(new String(manifest,StandardCharsets.UTF_8));}
}
