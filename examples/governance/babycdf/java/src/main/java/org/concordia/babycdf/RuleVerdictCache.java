package org.concordia.babycdf;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;

/** Private, bounded cache; failures are deduplicated only in memory. */
final class RuleVerdictCache {
    record QuoteRef(int index,int start,int end) {}
    record Entry(RuleVerdict.Output output,String model,RuleVerdict.Exact raw,Map<String,List<QuoteRef>> quoteRefs) {
        Entry(RuleVerdict.Output output,String model) { this(output,model,null,Map.of()); }
        Entry(RuleVerdict.Output output,String model,RuleVerdict.Exact raw) { this(output,model,raw,Map.of()); }
    }
    record Hit(Entry entry,boolean cached) {}
    private static final Map<Path,Object> LOCKS=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Entry> memory=new HashMap<>();
    private final Map<String,Entry> failures=new HashMap<>();
    private final Path root;
    interface OwnerCheck { boolean matches(Path root,Path created) throws java.io.IOException; }
    private final OwnerCheck ownerCheck;
    RuleVerdictCache(Path root) { this(root,RuleVerdictCache::sameOwner); }
    RuleVerdictCache(Path root,OwnerCheck ownerCheck) { this.root=root; this.ownerCheck=ownerCheck; }
    private static boolean sameOwner(Path root,Path created) throws java.io.IOException {
        try {
            return Files.getAttribute(root,"unix:uid",LinkOption.NOFOLLOW_LINKS)
                    .equals(Files.getAttribute(created,"unix:uid",LinkOption.NOFOLLOW_LINKS));
        } catch(UnsupportedOperationException unsupported) {
            // Compare filesystem principals directly; never resolve user.name/passwd.
            return Files.getOwner(root,LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(created,LinkOption.NOFOLLOW_LINKS));
        }
    }
    static boolean cacheable(Entry entry) { return entry.output().exact().reason()==null; }
    static String key(String version,String model,String rule,String evidence,List<String> clauseKinds,boolean complete) {
        try { return key(version,model,RuleVerdict.JSON.writeValueAsString(List.of(rule,clauseKinds,complete)),evidence); }
        catch(java.io.IOException failure) { throw new IllegalStateException("cache_key_failure"); }
    }
    static String key(String version,String model,String rule,String evidence) {
        try { return digest(RuleVerdict.JSON.writeValueAsString(List.of(version,digest(RuleVerdict.SYSTEM),model,digest(rule),digest(evidence)))); }
        catch(Exception failure) { throw new IllegalStateException("cache_key_failure"); }
    }
    private static String digest(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    synchronized Hit get(String key,Supplier<Entry> evaluate) throws java.io.IOException { return get(key,e->true,evaluate); }
    synchronized Hit get(String key,Predicate<Entry> verify,Supplier<Entry> evaluate) throws java.io.IOException {
        if(!key.matches("[a-f0-9]{64}")) throw new java.io.IOException("cache_unavailable");
        if(failures.containsKey(key)) return new Hit(failures.get(key),false);
        if(root==null) {
            if(memory.containsKey(key)&&verify.test(memory.get(key))) return new Hit(memory.get(key),true);
            memory.remove(key);
            Entry entry=evaluate.get(); (cacheable(entry)?memory:failures).put(key,entry); return new Hit(entry,false);
        }
        synchronized(LOCKS.computeIfAbsent(root.toAbsolutePath().normalize(),p->new Object())) {
            try {
                if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                var attrs=Files.readAttributes(root,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attrs.isDirectory()||attrs.permissions().contains(PosixFilePermission.GROUP_WRITE)||attrs.permissions().contains(PosixFilePermission.OTHERS_WRITE)) throw new java.io.IOException("cache_unavailable");
                // A freshly created probe belongs to the effective process UID, even
                // when that UID has no passwd entry (as in the numeric-UID image).
                Path probe=Files.createTempFile(root,"pending-owner-",".probe");
                try { if(!ownerCheck.matches(root,probe)) throw new java.io.IOException("cache_unavailable"); }
                finally { Files.deleteIfExists(probe); }
            } catch(Exception failure) { throw new java.io.IOException("cache_unavailable"); }
            try(FileChannel channel=FileChannel.open(root.resolve("cache.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS); FileLock held=channel.lock()) {
                Path file=root.resolve(key+".json");
                if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        if(Files.isSymbolicLink(file)||Files.size(file)>131072) throw new java.io.IOException();
                        Entry entry=RuleVerdict.JSON.readValue(Files.readAllBytes(file),Entry.class);
                        if(cacheable(entry)&&verify.test(entry)) return new Hit(entry,true);
                    } catch(Exception corrupt) { /* Treat corrupt/untrusted entries as a miss; replace atomically. */ }
                }
                long count; try(var files=Files.list(root)) { count=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).count(); }
                if(count>=256&&!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("cache_full");
                Entry entry=evaluate.get();
                if(!cacheable(entry)) { failures.put(key,entry); return new Hit(entry,false); }
                Path temporary=null;
                try {
                    byte[] bytes=RuleVerdict.JSON.writeValueAsBytes(entry);
                    if(bytes.length>131072) return new Hit(entry,false);
                    temporary=Files.createTempFile(root,"pending-",".json"); Files.write(temporary,bytes);
                    Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } catch(java.io.IOException writeFailure) { /* The paid validated result remains usable. */ }
                finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(java.io.IOException ignored) {} }
                return new Hit(entry,false);
            }
        }
    }
}
