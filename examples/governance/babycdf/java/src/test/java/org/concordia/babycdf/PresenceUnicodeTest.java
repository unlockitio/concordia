package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PresenceUnicodeTest {
 @Test void exactUnicodeSpans(){
  for(String[] pair:List.of(new String[]{"İ prefix SECURITY AUDIT","security audit","SECURITY AUDIT"},new String[]{"ẞ prefix security audit","security audit","security audit"},new String[]{"préfix ÉVALUATION SéCURITÉ","évaluation sécurité","ÉVALUATION SéCURITÉ"},new String[]{"İẞ".repeat(10000)+" SECURITY AUDIT","security audit","SECURITY AUDIT"})){
   var c=new Advisory.Clause("c","a","support","b","negative","criterion",List.of(),List.of(),"PRESENCE_REQUIRED",List.of(pair[1]));
   assertEquals(pair[2],Presence.indicator(c,null,pair[0]));
  }
 }
 @Test void badEvidenceIsContained(){
  var c=new Advisory.Clause("c","a","support","b","negative","criterion",List.of(),List.of(),"PRESENCE_REQUIRED",List.of("audit"));
  var b=new EvidenceBundle("url","a".repeat(40),List.of(new EvidenceBundle.File("f","a",0,null)),null,null,true);
  assertEquals("presence_evaluation_failed",Presence.resolve(c,b,null,true,null,List.of()).reason());
 }
}
