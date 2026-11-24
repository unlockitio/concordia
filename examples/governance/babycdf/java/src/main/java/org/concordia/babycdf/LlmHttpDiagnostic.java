package org.concordia.babycdf;
import java.nio.file.Path;
/** Explicit one-assessment diagnostic. No polling, retries or persistent-cache mutation. */
public final class LlmHttpDiagnostic {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("port opener deployed-package required");
  var settings=LlmSettings.fromEnvironment(System.getenv());
  if(settings.disabledReason()!=null){System.out.println("diagnostic_blocked="+settings.disabledReason());return;}
  System.out.println("diagnostic_budget=1 assessment; endpoint="+settings.endpoint()+" model="+settings.model());
  try(var assessment=new SupplementalAssessment("ReviewerDiagnostic",settings,new LlmClient(settings),null,System.out)){
   var ledger=new Reviewer.LocalSandboxGateway(Integer.parseInt(args[0]),args[1],args[2]);
   new AgendaFollowingReview(new Advisory.GitHub(),assessment).cycle(ledger);
  }
 }
}
