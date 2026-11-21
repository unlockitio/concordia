package org.concordia.babycdf;

import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** In-process public evidence only. Completeness describes file coverage, not compliance. */
record EvidenceBundle(String url,String headSha,List<EvidenceBundle.File> files,String title,String body,boolean completeness) {
    static final int MAX_EVIDENCE_BYTES=200*1024;
    record File(String path,String blobSha,int bytes,String content) {}
    EvidenceBundle { files=List.copyOf(files); }
    static String blob(String content) {
        try {
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
            var digest=MessageDigest.getInstance("SHA-1");
            digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static EvidenceBundle proposal(String url,String head,List<File> offered,Set<String> expected,boolean complete) {
        List<File> accepted=new ArrayList<>(); Set<String> paths=new HashSet<>(); int bytes=0;
        boolean valid=complete&&head!=null&&head.matches("[a-f0-9]{40}");
        for(File file:offered) {
            if(file==null||file.path()==null||file.content()==null||file.blobSha()==null) { valid=false;continue; }
            byte[] raw=file.content().getBytes(StandardCharsets.UTF_8);
            if(!paths.add(file.path())||file.content().indexOf('\0')>=0||raw.length!=file.bytes()||!blob(file.content()).equals(file.blobSha())||raw.length>MAX_EVIDENCE_BYTES-bytes) { valid=false;continue; }
            bytes+=raw.length; accepted.add(file);
        }
        if(!paths.equals(expected)||accepted.size()!=expected.size()) valid=false;
        accepted.sort(Comparator.comparing(File::path));
        return new EvidenceBundle(url,head,accepted,null,null,valid);
    }
    static EvidenceBundle issue(String url,String title,String body) {
        String safeBody=body==null?"":body;
        if(safeBody.getBytes(StandardCharsets.UTF_8).length>MAX_EVIDENCE_BYTES) safeBody="";
        return new EvidenceBundle(url,"",List.of(),title,safeBody,false);
    }
    boolean verified(String expectedHead) {
        if(!Objects.equals(headSha,expectedHead)||headSha==null||!headSha.matches("[a-f0-9]{40}")) return false;
        for(File file:files) if(!blob(file.content()).equals(file.blobSha())||file.bytes()!=file.content().getBytes(StandardCharsets.UTF_8).length) return false;
        return true;
    }
    boolean containsQuote(String quote) {
        String normalized=RuleVerdict.normalize(quote);
        return files.isEmpty()?RuleVerdict.normalize((title==null?"":title)+"\n"+(body==null?"":body)).contains(normalized):
                files.stream().anyMatch(f->RuleVerdict.normalize(f.content()).contains(normalized));
    }
    String evidenceText() { return files.isEmpty()?(title==null?"":title)+"\n"+(body==null?"":body):String.join("\n",files.stream().map(File::content).toList()); }
    String cacheIdentity() { return RuleVerdictCache.key("bundle-v1",url==null?"":url,headSha==null?"":headSha,
            files.stream().map(f->f.path()+":"+f.blobSha()).sorted().toList()+":"+completeness+":"+evidenceText()); }
}
