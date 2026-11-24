package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class LlmComboConfigTest {
 @Test void literalComboDefaultAndExplicitPrecedence()throws Exception{
  var defaults=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));assertEquals("concordia-cdf",defaults.model());assertEquals("https://ai-gateway.unlockit.io/v1/chat/completions",defaults.endpoint().toString());
  assertEquals("chosen-model",LlmSettings.fromEnvironment(Map.of("LLM_MODEL","chosen-model","OPENAI_MODEL","other-model")).model());assertEquals("other-model",LlmSettings.fromEnvironment(Map.of("OPENAI_MODEL","other-model")).model());
  var requests=new ArrayList<byte[]>();var client=new LlmClient(defaults,r->{requests.add(SupplementalAssessmentTest.body(r));return SupplementalAssessmentTest.reply(429,new byte[0]);},System::nanoTime,false);client.assess("evidence");assertEquals(1,requests.size());assertEquals("concordia-cdf",Advisory.JSON.readTree(requests.getFirst()).path("model").asText());
 }
}
