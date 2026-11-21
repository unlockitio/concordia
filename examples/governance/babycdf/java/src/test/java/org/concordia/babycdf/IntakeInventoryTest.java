package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class IntakeInventoryTest {
 @Test void fixtureActuallyTraversesPagesDeduplicatesAndExcludesPrIssues() {
   var report=IntakeInventory.fixture().discover();
   assertEquals(2,report.pulls().observedUniqueCount()); assertEquals(2,report.pulls().requests());
   assertEquals(2,report.issues().observedUniqueCount()); assertEquals(Set.of(540L),report.issues().milestoneIssueNumbers());
   assertTrue(report.complete());
   var text=IntakeInventory.render("fixture",report);
   assertTrue(text.contains("SYNTHETIC_OFFLINE_FIXTURE_NOT_GITHUB"));
   assertTrue(text.contains("2026-01-01T00:00:00Z")); assertTrue(text.contains("Exactly 2 curated"));
   assertEquals(2,IntakeSources.sources().size());
   assertTrue(text.contains("pinned bytes are verified by Intake when the request is processed"));
   assertFalse(text.contains("independently verified"));
 }
 @Test void partialFailureNeverClaimsCompleteOrRepositoryTotal() {
   var inventory=new DiscoveryReport.Inventory(Set.of(),Set.of(),1,DiscoveryReport.StopReason.FORBIDDEN);
   var text=IntakeInventory.render("live",new DiscoveryReport(Instant.EPOCH,inventory,inventory));
   assertTrue(text.contains("complete=false")); assertTrue(text.contains("FORBIDDEN"));
   assertTrue(text.contains("partial counts are not repository totals"));
 }
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
 @Test void actualStartupWritesPrivateExactReportAndReplacesWithoutTemps() throws Exception {
   var journal=temp.toRealPath();
   String expected=IntakeInventory.observe("fixture");
   IntakeInventory.startup("fixture",journal);
   var report=journal.resolve("discovery-inventory.txt");
   assertEquals(expected,java.nio.file.Files.readString(report));
   assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),java.nio.file.Files.getPosixFilePermissions(report));
   IntakeInventory.startup("fixture",journal);
   assertEquals(expected,java.nio.file.Files.readString(report));
   try(var files=java.nio.file.Files.list(journal)) { assertEquals(1,files.count()); }
 }
 @Test void badJournalFailsBeforeObservationOrWrites() throws Exception {
   var root=temp.toRealPath(); var link=root.resolve("alias"); java.nio.file.Files.createSymbolicLink(link,root);
   assertThrows(IllegalArgumentException.class,()->IntakeInventory.startup("fixture",link));
   assertThrows(IllegalArgumentException.class,()->IntakeInventory.startup("fixture",root.resolve("missing")));
   try(var files=java.nio.file.Files.list(root)) { assertEquals(1,files.count()); }
 }
 @Test void startupBudgetLeavesRoomForBoundedStepWait() {
   assertEquals(java.time.Duration.ofSeconds(30),IntakeInventory.startupLimits().totalTime());
   assertEquals(java.time.Duration.ofSeconds(5),IntakeInventory.startupLimits().requestTime());
 }
 @Test void invalidModeNeverFetches() { assertThrows(IllegalArgumentException.class,()->IntakeInventory.observe("automatic")); }
}
