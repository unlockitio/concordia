package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class IntegratedReviewOutputTest {
 @Test void failedEmissionNeverAdvancesStateAndRestartSuppressesOnlySuccess()throws Exception {
  Path file=Path.of("integrated-output-test-"+java.util.UUID.randomUUID()+".json").toAbsolutePath();var output=new IntegratedReviewOutput(file);var result=Advisory.JSON.createObjectNode().put("status","evaluated").put("rule_version",1);
  assertThrows(java.io.IOException.class,()->output.publish(result,r->{throw new java.io.IOException("sink failed");}));assertFalse(Files.exists(file));
  assertTrue(output.publish(result,r->{}));assertTrue(Files.exists(file));assertFalse(new IntegratedReviewOutput(file).publish(result,r->fail("duplicate after restart")));
  var v2=result.deepCopy().put("rule_version",2);assertTrue(new IntegratedReviewOutput(file).publish(v2,r->{}));
 }
}
