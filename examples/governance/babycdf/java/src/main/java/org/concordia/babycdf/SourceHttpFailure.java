// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Non-success response metadata only: never logs a body, URL, or arbitrary header. */
final class SourceHttpFailure extends IOException {
    // Long or unknown provider delays suspend this scheduler instead of retrying early.
    static final long MAX_DELAY_SECONDS=86_400;
    final long delayNanos;
    final boolean suspended;
    private record Policy(long seconds,boolean suspend) {}
    SourceHttpFailure(GitHubDiscovery.Response response,Clock clock) {
        this(response,policy(response,clock));
    }
    private SourceHttpFailure(GitHubDiscovery.Response r,Policy p) {
        super(message(r,p));
        delayNanos=p.seconds()*1_000_000_000L;suspended=p.suspend();
    }
    private static String value(GitHubDiscovery.Response r,String name) {
        var values=r.headers().get(name);
        if(values==null || values.size()!=1)return "";
        String v=values.getFirst();
        return v.length()<=128 && v.matches("[A-Za-z0-9 ,:+/=-]*")?v:"";
    }
    private static String message(GitHubDiscovery.Response r,Policy p) {
        StringBuilder s=new StringBuilder("GitHub discovery HTTP "+r.status()+"; checkpoint unchanged");
        for(String name:List.of("x-ratelimit-remaining","x-ratelimit-reset","retry-after","x-github-request-id")) {
            String v=value(r,name);if(!v.isEmpty())s.append("; ").append(name).append('=').append(v);
        }
        if(r.status()==401)s.append("; invalid GitHub credential; automatic requests suspended; replace credential and deliberately restart Intake");
        if(r.status()==403 || r.status()==429)
            s.append(p.suspend()?"; automatic requests suspended (missing/invalid or excessive retry evidence)":"; retry delay seconds="+p.seconds());
        return s.toString();
    }
    private static Policy policy(GitHubDiscovery.Response r,Clock clock) {
        if(r.status()==401)return new Policy(0,true);
        if(r.status()!=403 && r.status()!=429)return new Policy(0,false);
        long delay=0;boolean evidence=false;
        String retry=value(r,"retry-after");
        if(r.headers().containsKey("retry-after") && retry.isEmpty())return new Policy(0,true);
        if(!retry.isEmpty()) {
            try {
                long seconds=retry.matches("[0-9]+")?Long.parseLong(retry):
                        remaining(clock.instant(),ZonedDateTime.parse(retry,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                delay=Math.max(delay,seconds);evidence=true;
            }catch(RuntimeException invalid){return new Policy(0,true);}
        }
        if(value(r,"x-ratelimit-remaining").equals("0")) {
            try {
                String reset=value(r,"x-ratelimit-reset");
                if(!reset.matches("[0-9]+"))return new Policy(0,true);
                delay=Math.max(delay,remaining(clock.instant(),Instant.ofEpochSecond(Long.parseLong(reset))));evidence=true;
            }catch(RuntimeException invalid){return new Policy(0,true);}
        }
        if(!evidence || delay>MAX_DELAY_SECONDS)return new Policy(0,true);
        // Avoid immediate repeated responses with zero or stale provider deadlines.
        return new Policy(Math.max(60,delay),false);
    }
    private static long remaining(Instant now,Instant until) {
        if(!until.isAfter(now))return 0;
        Duration d=Duration.between(now,until);
        return Math.addExact(d.getSeconds(),d.getNano()==0?0:1);
    }
}
