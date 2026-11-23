package org.concordia.babycdf;
import com.fasterxml.jackson.databind.node.ObjectNode;import java.nio.file.*;import java.io.IOException;import java.util.*;
/** Publish text successfully before advancing the existing merged preview state. */
final class IntegratedReviewOutput {
 @FunctionalInterface interface Sink {void emit(ObjectNode result)throws Exception;}
 private final Path path;private final Set<String> emitted;
 IntegratedReviewOutput(Path path)throws IOException {
  this.path=path;if(path!=null&&(!path.toAbsolutePath().normalize().getParent().equals(Path.of(".").toRealPath())||Files.isSymbolicLink(path)))throw new IOException("state file must be direct local file");
  emitted=path!=null&&Files.exists(path)?Advisory.loadState(path):new HashSet<>();
 }
 static String identity(ObjectNode result)throws IOException {
  var stable=Advisory.JSON.createObjectNode();for(String name:List.of("status","reason","rule_id","rule_version","rule_document_sha256","rule_ref"))if(result.has(name))stable.set(name,result.get(name));var reviews=stable.putArray("reviews");
  for(var item:result.path("reviews")){var row=reviews.addObject();for(String name:List.of("round_id","number","url","revision","evidence_identity","rule_version_evaluated","rule_document_sha256","pinned","status"))if(item.has(name))row.set(name,item.get(name));row.set("findings",item.path("findings"));
   // Fresh clause diagnostics versus unavailable_cached are not assessment identity.
   if(item.has("supplemental_assessment")){row.set("assessment",item.path("supplemental_assessment").path("exact"));}
  }
  return SourceObservation.hash(Advisory.JSON.writeValueAsString(stable));
 }
 boolean publish(ObjectNode result,Sink sink)throws Exception {
  String identity=identity(result);if(emitted.contains(identity))return false;
  var staged=new HashSet<>(emitted);staged.add(identity);sink.emit(result);
  if(path!=null)Advisory.saveState(path,staged);emitted.clear();emitted.addAll(staged);return true;
 }
}
