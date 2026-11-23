package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class IntegratedStartupContractTest {
 @Test void oneOpenerOneReadonlyReviewerNoBallotAuthority()throws Exception {
  String entry=Files.readString(Path.of("../demo/compose/agents/entrypoint.sh"));assertTrue(entry.contains("Intake --integrated-demo"));assertFalse(entry.contains("Intake --offline-fixture"));assertTrue(entry.contains("EVIDENCE_FLAGS='--integrated-agenda'"));
  String identity=Files.readString(Path.of("../demo/daml/Cap/Examples/BabyCdf/IdentityDemo.daml"));assertTrue(identity.contains("intakeRights == [CanActAs state.opener]"));assertTrue(identity.contains("readerRights == [CanReadAs state.opener]"));
  String integrated=Files.readString(Path.of("src/main/java/org/concordia/babycdf/AgendaFollowingReview.java"));assertFalse(integrated.contains("CommandService"));assertFalse(integrated.contains("addActAs"));
 }
}
