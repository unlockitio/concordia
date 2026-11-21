package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class RuleCacheTrustTest {
    RuleVerdictCache.Entry entry() { return new RuleVerdictCache.Entry(new RuleVerdict.Output(new RuleVerdict.Exact(RuleVerdict.Verdict.PASS,List.of(new RuleVerdict.Clause("c1",RuleVerdict.Verdict.PASS,"ok",List.of("evidence support"))),null),""),"model"); }
    boolean valid(RuleVerdictCache.Entry e) { return entry().equals(e); }
    @Test void numericUidWithoutPasswdStillWorks(@TempDir Path root) throws Exception {
        String previous=System.getProperty("user.name");
        try {
            System.setProperty("user.name","?");
            String key=RuleVerdictCache.key("v","m","r","e",List.of("PRESENCE_REQUIRED"),true);
            assertNotEquals(key,RuleVerdictCache.key("v","m","r","e",List.of("CONTRADICTION_OR_SUPPORT"),true));
            assertNotEquals(key,RuleVerdictCache.key("v","m","r","e",List.of("PRESENCE_REQUIRED"),false));
            var cache=new RuleVerdictCache(root);
            assertFalse(cache.get(key,this::entry).cached());
            assertTrue(cache.get(key,this::entry).cached());
            try(var files=Files.list(root)) {
                assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith("pending-owner-")&&p.toString().endsWith(".probe")));
            }
        } finally { if(previous==null) System.clearProperty("user.name"); else System.setProperty("user.name",previous); }
    }
    @Test void injectedOwnerMismatchIsRejected(@TempDir Path root) throws Exception {
        var calls=new AtomicInteger();
        var cache=new RuleVerdictCache(root,(directory,created)->false);
        assertEquals("cache_unavailable",assertThrows(java.io.IOException.class,()->cache.get(
                RuleVerdictCache.key("v","m","r","e"),()->{calls.incrementAndGet();return entry();})).getMessage());
        assertEquals(0,calls.get());
        try(var files=Files.list(root)) { assertEquals(0,files.count()); }
    }
    @Test void corruptAndForgedEntriesSelfHeal(@TempDir Path root) throws Exception {
        String key=RuleVerdictCache.key("v","m","r","e"); Path file=root.resolve(key+".json");
        for(String bad:List.of("broken", "x".repeat(131073),RuleVerdict.JSON.writeValueAsString(entry()).replace("\"overall\":\"PASS\"","\"overall\":\"FAIL\""),RuleVerdict.JSON.writeValueAsString(entry()).replace("evidence support","forged quote"))) {
            Files.writeString(file,bad); var calls=new AtomicInteger();
            var hit=new RuleVerdictCache(root).get(key,this::valid,()->{calls.incrementAndGet();return entry();});
            assertFalse(hit.cached()); assertEquals(1,calls.get()); assertEquals(entry(),RuleVerdict.JSON.readValue(Files.readAllBytes(file),RuleVerdictCache.Entry.class));
        }
        Files.delete(file); Path target=root.resolve("target"); Files.writeString(target,"untouched"); Files.createSymbolicLink(file,target);
        assertFalse(new RuleVerdictCache(root).get(key,this::valid,this::entry).cached());
        assertFalse(Files.isSymbolicLink(file)); assertEquals("untouched",Files.readString(target));
    }
    @Test void unsafeRootAndFullCache(@TempDir Path root) throws Exception {
        String key=RuleVerdictCache.key("v","m","r","e");
        var permissions=Files.getPosixFilePermissions(root); var unsafe=new HashSet<>(permissions); unsafe.add(PosixFilePermission.GROUP_WRITE); Files.setPosixFilePermissions(root,unsafe);
        assertEquals("cache_unavailable",assertThrows(java.io.IOException.class,()->new RuleVerdictCache(root).get(key,this::entry)).getMessage());
        unsafe.remove(PosixFilePermission.GROUP_WRITE); unsafe.add(PosixFilePermission.OTHERS_WRITE); Files.setPosixFilePermissions(root,unsafe);
        assertEquals("cache_unavailable",assertThrows(java.io.IOException.class,()->new RuleVerdictCache(root).get(key,this::entry)).getMessage());
        Files.setPosixFilePermissions(root,permissions);
        for(int i=0;i<256;i++) Files.writeString(root.resolve(String.format("%064x.json",i)),"{}");
        assertEquals("cache_full",assertThrows(java.io.IOException.class,()->new RuleVerdictCache(root).get(key,this::entry)).getMessage());
    }
    @Test void failuresAreOnlyProcessLocal(@TempDir Path root) throws Exception {
        String key=RuleVerdictCache.key("v","m","r","e"); AtomicInteger calls=new AtomicInteger();
        var cache=new RuleVerdictCache(root);
        java.util.function.Supplier<RuleVerdictCache.Entry> failure=()->{calls.incrementAndGet();return new RuleVerdictCache.Entry(new RuleVerdict.Output(RuleVerdict.failure("HTTP_STATUS"),""),"unknown");};
        cache.get(key,failure); cache.get(key,failure); assertEquals(1,calls.get());
        assertFalse(Files.exists(root.resolve(key+".json")));
        new RuleVerdictCache(root).get(key,failure); assertEquals(2,calls.get());
    }
    @Test void normalizedAndInvalidQuotes() throws Exception {
        String evidence="Unicode café\u00a0evidence\n supports\tthis  criterion";
        for(String quote:List.of("café evidence supports", "evidence\n\t supports", "Unicode café")) {
            String content=RuleVerdict.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c1","verdict","PASS","rationale","ok","quotes",List.of(quote)))));
            assertEquals(RuleVerdict.Verdict.PASS,RuleVerdict.parse(content,List.of("c1"),evidence).exact().overall());
        }
        for(List<String> quotes:List.of(List.of(""),List.of(" \t\n"),List.of("rule text only"),List.of("short"),List.of("x".repeat(2001)),Collections.nCopies(9,"Unicode café"))) {
            String content=RuleVerdict.JSON.writeValueAsString(Map.of("verdicts",List.of(Map.of("id","c1","verdict","FAIL","rationale","ok","quotes",quotes))));
            assertEquals(RuleVerdict.Verdict.INCONCLUSIVE,RuleVerdict.parse(content,List.of("c1"),evidence).exact().overall());
        }
    }
}
