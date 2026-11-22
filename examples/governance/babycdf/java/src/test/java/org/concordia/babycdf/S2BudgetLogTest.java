package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class S2BudgetLogTest {
 Reviewer.EffectiveRule rule(String sha,int version,String failure){return new Reviewer.EffectiveRule("cid"+version,"architecture-review",version,SupplementalAssessmentTest.rule(1).clauses(),new RuleDocument.Ref("rule"+version+".json",null,sha),failure);}
 @Test void bucketsAndRefLogs()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();var logs=new ByteArrayOutputStream();var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(500,new byte[0]);},System::nanoTime,false);var s=new SupplementalAssessment("Reviewer",settings,client,null,new PrintStream(logs));var a=rule("a".repeat(64),1,null);var b=rule("b".repeat(64),2,null);
  for(int i=0;i<5;i++)s.assess(SupplementalAssessmentTest.item(),"CAP evidence "+i,a,"review");assertEquals(4,calls.get());s.assess(SupplementalAssessmentTest.item(),"CAP evidence B",b,"review");assertEquals(5,calls.get());s.assess(SupplementalAssessmentTest.item(),"CAP evidence new",a,"review");assertEquals(5,calls.get());
  for(int i=0;i<3;i++)s.assess(SupplementalAssessmentTest.item(),"",rule("c".repeat(64),3,"rule_unverified"),"review");assertEquals(5,calls.get());assertEquals(3,logs.toString().lines().filter(l->l.contains("verified=false")).count());assertEquals(3,logs.toString().lines().filter(l->l.contains("verified=true")).count());
 }
 @Test void invalidInputsAndCacheAreFree()throws Exception{
  var settings=LlmSettings.fromEnvironment(Map.of("LLM_API_KEY","synthetic"));var calls=new AtomicInteger();byte[] response=SupplementalAssessmentTest.response(SupplementalAssessmentTest.valid("PASS",""));var client=new LlmClient(settings,r->{calls.incrementAndGet();return SupplementalAssessmentTest.reply(200,response);},System::nanoTime,false);var s=new SupplementalAssessment("Reviewer",settings,client,null,System.out);var a=rule("f".repeat(64),21,null);s.assess(SupplementalAssessmentTest.item(),"",a,"review");assertEquals(0,s.assessments());s.assess(SupplementalAssessmentTest.item(),"x".repeat(EvidenceBundle.MAX_EVIDENCE_BYTES+1),a,"review");assertEquals(0,s.assessments());s.assess(SupplementalAssessmentTest.item(),"CAP evidence",a,"review");int tokens=s.reservedTokens();s.assess(SupplementalAssessmentTest.item(),"CAP evidence",a,"review");assertEquals(1,calls.get());assertEquals(1,s.assessments());assertEquals(tokens,s.reservedTokens());
 }
 @Test void activeClausesOnlyAndWarningVisible()throws Exception{
  var log=new ByteArrayOutputStream();var out=new PrintStream(log);var a=rule("d".repeat(64),11,null);var b=rule("e".repeat(64),12,null);RuleDocument.announceActive(a,out);RuleDocument.announceActive(a,out);RuleDocument.announceActive(b,out);RuleDocument.announceActive(a,out);assertEquals(3,log.toString().lines().filter(l->l.contains(" active ")).count());assertEquals(2,log.toString().lines().filter(l->l.contains(" — ")).count());
  var properties=new Properties();try(var in=getClass().getResourceAsStream("/logging.properties")){properties.load(in);}assertEquals("WARNING",properties.getProperty("io.grpc.netty.shaded.level"));assertEquals("INFO",properties.getProperty(".level"));var capture=new ByteArrayOutputStream();var handler=new java.util.logging.StreamHandler(capture,new java.util.logging.SimpleFormatter());var logger=java.util.logging.Logger.getLogger("babycdf.deliberate.warning");logger.setUseParentHandlers(false);logger.addHandler(handler);logger.warning("deliberate warning retained");handler.flush();assertTrue(capture.toString().contains("deliberate warning retained"));logger.removeHandler(handler);
 }
}
