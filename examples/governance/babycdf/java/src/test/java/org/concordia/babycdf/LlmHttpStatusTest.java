package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class LlmHttpStatusTest {
 @Test void numericStatusAndFixedCategoryNeverCarryBodyOrCredential() {
  for(int code:List.of(401,403,404,429,500)){
   var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","SECRET_SENTINEL"));var calls=new java.util.concurrent.atomic.AtomicInteger();var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(code,"SECRET_BODY_SENTINEL".getBytes());},System::nanoTime,false);
   var failed=assertInstanceOf(LlmClient.Failed.class,client.assess("UNTRUSTED_PROMPT_SENTINEL"));assertEquals(code,failed.httpStatus());assertEquals(LlmClient.httpCategory(code),failed.httpCategory());assertEquals(1,calls.get());assertFalse(failed.toString().contains("SENTINEL"));
  }
 }
}
