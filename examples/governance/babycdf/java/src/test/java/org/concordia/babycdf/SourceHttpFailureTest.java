// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SourceHttpFailureTest {
    static final Clock CLOCK=GitHubSourcePagesTest.CLOCK;
    static GitHubDiscovery.Response response(int status,Map<String,List<String>> headers) {
        return new GitHubDiscovery.Response(status,List.of(),new ByteArrayInputStream(new byte[0]),headers);
    }
    @Test void headersAreAllowlistedAndDiagnosticIsSanitized()throws Exception {
        try(var r=response(403,Map.of("X-RateLimit-Remaining",List.of("0"),"X-RateLimit-Reset",List.of(""+(CLOCK.instant().getEpochSecond()+3600)),
                "X-GitHub-Request-Id",List.of("ABCD:1234"),"Authorization",List.of("SECRET"),"Retry-After",List.of("unsafe\nINJECT")))) {
            var e=new SourceHttpFailure(r,CLOCK);
            assertEquals(4,r.headers().size());assertFalse(e.getMessage().contains("SECRET"));
            assertFalse(e.getMessage().contains("INJECT"));assertTrue(e.getMessage().contains("x-github-request-id=ABCD:1234"));
            assertTrue(e.suspended,"invalid Retry-After cannot authorize a retry");
        }
    }
    @Test void retryAfterDateSecondsAndResetUseLatestDeadline()throws Exception {
        for(int status:List.of(403,429)) {
            for(String retry:List.of("120","Fri, 20 Nov 2026 12:02:00 GMT")) {
                try(var r=response(status,Map.of("Retry-After",List.of(retry)))) {
                    var e=new SourceHttpFailure(r,CLOCK);assertFalse(e.suspended);assertEquals(120_000_000_000L,e.delayNanos);
                }
            }
            try(var r=response(status,Map.of("Retry-After",List.of("120"),"X-RateLimit-Remaining",List.of("0"),
                    "X-RateLimit-Reset",List.of(""+(CLOCK.instant().getEpochSecond()+3600))))) {
                assertEquals(3600_000_000_000L,new SourceHttpFailure(r,CLOCK).delayNanos);
            }
        }
    }
    @Test void unknownForbiddenOrUnboundedDelaySuspendsWithoutGuessingCause()throws Exception {
        for(int status:List.of(403,429)) {
            for(var headers:List.of(Map.<String,List<String>>of(),Map.of("Retry-After",List.of("-1")),
                    Map.of("Retry-After",List.of("1.5")),Map.of("Retry-After",List.of("86401")),
                    Map.of("Retry-After",List.of("9999999999999999999999999999")),
                    Map.of("X-RateLimit-Remaining",List.of("0")),Map.of("Retry-After",List.of("10","20")))) {
                var e=new SourceHttpFailure(response(status,headers),CLOCK);assertTrue(e.suspended);
                assertFalse(e.getMessage().contains("rate exhausted"));
            }
        }
        assertFalse(new SourceHttpFailure(response(500,Map.of()),CLOCK).suspended);
    }
    @Test void rateFailureClosesUnreadBodyAndPreservesCheckpointUntilRealDeadline()throws Exception {
        for(int status:List.of(403,429)) {
            var ledger=new SourceSweepSchedulerTest.Ledger();
            ledger.cp=new JavaSourceIngestion.Checkpoint("existing",702,"full-v2:issues:3:0::Y3Vyc29yOnYyOpLPAAABnkS7lojPAAAAAQlnAk0=",true);
            var before=ledger.cp;AtomicLong time=new AtomicLong(),requests=new AtomicLong();List<String> logs=new ArrayList<>();
            var closed=new java.util.concurrent.atomic.AtomicBoolean();
            var pages=new GitHubSourcePages((uri,t)->{
                requests.incrementAndGet();
                return new GitHubDiscovery.Response(status,List.of(),new ByteArrayInputStream(new byte[0]) {
                    @Override public int read(){fail("error body must not be read");return -1;}
                    @Override public void close(){closed.set(true);}
                },Map.of("X-RateLimit-Remaining",List.of("0"),"X-RateLimit-Reset",List.of(""+(CLOCK.instant().getEpochSecond()+3600))));
            },CLOCK);
            var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,pages,logs::add),time::get,30_000);
            assertThrows(SourceHttpFailure.class,scheduler::tick);assertTrue(closed.get());
            time.set(30_000_000_000L);assertEquals(SourceSweepScheduler.TickOutcome.SKIPPED,scheduler.tick());
            time.set(3599_999_999_999L);assertEquals(SourceSweepScheduler.TickOutcome.SKIPPED,scheduler.tick());
            assertEquals(1,requests.get());assertEquals(before,ledger.cp);assertNull(ledger.committed);assertTrue(logs.isEmpty());
            time.set(3600_000_000_000L);assertThrows(SourceHttpFailure.class,scheduler::tick);assertEquals(2,requests.get());
        }
    }
    @Test void unknown403IsNotUnconditionallyRetried()throws Exception {
        var ledger=new SourceSweepSchedulerTest.Ledger();AtomicLong time=new AtomicLong(),requests=new AtomicLong();
        var pages=new GitHubSourcePages((uri,t)->{requests.incrementAndGet();return response(403,Map.of());},CLOCK);
        var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(ledger,pages,s->{}),time::get,30_000);
        assertThrows(SourceHttpFailure.class,scheduler::tick);
        time.set(7*86_400_000_000_000L);assertEquals(SourceSweepScheduler.TickOutcome.SKIPPED,scheduler.tick());assertEquals(1,requests.get());
    }
}
