package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class S2FailureBoundaryTest {
 @Test void exactlyOneSlf4jProvider(){var providers=ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class).stream().toList();assertEquals(1,providers.size());assertEquals("org.slf4j.helpers.NOP_FallbackServiceProvider",providers.get(0).type().getName());}
 @Test void selectedFailureAlwaysLogsBeforeCollection()throws Exception{
  var rule=new Reviewer.EffectiveRule("cid","architecture-review",1,List.of(),new RuleDocument.Ref("missing",null,"a".repeat(64)),"rule_unverified");var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();var client=new LlmClient(settings,r->{calls.incrementAndGet();throw new AssertionError();},System::nanoTime,false);var session=new SupplementalAssessment("Reviewer",settings,client,null,System.out);var disabled=new SupplementalAssessment("Reviewer",LlmSettings.fromEnvironment(Map.of()),null,"disabled",System.out);var log=new ByteArrayOutputStream();var old=System.err;
  try{System.setErr(new PrintStream(log));for(var supplemental:List.of(session,disabled)){Reviewer.review(()->List.of(rule),p->{throw new AssertionError();},supplemental,()->{var n=Advisory.JSON.createObjectNode();n.putArray("inputs");n.putArray("errors");return n;});}assertThrows(IOException.class,()->Reviewer.review(()->List.of(rule),p->{throw new AssertionError();},session,()->{throw new IOException("collection failed");}));}finally{System.setErr(old);}
  assertEquals(3,log.toString().lines().filter(l->l.contains("verified=false reason=rule_unverified")).count());assertEquals(0,calls.get());assertEquals(0,session.assessments());assertEquals(0,session.reservedTokens());
 }
}
