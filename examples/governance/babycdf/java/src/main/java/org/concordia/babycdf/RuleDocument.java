package org.concordia.babycdf;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.io.*;
import com.fasterxml.jackson.databind.*;

/** Exact-byte rule authority. No document is parsed before hash verification. */
final class RuleDocument {
    private static final Set<String> ANNOUNCED=java.util.concurrent.ConcurrentHashMap.newKeySet();
    record Ref(String location,String commit,String sha256) {}
    record Resolved(int version,List<Advisory.Clause> clauses,String reason) { boolean verified(){return reason==null;} }
    static Path rulesRoot() { return Path.of(System.getProperty("babycdf.rules.root","../rules")); }
    static Resolved runtime(Ref ref) { return resolve(ref,rulesRoot(),RuleDocument::github); }
    private static final Map<Ref,byte[]> REMOTE_CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class SharedApi { static final Advisory.Api API=new Advisory.GitHub(); }
    private static byte[] github(Ref ref) throws Exception {
        byte[] cached=REMOTE_CACHE.get(ref); if(cached!=null)return cached.clone();
        byte[] bytes=github(ref,SharedApi.API);
        if(bytes.length<=256*1024&&sha(bytes).equals(ref.sha256())&&parse(bytes).verified()&&REMOTE_CACHE.size()<64)REMOTE_CACHE.put(ref,bytes.clone());
        return bytes;
    }
    static byte[] github(Ref ref,Advisory.Api api) throws Exception {
        var uri=java.net.URI.create(ref.location());
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException();
        String[] p=uri.getPath().split("/",-1);String owner,repo,commit,path;
        if("github.com".equals(uri.getHost())&&p.length>=6&&"blob".equals(p[3])) {owner=p[1];repo=p[2];commit=p[4];path=String.join("/",Arrays.copyOfRange(p,5,p.length));}
        else if("raw.githubusercontent.com".equals(uri.getHost())&&p.length>=5) {owner=p[1];repo=p[2];commit=p[3];path=String.join("/",Arrays.copyOfRange(p,4,p.length));}
        else throw new IOException();
        if(!commit.matches("[a-f0-9]{40}")||ref.commit()!=null&&!ref.commit().equals(commit))throw new IOException();
        for(String segment:(owner+"/"+repo+"/"+path).split("/",-1)) if(!segment.matches("[A-Za-z0-9._-]+")||segment.equals("..")||segment.equals("."))throw new IOException();
        var blob=api.get("repos/"+owner+"/"+repo+"/contents/"+path+"?ref="+commit);
        if(!"base64".equals(blob.path("encoding").asText()))throw new IOException();
        String encoded=blob.path("content").asText();if(encoded.length()>360000)throw new IOException();
        return Base64.getMimeDecoder().decode(encoded);
    }
    static Resolved local(Ref ref,Path root) { return resolve(ref,root,r->{throw new IOException("remote rule unavailable");}); }
    interface Remote { byte[] fetch(Ref ref) throws Exception; }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static Resolved resolve(Ref ref,Path root,Remote remote) {
        try {
            byte[] bytes;
            if(ref==null||ref.location()==null||ref.sha256()==null||!ref.sha256().matches("[a-f0-9]{64}")) return bad("rule_unverified");
            if(ref.location().startsWith("https://")) bytes=remote.fetch(ref);
            else {
                if(ref.location().contains(":")||Arrays.asList(ref.location().split("/")).contains("..")||Path.of(ref.location()).isAbsolute()) return bad("rule_unverified");
                String location=ref.location().startsWith("rules/")?ref.location().substring(6):ref.location();
                Path base=root.toRealPath(),path=base.resolve(location).normalize();
                if(!path.startsWith(base)||!path.toRealPath().startsWith(base)) return bad("rule_unverified");
                Path component=base;
                for(Path part:base.relativize(path)) { component=component.resolve(part); if(Files.isSymbolicLink(component)) return bad("rule_unverified"); }
                try(var in=Files.newInputStream(path)) {bytes=in.readNBytes(256*1024+1);}
            }
            if(bytes.length>256*1024||!sha(bytes).equals(ref.sha256())) return bad("rule_unverified");
            var resolved=parse(bytes);
            if(resolved.verified()&&ANNOUNCED.add(ref.sha256())) for(var clause:resolved.clauses()) System.err.println("rule v"+resolved.version()+" "+ref.sha256().substring(0,12)+": "+clause.id()+" — "+clause.criterion());
            return resolved;
        } catch(Exception | LinkageError failure) { return bad("rule_unverified"); }
    }
    static Resolved parse(byte[] bytes) {
        try {
            JsonNode root=RuleVerdict.JSON.readTree(bytes); fields(root,Set.of("schema","version","clauses"));
            if(!"babycdf.rule/1".equals(root.path("schema").asText())||!root.path("version").isInt()||root.path("version").asInt()<1||!root.path("clauses").isArray()||root.path("clauses").isEmpty())return bad("malformed_rule");
            List<Advisory.Clause> clauses=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonNode c:root.path("clauses")) {
                fields(c,Set.of("id","kind","text","expectedAnchor","expected","conflictAnchor","conflict","requiredPhrases","forbiddenPhrases","indicators"));
                String id=text(c,"id"),kind=text(c,"kind");
                if(!ids.add(id)||ClauseKind.recognized(kind)==null) return bad("malformed_rule");
                var indicators=list(c,"indicators");
                if(kind.equals("PRESENCE_REQUIRED")&&indicators.isEmpty()||kind.equals("CONTRADICTION_OR_SUPPORT")&&!indicators.isEmpty())return bad("malformed_rule");
                clauses.add(new Advisory.Clause(id,text(c,"expectedAnchor"),text(c,"expected"),text(c,"conflictAnchor"),text(c,"conflict"),text(c,"text"),list(c,"requiredPhrases"),list(c,"forbiddenPhrases"),kind,indicators));
            }
            return new Resolved(root.path("version").asInt(),List.copyOf(clauses),null);
        } catch(Exception invalid) {return bad("malformed_rule");}
    }
    private static void fields(JsonNode n,Set<String> allowed) {if(n==null||!n.isObject())throw new IllegalArgumentException();n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException();});}
    private static String text(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isTextual()||v.asText().isBlank()||v.asText().length()>4000)throw new IllegalArgumentException();return v.asText();}
    private static List<String> list(JsonNode n,String key){var v=n.get(key);if(v==null)return List.of();if(!v.isArray()||v.size()>64)throw new IllegalArgumentException();List<String> out=new ArrayList<>();for(var s:v){if(!s.isTextual()||s.asText().isBlank())throw new IllegalArgumentException();out.add(s.asText());}return out;}
    private static Resolved bad(String reason){return new Resolved(0,List.of(),reason);}
}
