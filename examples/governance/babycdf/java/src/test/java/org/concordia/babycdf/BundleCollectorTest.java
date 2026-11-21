package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
class BundleCollectorTest {
    @Test void rejectedFileCases() throws Exception {
        for(String mode:List.of("removed","truncated","sha","encoding","utf8","nul","large","failed","count","page")) {
            byte[] bytes=switch(mode) { case "utf8"->new byte[]{(byte)255};case "nul"->new byte[]{0};case "large"->new byte[EvidenceBundle.MAX_EVIDENCE_BYTES+1];default->"evidence support".getBytes(StandardCharsets.UTF_8);};
            String sha=EvidenceBundle.blob(new String(bytes,StandardCharsets.UTF_8));
            var files=Advisory.JSON.createArrayNode();
            int count=mode.equals("page")?100:1;
            for(int i=0;i<count;i++) files.addObject().put("filename","f"+i).put("sha",sha).put("status",mode.equals("removed")?"removed":"modified");
            var pr=Advisory.JSON.createObjectNode().put("html_url","url").put("changed_files",mode.equals("count")?2:count); pr.putObject("head").put("sha","a".repeat(40));
            Advisory.Api api=path->{
                if(mode.equals("failed")) throw new IOException("synthetic");
                return Advisory.JSON.createObjectNode().put("sha",mode.equals("sha")?"wrong":sha).put("encoding",mode.equals("encoding")?"raw":"base64")
                    .put("truncated",mode.equals("truncated")).put("content",Base64.getEncoder().encodeToString(bytes));
            };
            assertFalse(Advisory.collectBundle(api,pr,files).completeness(),mode);
        }
    }
    @Test void quoteCannotCrossFiles() {
        var a=new EvidenceBundle.File("a",EvidenceBundle.blob("first evidence"),14,"first evidence");
        var b=new EvidenceBundle.File("b",EvidenceBundle.blob("second evidence"),15,"second evidence");
        var bundle=new EvidenceBundle("url","a".repeat(40),List.of(a,b),null,null,true);
        assertFalse(bundle.containsQuote("evidence second")); assertTrue(bundle.containsQuote("first evidence"));
    }
}
