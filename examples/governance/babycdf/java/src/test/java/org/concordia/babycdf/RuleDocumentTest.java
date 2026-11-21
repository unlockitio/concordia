package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class RuleDocumentTest {
    @Test void exactBytesAndStrictSchema(@TempDir Path root) throws Exception {
        String raw="{\"schema\":\"babycdf.rule/1\",\"version\":1,\"clauses\":[{\"id\":\"c1\",\"kind\":\"PRESENCE_REQUIRED\",\"text\":\"criterion\",\"expectedAnchor\":\"a\",\"expected\":\"support\",\"conflictAnchor\":\"b\",\"conflict\":\"negative\",\"indicators\":[\"security audit\"]}]}";
        byte[] bytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(root.resolve("rule.json"),bytes);
        var ref=new RuleDocument.Ref("rule.json",null,RuleDocument.sha(bytes));
        assertTrue(RuleDocument.local(ref,root).verified());
        Files.writeString(root.resolve("rule.json"),raw+"\n");assertEquals("rule_unverified",RuleDocument.local(ref,root).reason());
        assertEquals("malformed_rule",RuleDocument.parse(raw.replace("PRESENCE_REQUIRED","UNKNOWN").getBytes()).reason());
        assertEquals("malformed_rule",RuleDocument.parse(raw.replace("[\"security audit\"]","[]").getBytes()).reason());
        assertEquals("malformed_rule",RuleDocument.parse(raw.replace("\"version\":1","\"extra\":true,\"version\":1").getBytes()).reason());
    }
}
