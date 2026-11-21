// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IntakeGitHubTest {
    @TempDir Path temp;
    private static final String SYNTHETIC="synthetic-unit-test-not-a-credential";
    IntakeGitHub configured(String content)throws IOException {
        Path file=temp.resolve("synthetic");Files.writeString(file,content);
        return IntakeGitHub.configured(Map.of("INTAKE_GITHUB_TOKEN_FILE",file.toString()));
    }
    @Test void exactBearerForBothProductionConsumers()throws Exception {
        var auth=configured(SYNTHETIC+"\n");AtomicInteger requests=new AtomicInteger();
        var transport=auth.transport(request->{
            assertEquals("Bearer "+SYNTHETIC,request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("GET",request.method());assertEquals("api.github.com",request.uri().getHost());
            assertEquals(Duration.ofSeconds(5),request.timeout().orElseThrow());requests.incrementAndGet();
            return new GitHubDiscovery.Response(200,List.of(),"[]");
        });
        Intake.liveSourcePages(transport).next(new JavaSourceIngestion.Checkpoint("cp",702,"",false));
        assertTrue(IntakeInventory.live(transport).discover().complete());
        assertEquals(3,requests.get());assertFalse(auth.toString().contains(SYNTHETIC));
    }
    @Test void productionClientNeverFollowsRedirects() {
        try(var client=IntakeGitHub.client()) {
            assertEquals(java.net.http.HttpClient.Redirect.NEVER,client.followRedirects());
            assertEquals(Duration.ofSeconds(5),client.connectTimeout().orElseThrow());
        }
    }
    @Test void unreadableFileBlocksBeforeTransportConstruction()throws Exception {
        Path file=temp.resolve("unreadable");Files.writeString(file,SYNTHETIC);
        Files.setPosixFilePermissions(file,Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file),
                    "privileged execution can read mode-000 files; unreadability is not established");
            assertThrows(IOException.class,()->IntakeGitHub.configured(Map.of("INTAKE_GITHUB_TOKEN_FILE",file.toString())));
        }
        finally {Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
    }
    @Test void rejectsOtherTargetsBeforeSending()throws Exception {
        var transport=configured(SYNTHETIC).transport(request->{fail("unsafe target sent");return null;});
        for(String target:List.of("http://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100",
                "https://evil.example/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100",
                "https://api.github.com:443/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100",
                "https://api.github.com/repos/other/repo/pulls?state=all&per_page=100",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100&unexpected=x",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100#fragment",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=50",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=50&sort=updated",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=50&sort=updated&direction=asc&after=YWJj",
                "https://user@api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=100",
                "https://api.github.com/repositories/1156484704/issues?state=all&per_page=100",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=100&page=0",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=open&per_page=100"))
            assertThrows(IOException.class,()->transport.get(URI.create(target),Duration.ofSeconds(5)));
    }
    @Test void noAmbientOrAnonymousFallback() {
        for(var env:List.of(Map.<String,String>of(),Map.of("GITHUB_TOKEN",SYNTHETIC),Map.of("LLM_API_KEY",SYNTHETIC),
                Map.of("INTAKE_GITHUB_TOKEN",SYNTHETIC),Map.of("INTAKE_GITHUB_ALLOW_ANONYMOUS","1"),
                Map.of("INTAKE_GITHUB_TOKEN_FILE","relative-synthetic-path"),
                Map.of("INTAKE_GITHUB_TOKEN_FILE",temp.resolve("missing").toString()))) {
            var e=assertThrows(IOException.class,()->IntakeGitHub.configured(env));assertNull(e.getCause());
            assertFalse(e.toString().contains(SYNTHETIC));
        }
    }
    @Test void rejectsBlankMultilineNonAsciiAndOversizeWithoutContentInErrors()throws Exception {
        for(String bad:List.of("","\n"," ",SYNTHETIC+"\r\n",SYNTHETIC+"\nsecond",SYNTHETIC+"\n\n","é", "a".repeat(4097))) {
            var e=assertThrows(IOException.class,()->configured(bad));assertNull(e.getCause());
            assertFalse(e.toString().contains(SYNTHETIC));
        }
    }
    @Test void rejectsDirectoryAndSymlink()throws Exception {
        Path target=temp.resolve("regular");Files.writeString(target,SYNTHETIC);
        Path link=temp.resolve("link");Files.createSymbolicLink(link,target);
        for(Path path:List.of(temp,link))assertThrows(IOException.class,()->IntakeGitHub.configured(Map.of("INTAKE_GITHUB_TOKEN_FILE",path.toString())));
    }
    @Test void transportFailureRedactsUnderlyingMessageAndCause()throws Exception {
        var transport=configured(SYNTHETIC).transport(request->{throw new IOException(request.headers().toString());});
        var e=assertThrows(IOException.class,()->transport.get(URI.create("https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=100"),Duration.ofSeconds(5)));
        assertNull(e.getCause());assertFalse(e.toString().contains(SYNTHETIC));
        StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));assertFalse(trace.toString().contains(SYNTHETIC));
    }
    @Test void authenticatedOpaqueCursorRetainsExactEncoding()throws Exception {
        var transport=configured(SYNTHETIC).transport(request->{
            assertTrue(request.uri().getRawQuery().endsWith("after=Y3Vyc29yOnYyOpLPAAABnkS7lojPAAAAAQlnAk0%3D"));
            return new GitHubDiscovery.Response(200,List.of(),"[]");
        });
        assertTrue(Intake.liveSourcePages(transport).next(new JavaSourceIngestion.Checkpoint("cp",702,
                "full-v2:issues:3:0::Y3Vyc29yOnYyOpLPAAABnkS7lojPAAAAAQlnAk0=",true)).complete());
    }
    @Test void explicitFixtureNeedsNoCredential()throws Exception {
        assertTrue(IntakeInventory.observe("fixture").contains("SYNTHETIC_OFFLINE_FIXTURE_NOT_GITHUB"));
    }
    @Test void unauthorizedSuspendsWithoutCheckpointMutation()throws Exception {
        var ledger=new SourceSweepSchedulerTest.Ledger();var before=ledger.cp;AtomicInteger requests=new AtomicInteger();
        var auth=configured(SYNTHETIC);
        var pages=Intake.liveSourcePages(auth.transport(request->{requests.incrementAndGet();return new GitHubDiscovery.Response(401,List.of(),SYNTHETIC);}));
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,pages,s->fail("no mutation log")),clock::get,30000);
        var e=assertThrows(SourceHttpFailure.class,scheduler::tick);assertTrue(e.suspended);
        assertTrue(e.getMessage().contains("invalid GitHub credential"));assertFalse(e.toString().contains(SYNTHETIC));
        clock.set(7*86_400_000_000_000L);assertEquals(SourceSweepScheduler.TickOutcome.SKIPPED,scheduler.tick());
        assertEquals(1,requests.get());assertEquals(before,ledger.cp);assertNull(ledger.committed);
    }
}
