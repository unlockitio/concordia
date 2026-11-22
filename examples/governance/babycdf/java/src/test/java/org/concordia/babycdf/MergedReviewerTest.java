package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class MergedReviewerTest {
 @Test void oneCollectionOneAssessmentAndSharedPreview()throws Exception{
  var collections=new AtomicInteger();var calls=new AtomicInteger();var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));byte[] response=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("PASS",""));var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);var session=new SupplementalAssessment("Reviewer",settings,client,null,System.out);var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,SupplementalAssessmentTest.rule(1).clauses());var previewState=new HashSet<String>();
  Reviewer.Collection collector=()->{collections.incrementAndGet();var result=Advisory.JSON.createObjectNode();result.putArray("errors");result.putArray("inputs").addObject().put("kind","proposal_pr").put("number",184).put("url","https://github.com/"+Advisory.REPO+"/pull/184").put("revision","a".repeat(40)).put("pinned",true).put("_review_text","CAP evidence");return result;};
  var first=Reviewer.review(()->List.of(rule),path->{throw new AssertionError();},session,collector);var exact=first.path("reviews").get(0).path("supplemental_assessment").path("exact").deepCopy();Reviewer.attachPreviews(first,previewState);assertEquals("preview_submission_blocked",first.path("ballot_previews").get(0).path("status").asText());assertEquals(exact,first.path("reviews").get(0).path("supplemental_assessment").path("exact"));var second=Reviewer.review(()->List.of(rule),path->{throw new AssertionError();},session,collector);Reviewer.attachPreviews(second,previewState);assertTrue(second.path("ballot_previews").isEmpty());assertEquals(2,collections.get());assertEquals(1,calls.get());assertFalse(first.path("ballot_previews").get(0).path("submitted").asBoolean());
 }
 @Test void oldMainRetired(){assertTrue(assertThrows(IllegalArgumentException.class,()->Advisory.main(new String[0])).getMessage().contains("retired"));}
}
