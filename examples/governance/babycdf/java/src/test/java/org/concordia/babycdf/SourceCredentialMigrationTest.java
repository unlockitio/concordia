// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.concordia.babycdf.SourceCatalogueLedgerTest.*;
import static org.concordia.babycdf.SourceCatalogueCodec.*;
class SourceCredentialMigrationTest {
 @TempDir Path directory;
 @Test void legacyIntentNamespaceBlocksWithoutParsingOrSending() throws Exception {
  Files.writeString(directory.resolve("babycdf-source-"+"a".repeat(64)+".intent"),"LEGACY_RAW_SENTINEL");
  var server=new Server();var ledger=new SourceCatalogueLedger(PARTY,PKG,"user",directory.toRealPath(),server);
  var failure=assertThrows(IOException.class,ledger::checkpoint);
  assertFalse(failure.toString().contains("LEGACY_RAW_SENTINEL"));assertEquals(0,server.sends);
 }
 @Test void legacyCheckpointWithoutSchemaBlocksWithoutCreate() throws Exception {
  var server=new Server();var value=checkpoint(0,"",false,0,"",0).toBuilder();
  for(int i=value.getFieldsCount()-1;i>=0;i--)if(value.getFields(i).getLabel().equals("schema"))value.removeFields(i);
  server.active.add(created("legacy","SourceCheckpoint",value.build()));
  var ledger=new SourceCatalogueLedger(PARTY,PKG,"user",directory.toRealPath(),server);
  assertThrows(IOException.class,ledger::checkpoint);assertEquals(0,server.sends);
 }
 @Test void transientBodyAbsentFromWireIntentAndLogs() throws Exception {
  String sentinel="RAW_BODY_SENTINEL_9e18688a\r\n";
  var row=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("number",184)
   .put("title","proposal").put("body",sentinel).put("state","open")
   .put("html_url","https://github.com/canton-foundation/canton-dev-fund/pull/184")
   .put("updated_at","2026-01-01T00:00:00Z").putNull("merged_at");
  var observation=SourceObservation.parse(row,true,java.time.Instant.parse("2026-01-02T00:00:00Z"));
  assertEquals(SourceObservation.hash(sentinel),observation.bodySha256());
  assertFalse(observation.toString().contains(sentinel));
  var server=new Server();var ledger=new SourceCatalogueLedger(PARTY,PKG,"user",directory.toRealPath(),server);
  var logs=new ArrayList<String>();
  new JavaSourceIngestion(ledger,c->new JavaSourceIngestion.Page(List.of(observation),"",true),logs::add).cycle();
  assertFalse(server.last.toString().contains("RAW_BODY_SENTINEL"));
  assertFalse(logs.toString().contains("RAW_BODY_SENTINEL"));
  try(var paths=Files.list(directory)) {for(var path:paths.toList())assertFalse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8).contains("RAW_BODY_SENTINEL"));}
 }
}
