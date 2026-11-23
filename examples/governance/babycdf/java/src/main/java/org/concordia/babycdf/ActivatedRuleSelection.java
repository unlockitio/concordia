package org.concordia.babycdf;
import com.daml.ledger.api.v2.*;import java.util.*;import java.io.IOException;
/** Registry version is current authority; retained historical EffectiveRules are expected. */
final class ActivatedRuleSelection {
 static Reviewer.EffectiveRule select(Reviewer.LedgerRuleGateway ledger,IntegratedAgendaSnapshot agenda)throws Exception {
  var rules=ledger.activeRules();if(!ledger.registryBacked()){if(rules.stream().filter(r->r!=null&&"architecture-review".equals(r.ruleId())).count()!=1)throw new IOException("rule_selection");return Reviewer.select(rules);}
  var registries=ledger.registryEvents();if(registries.size()!=1)throw new IOException("rule_selection");var event=registries.getFirst();var record=event.getCreateArguments();var opener=IntakeLedger.field(record,"opener");if(!opener.hasParty()||!opener.getParty().equals(agenda.opener())||!event.getSignatoriesList().equals(List.of(agenda.opener()))||!event.getWitnessPartiesList().contains(agenda.opener()))throw new IOException("rule_selection");long version=IntakeLedger.number(record,"version");if(version<1)throw new IOException("rule_selection");
  if(rules.stream().anyMatch(r->r!=null&&"architecture-review".equals(r.ruleId())&&r.version()>version))throw new IOException("rule_selection");var selected=rules.stream().filter(r->r!=null&&"architecture-review".equals(r.ruleId())&&r.version()==version).toList();if(selected.size()!=1)throw new IOException("rule_selection");var current=Reviewer.select(selected);if(current.ruleFailure()!=null||current.ruleRef()==null||!RuleDocument.runtime(current.ruleRef()).verified()||RuleDocument.runtime(current.ruleRef()).version()!=version||!RuleDocument.runtime(current.ruleRef()).clauses().equals(current.clauses()))throw new IOException("rule_selection");return current;
 }
}
