// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.io.IOException;
/** Only the exact parsed schema can produce this capability; titles are irrelevant. */
public final class VerifiedAmendment {
 private final String repo;private final long evidenceId,original,amending;private final String originalPin,amendingPin;
 private VerifiedAmendment(String repo,long evidenceId,long original,String originalPin,long amending,String amendingPin){this.repo=repo;this.evidenceId=evidenceId;this.original=original;this.originalPin=originalPin;this.amending=amending;this.amendingPin=amendingPin;}
 public String repo(){return repo;}public long evidenceId(){return evidenceId;}public long original(){return original;}public long amending(){return amending;}
 public String originalPin(){return originalPin;}public String amendingPin(){return amendingPin;}
 public static List<VerifiedAmendment> parse(byte[] bytes,Map<Long,SourceEvidence.Proposal> proposals,Set<Long> acceptedSourceIds)throws IOException {
  try {
   var json=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);var root=json.readTree(bytes);
   exact(root,Set.of("scheme","amendments"));if(!root.path("scheme").asText().equals("babycdf-amendments/v1")||!root.path("amendments").isArray())throw new IOException();
   var result=new ArrayList<VerifiedAmendment>();var seen=new HashSet<Long>();
   for(var entry:root.path("amendments")) {
    exact(entry,Set.of("repo","evidenceId","original","amending","declaration"));exact(entry.path("original"),Set.of("number","pin"));exact(entry.path("amending"),Set.of("number","pin"));
    long id=integer(entry,"evidenceId"),original=integer(entry.path("original"),"number"),amending=integer(entry.path("amending"),"number");
    String repo=text(entry,"repo"),op=text(entry.path("original"),"pin"),ap=text(entry.path("amending"),"pin");
    if(!repo.equals(SourceEvidenceCollector.REPO)||original==amending||!seen.add(id)||!acceptedSourceIds.contains(id)
      ||!text(entry,"declaration").equals("amends:"+original)||!proposals.containsKey(original)||!proposals.containsKey(amending)
      ||!validReady(proposals.get(original))||!validReady(proposals.get(amending))||!op.equals(pin(proposals.get(original)))||!ap.equals(pin(proposals.get(amending))))throw new IOException();
    result.add(new VerifiedAmendment(repo,id,original,op,amending,ap));
   }return List.copyOf(result);
  }catch(Exception bad){throw new IOException("invalid checked amendment manifest");}
 }
 private static boolean validReady(SourceEvidence.Proposal p){
  return p.eligible()&&p.status().equals("ready")&&p.head().matches("[0-9a-f]{40}")&&!p.files().isEmpty()
    &&p.files().stream().map(SourceEvidence.FilePin::path).distinct().count()==p.files().size()
    &&p.files().stream().allMatch(f->!f.path().isBlank()&&f.blob().matches("[0-9a-f]{40}")&&Set.of("added","modified","renamed","changed","copied").contains(f.status()));
 }
 public static String pin(SourceEvidence.Proposal p){try{return SourceObservation.hash(new ObjectMapper().writeValueAsString(List.of(p.head(),p.files().stream().map(f->List.of(f.path(),f.status(),f.blob(),f.previousPath())).toList())));}catch(Exception e){throw new IllegalArgumentException("invalid proposal pin");}}
 private static void exact(JsonNode n,Set<String> names)throws IOException {if(!n.isObject())throw new IOException();var actual=new HashSet<String>();n.fieldNames().forEachRemaining(actual::add);if(!actual.equals(names))throw new IOException();}
 private static String text(JsonNode n,String key)throws IOException {if(!n.path(key).isTextual())throw new IOException();return n.path(key).textValue();}
 private static long integer(JsonNode n,String key)throws IOException {var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw new IOException();return v.longValue();}
}
