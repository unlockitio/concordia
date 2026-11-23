package org.concordia.babycdf;
import org.junit.jupiter.api.Test;import com.fasterxml.jackson.databind.ObjectMapper;import static org.junit.jupiter.api.Assertions.*;
class ConcordiaDemoScopeTest {
 @Test void rootExactPinAndIndependentImmutableMembership()throws Exception {
  var json=new ObjectMapper();String root="[\""+ConcordiaDemoScope.ROOT_HEAD+"\",[[\"proposals/concordia.md\",\"added\",\""+ConcordiaDemoScope.ROOT_BLOB+"\",\"\"]]]";
  assertTrue(ConcordiaDemoScope.proposal(184,ConcordiaDemoScope.REPO,json.readTree(root)));assertFalse(ConcordiaDemoScope.proposal(184,ConcordiaDemoScope.REPO,json.readTree(root.replace(ConcordiaDemoScope.ROOT_HEAD,"a".repeat(40)))));
  assertTrue(ConcordiaDemoScope.proposal(475,ConcordiaDemoScope.REPO,json.readTree(root)));assertFalse(ConcordiaDemoScope.proposal(475,ConcordiaDemoScope.REPO,json.readTree(root.replace("concordia.md","unrelated.md"))));
  assertFalse(ConcordiaDemoScope.proposal(184,"foreign/repo",json.readTree(root)));assertTrue(ConcordiaDemoScope.milestone(ConcordiaDemoScope.REPO,184));assertFalse(ConcordiaDemoScope.milestone(ConcordiaDemoScope.REPO,999));
 }
}
