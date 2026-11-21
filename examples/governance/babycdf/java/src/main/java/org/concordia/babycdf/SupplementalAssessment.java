package org.concordia.babycdf;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Read-only semantic interpretation. Deterministic findings are never modified. */
final class SupplementalAssessment implements AutoCloseable {
    static final int MAX_ASSESSMENTS=4, MAX_INPUT_BYTES=1600000, MAX_ATTEMPTS=8, MAX_RESERVED_TOKENS=2000000;
    private final LlmClient client;
    private final String agent, model, disabled, credential;
    private final PrintStream log;
    private final RuleVerdictCache cache;
    private int assessments, reservedAttempts, reservedTokens;
    private boolean notice;
    private final Set<String> emitted=new HashSet<>();
    private final Map<ObjectNode,String> displayKeys=new IdentityHashMap<>();
    static SupplementalAssessment runtime(String agent,PrintStream log) {
        var env=System.getenv();
        // Disabled mode does not even open a configured credential file.
        boolean enabled="1".equals(env.get("BABYCDF_LLM_ENABLED"));
        var settings=LlmSettings.fromEnvironment(enabled?env:Map.of());
        String disabled=enabled?settings.disabledReason():"runtime_opt_in_required";
        return new SupplementalAssessment(agent,settings,disabled==null?new LlmClient(settings):null,disabled,log,
                Path.of(System.getProperty("java.io.tmpdir"),"babycdf-"+safe(agent)+"-rule-verdict"));
    }
    SupplementalAssessment(String agent,LlmSettings settings,LlmClient client,String disabled,PrintStream log) {
        this(agent,settings,client,disabled,log,null);
    }
    SupplementalAssessment(String agent,LlmSettings settings,LlmClient client,String disabled,PrintStream log,Path cacheRoot) {
        this.agent=safe(agent); this.client=client; this.disabled=disabled; this.log=log;
        model=settings.model(); credential=settings.disabledReason()==null?settings.authorization().substring(7):"";
        cache=new RuleVerdictCache(cacheRoot);
    }
    boolean enabled() { return disabled==null; }
    private void disabledNotice() {
        if(disabled!=null&&!notice) { log.println("[llm] agent="+agent+" status=disabled reason="+safe(disabled)); notice=true; }
    }
    void contextFailure() { log.println("["+agent+"] LLM rule check unavailable: INCONCLUSIVE reason=rule_context_unavailable"); }
    void assess(ObjectNode item,String text,Object rule,String task) { assess(item,text,rule,task,"pinned_demo_fixture_not_live_authority"); }
    synchronized void assess(ObjectNode item,String text,Object rule,String task,String source) {
        long start=System.nanoTime();
        if(!enabled()) { disabledNotice(); return; }
        if(rule instanceof Reviewer.EffectiveRule effective && effective.ruleFailure()!=null) {
            display(item,rule,source,failure("rule_unverified"),false,start); return;
        }
        if(!enabled()) { disabledNotice(); return; }
        try {
            if(rule instanceof Reviewer.EffectiveRule effective&&effective.ruleRef()!=null) {
                log.println("["+agent+"] rule_ref="+safe(effective.ruleRef().location())+" sha="+safe(effective.ruleRef().sha256().substring(0,12))+" verified="+(effective.ruleFailure()==null));
                if(effective.ruleFailure()!=null) {display(item,rule,source,failure("rule_unverified"),false,start);return;}
            }
            boolean issue=item.path("kind").asText().contains("issue");
            EvidenceBundle bundle=item.has("evidence_bundle")?Advisory.JSON.treeToValue(item.get("evidence_bundle"),EvidenceBundle.class):null;
            String evidence=bundle==null?text:bundle.evidenceText();
            if((!item.path("pinned").asBoolean()&&!issue)||item.has("supplemental_valid")&&!item.path("supplemental_valid").asBoolean()||evidence==null||evidence.isBlank()) {
                display(item,rule,source,new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("invalid_input"),""),"unknown"),false,start); return;
            }
            String context=Advisory.JSON.valueToTree(rule).toString();
            if(evidence.getBytes(StandardCharsets.UTF_8).length>EvidenceBundle.MAX_EVIDENCE_BYTES) {
                display(item,rule,source,failure("evidence_budget_exhausted"),false,start); return;
            }
            boolean absenceEligible=Presence.eligible(item,bundle);
            String key=RuleVerdictCache.key(RuleVerdict.PROMPT_VERSION,model,context,evidence+(bundle==null?"":bundle.cacheIdentity()),
                    ruleClauses(context).stream().map(Advisory.Clause::kind).toList(),absenceEligible);
            // Lock, lookup and evaluation are atomic. Cache hits bypass every budget counter.
            var hit=cache.get(key,entry->trusted(entry,context,evidence,bundle,absenceEligible),()->evaluate(context,evidence,source,bundle,absenceEligible));
            displayKeys.put(item,key);
            try { display(item,rule,source,hit.entry(),hit.cached(),start); }
            finally { displayKeys.remove(item); }
        } catch(Exception failure) {
            try { display(item,rule,source,new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure(
                    "cache_unavailable".equals(failure.getMessage())?"cache_unavailable":"cache_or_internal_failure"),""),"unknown"),false,start); }
            catch(Exception ignored) { /* Never interfere with deterministic evaluation. */ }
        }
    }
    private RuleVerdictCache.Entry evaluate(String context,String evidence,String source,EvidenceBundle bundle,boolean eligible) {
        try {
            var tree=Advisory.JSON.readTree(context);
            List<String> ids=new ArrayList<>();
            for(var clause:tree.path("clauses")) ids.add(clause.path("id").asText());
            if(ids.isEmpty()||ids.stream().anyMatch(String::isBlank)||new HashSet<>(ids).size()!=ids.size()) return failure("invalid_rule");
            ObjectNode data=Advisory.JSON.createObjectNode();
            data.put("prompt_version",RuleVerdict.PROMPT_VERSION).put("rule_context_source",source);
            data.put("untrusted_rule_data",context);
            if(bundle!=null) data.put("untrusted_evidence_bundle",Advisory.JSON.writeValueAsString(bundle));
            else data.put("untrusted_public_pinned_text",evidence);
            String prompt=data.toString();
            if(prompt.getBytes(StandardCharsets.UTF_8).length>MAX_INPUT_BYTES) return failure("input_budget_exhausted");
            int attempts=client.maximumAttempts();
            int tokens=Math.multiplyExact(attempts,Math.addExact(client.requestBody(prompt).length,2048));
            if(assessments>=MAX_ASSESSMENTS||reservedAttempts+attempts>MAX_ATTEMPTS||reservedTokens+tokens>MAX_RESERVED_TOKENS) return failure("run_budget_exhausted");
            assessments++; reservedAttempts+=attempts; reservedTokens+=tokens;
            var output=client.assessRule(prompt,ids,evidence);
            var metadata=client.ruleMetadata();
            // Redact before any persistence; commentary cannot influence exact identity.
            var parsed=output.exact();
            var exact=new RuleVerdict.Exact(parsed.overall(),parsed.verdicts().stream().map(c->new RuleVerdict.Clause(c.id(),c.verdict(),c.rationale(),c.quotes().stream().map(RuleVerdict::normalize).toList())).toList(),parsed.reason());
            if(bundle!=null) {
                var verified=exact.verdicts().stream().map(c->(c.verdict()==RuleVerdict.Verdict.PASS||c.verdict()==RuleVerdict.Verdict.FAIL)
                    &&c.quotes().stream().anyMatch(q->!bundle.containsQuote(q))?
                    new RuleVerdict.Clause(c.id(),RuleVerdict.Verdict.INCONCLUSIVE,"unverified_quote",List.of()):c).toList();
                exact=new RuleVerdict.Exact(RuleVerdict.aggregate(verified),verified,exact.reason());
            }
            var references=quoteReferences(exact,evidence,bundle);
            var raw=new RuleVerdict.Exact(exact.overall(),exact.verdicts().stream().map(c->new RuleVerdict.Clause(c.id(),c.verdict(),clean(c.rationale()),List.of())).toList(),exact.reason());
            if(exact.reason()==null) exact=RuleVerdict.resolve(exact,ruleClauses(context),bundle,eligible);
            var clauses=exact.verdicts().stream().map(c->new RuleVerdict.Clause(c.id(),c.verdict(),clean(c.rationale()),
                    c.quotes().stream().map(this::cleanQuote).toList())).toList();
            return new RuleVerdictCache.Entry(new RuleVerdict.Output(new RuleVerdict.Exact(exact.overall(),clauses,exact.reason()),clean(output.commentary())),
                    metadata==null?"unknown":clean(metadata.returnedModel()),raw,references);
        } catch(Exception failure) { return failure("internal_failure"); }
    }
    private List<Advisory.Clause> ruleClauses(String context) throws java.io.IOException {
        List<Advisory.Clause> clauses=new ArrayList<>();
        for(var node:Advisory.JSON.readTree(context).path("clauses")) clauses.add(Advisory.JSON.treeToValue(node,Advisory.Clause.class));
        return clauses;
    }
    boolean trusted(RuleVerdictCache.Entry entry,String context,String evidence,EvidenceBundle bundle) {return trusted(entry,context,evidence,bundle,false);}
    boolean trusted(RuleVerdictCache.Entry entry,String context,String evidence,EvidenceBundle bundle,boolean eligible) {
        try {
            List<String> ids=new ArrayList<>();
            for(var clause:Advisory.JSON.readTree(context).path("clauses")) ids.add(clause.path("id").asText());
            if(entry.raw()==null||entry.raw().reason()!=null) return false;
            var restored=restoreQuotes(entry,evidence,bundle);
            var root=Advisory.JSON.createObjectNode(); root.set("verdicts",Advisory.JSON.valueToTree(restored.verdicts()));
            var checked=RuleVerdict.parse(root.toString(),ids,evidence).exact();
            checked=sanitize(RuleVerdict.resolve(checked,ruleClauses(context),bundle,eligible));
            return checked.reason()==null&&checked.equals(entry.output().exact());
        } catch(Exception invalid) { return false; }
    }
    private List<String> quoteSources(String evidence,EvidenceBundle bundle) {
        if(bundle!=null&&!bundle.files().isEmpty()) return bundle.files().stream().sorted(Comparator.comparing(EvidenceBundle.File::path)).map(f->RuleVerdict.normalize(f.content())).toList();
        return List.of(RuleVerdict.normalize(evidence));
    }
    private Map<String,List<RuleVerdictCache.QuoteRef>> quoteReferences(RuleVerdict.Exact exact,String evidence,EvidenceBundle bundle) throws Exception {
        var sources=quoteSources(evidence,bundle);Map<String,List<RuleVerdictCache.QuoteRef>> result=new LinkedHashMap<>();
        for(var clause:exact.verdicts()) {
            List<RuleVerdictCache.QuoteRef> refs=new ArrayList<>();
            for(String quote:clause.quotes()) {
                String normalized=RuleVerdict.normalize(quote);
                for(int index=0;index<sources.size();index++) { int start=sources.get(index).indexOf(normalized);
                    if(start>=0) {refs.add(new RuleVerdictCache.QuoteRef(index,start,start+normalized.length()));break;}
                }
            }
            result.put(clause.id(),List.copyOf(refs));
        }
        return result;
    }
    private RuleVerdict.Exact restoreQuotes(RuleVerdictCache.Entry entry,String evidence,EvidenceBundle bundle) throws Exception {
        if(entry.quoteRefs()==null)throw new IllegalArgumentException("missing_quote_references");
        var sources=quoteSources(evidence,bundle);List<RuleVerdict.Clause> clauses=new ArrayList<>();
        for(var clause:entry.raw().verdicts()) {
            List<String> quotes=new ArrayList<>();
            for(var ref:entry.quoteRefs().getOrDefault(clause.id(),List.of())) {
                if(ref.index()<0||ref.index()>=sources.size())throw new IllegalArgumentException("invalid_quote_index");
                String text=sources.get(ref.index());if(ref.start()<0||ref.end()<ref.start()||ref.end()>text.length())throw new IllegalArgumentException("invalid_quote_reference");
                String quote=text.substring(ref.start(),ref.end());
                quotes.add(quote);
            }
            clauses.add(new RuleVerdict.Clause(clause.id(),clause.verdict(),clause.rationale(),quotes));
        }
        return new RuleVerdict.Exact(entry.raw().overall(),clauses,entry.raw().reason());
    }
    private RuleVerdict.Exact sanitize(RuleVerdict.Exact exact) {
        return new RuleVerdict.Exact(exact.overall(),exact.verdicts().stream().map(c->new RuleVerdict.Clause(c.id(),c.verdict(),clean(c.rationale()),c.quotes().stream().map(this::cleanQuote).toList())).toList(),exact.reason());
    }
    private String cleanQuote(String value) {
        String redacted=credential.isEmpty()?value:value.replace(credential,"[REDACTED]");
        return redacted.replaceAll("(?i)(bearer\\s+|(?:api[_-]?key|token|secret)\\s*[:=]\\s*)\\S+","$1[REDACTED]").replaceAll("github_pat_[A-Za-z0-9_]+","[REDACTED]");
    }
    private RuleVerdictCache.Entry failure(String reason) { return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure(reason),""),"unknown"); }
    private void display(ObjectNode item,Object rule,String source,RuleVerdictCache.Entry entry,boolean cached,long start) {
        var output=entry.output();
        var stored=item.putObject("supplemental_assessment");
        stored.put("authority","none_deterministic_findings_unchanged").put("rule_context_source",source);
        stored.set("exact",Advisory.JSON.valueToTree(output.exact()));
        for(var clause:stored.path("exact").path("verdicts")) if("conflict".equals(clause.path("rationale").asText())) ((ObjectNode)clause).put("conflict_quote_role","verified_supporting_non_authoritative");
        stored.put("commentary",clean(output.commentary()));
        var tree=Advisory.JSON.valueToTree(rule);
        String subject=safe(item.path("kind").asText("proposal"))+" #"+item.path("number").asText("unknown");
        String perClause=String.join(", ",output.exact().verdicts().stream().map(c->safe(c.id())+"="+c.verdict()+(Set.of("absent","incomplete_evidence","conflict","unknown_clause_kind","semantic_support_unconfirmed","presence_evaluation_failed","malformed_clause","unsafe_quote_redacted","rule_unverified").contains(c.rationale())?"("+c.rationale()+")":"")).toList());
        String identity=displayKeys.remove(item);
        if(identity==null) identity=RuleVerdictCache.key(RuleVerdict.PROMPT_VERSION,model,tree.toString(),item.path("body_sha256").asText()+output.exact().toString());
        if(!emitted.add(identity+":"+item.path("kind").asText()+":"+item.path("number").asText()+":"+output.exact().reason())) return;
        String reason=output.exact().reason()==null?"":" reason="+safe(output.exact().reason());
        log.println("["+agent+"] LLM rule check "+subject+" rule v"+tree.path("version").asText("unknown")+": "+output.exact().overall()
                +" ("+perClause+") model="+clean(entry.model())+" cached="+cached+" latency="+(System.nanoTime()-start)/1000000+"ms"+reason
                +" deterministic="+deterministic(item));
        if(!output.commentary().isEmpty()) log.println("["+agent+"]   comment (advisory, not verified): "+clean(output.commentary()));
    }
    private String deterministic(ObjectNode item) {
        boolean pass=false,unknown=false;
        for(var finding:item.path("findings")) {
            String verdict=finding.path("verdict").asText(finding.path("status").asText());
            if("fail".equalsIgnoreCase(verdict)) return "FAIL";
            if("pass".equalsIgnoreCase(verdict)) pass=true; else unknown=true;
        }
        return pass&&!unknown?"PASS":"INCONCLUSIVE";
    }
    int reservedTokens() { return reservedTokens; }
    int assessments() { return assessments; }
    private String clean(String value) { return safe(value==null?"unknown":cleanQuote(value)); }
    static String bounded(String text,int bytes) {
        var out=new StringBuilder(); int used=0;
        for(int offset=0;offset<text.length();) {
            int cp=text.codePointAt(offset); String s=new String(Character.toChars(cp)); int n=s.getBytes(StandardCharsets.UTF_8).length;
            if(used+n>bytes) break; out.append(s); used+=n; offset+=Character.charCount(cp);
        } return out.toString();
    }
    private static String safe(String value) {
        return bounded(value==null?"unknown":value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]"," ")
                .replaceAll("(?i)(bearer\\s+|(?:api[_-]?key|token|secret)\\s*[:=]\\s*)\\S+","$1[REDACTED]"),240);
    }
    @Override public void close() { }
}
