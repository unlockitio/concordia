// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubSourcePagesTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"),ZoneOffset.UTC);
    static String row(long n,String title,String body,boolean pull)throws Exception {
        return new ObjectMapper().writeValueAsString(new LinkedHashMap<>(Map.of("number",n,"title",title,"body",body,
                "html_url","https://github.com/canton-foundation/canton-dev-fund/"+(pull?"pull/":"issues/")+n,
                "state","open","updated_at","2026-01-01T00:00:00Z"))) .replace("}",pull?",\"merged_at\":null}":"}");
    }
    static JavaSourceIngestion.Checkpoint cp(String token){return new JavaSourceIngestion.Checkpoint("cid",1,token,true);}
    @Test void capturedBase64UrlPageThreeAdvancesAndPreservesExactCursor()throws Exception {
        String after="Y3Vyc29yOnYyOpLPAAABnpfyZhDPAAAAARH-jdE=";
        String captured="<https://api.github.com/repositories/1156484704/issues?state=all&per_page=50&sort=updated&direction=asc&page=4&after=Y3Vyc29yOnYyOpLPAAABnpfyZhDPAAAAARH-jdE%3D>; rel=\"next\"";
        var page=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(captured),"[]"),CLOCK)
                .next(cp("full-v2:issues:3:0::"+AFTER));
        assertEquals("full-v2:issues:4:0::"+after,page.nextToken());
        var later=new GitHubSourcePages((uri,t)->{
            assertEquals("state=all&per_page=50&sort=updated&direction=asc&page=4&after="+after.replace("=","%3D"),uri.getRawQuery());
            return new GitHubDiscovery.Response(200,List.of(nextLink("issues",5).replace(">;","&after=_w%3D%3D>;")),"[]");
        },CLOCK).next(cp(page.nextToken()));
        assertEquals("full-v2:issues:5:0::_w==",later.nextToken());
    }
    @Test void base64UrlBoundariesRejectMixedAndNoncanonicalForms()throws Exception {
        for(String after:List.of("-_8=","_w==","-w==")) {
            for(String raw:List.of(after,after.replace("-","%2D").replace("_","%5F").replace("=","%3D"))) {
                var page=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,
                        List.of(nextLink("issues",3).replace(">;","&after="+raw+">;")),"[]"),CLOCK)
                        .next(cp("full-v2:issues:2:0::"+after));
                assertEquals("full-v2:issues:3:0::"+after,page.nextToken());
            }
        }
        for(String after:List.of("+_8=","-/8=","-_9=","_x==","_w===","_w","-_8","_w==%","_w==\n","_w==é","_".repeat(129))) {
            var pages=new GitHubSourcePages((uri,t)->{fail("invalid checkpoint must not request");return null;},CLOCK);
            assertThrows(IOException.class,()->pages.next(cp("full-v2:issues:2:0::"+after)),after);
            String raw=java.net.URLEncoder.encode(after,java.nio.charset.StandardCharsets.UTF_8);
            var headers=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,
                    List.of(nextLink("issues",2).replace(">;","&after="+raw+">;")),"[]"),CLOCK);
            var checkpoint=cp("full-v1:issues:1:0:");
            var ledger=new JavaSourceIngestionTest.Ledger();ledger.checkpoint=checkpoint;
            var logs=new ArrayList<String>();
            var engine=new JavaSourceIngestion(ledger,headers,logs::add);
            assertThrows(IOException.class,engine::cycle,after);
            assertEquals(checkpoint,ledger.checkpoint);assertEquals(0,ledger.calls);
            assertTrue(ledger.items.isEmpty());assertTrue(logs.isEmpty());
        }
    }
    @Test void opaqueCursorRepresentationsPreserveBytesAndAdoptNewBoundary()throws Exception {
        // Synthetic byte patterns, not claimed captured upstream headers.
        for(String after:List.of("+/8=","/w==","YWJj","A".repeat(128))) {
            String encoded=after.replace("+","%2B").replace("/","%2f").replace("=","%3d");
            String allEncoded=after.chars().mapToObj(c->String.format("%%%02X",c)).collect(java.util.stream.Collectors.joining());
            for(String raw:List.of(after,encoded,allEncoded)) {
                String link=nextLink("issues",3).replace(">;","&after="+raw+">;");
                var page=new GitHubSourcePages((uri,t)->{
                    assertTrue(uri.getRawQuery().endsWith("&after=QQ%3D%3D"));
                    return new GitHubDiscovery.Response(200,List.of(link),"[]");
                },CLOCK).next(cp("full-v2:issues:2:0::QQ=="));
                assertEquals("full-v2:issues:3:0::"+after,page.nextToken());
                assertArrayEquals(Base64.getDecoder().decode(after),Base64.getDecoder().decode(page.nextToken().substring(page.nextToken().lastIndexOf(':')+1)));
            }
        }
        for(String raw:List.of("%252B%252F8%253D","+%20/8=","+/9=","/x==","QQ===","QQ","QQ==&after=QQ==","QQ==;evil=1","A".repeat(132))) {
            var pages=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(nextLink("issues",2).replace(">;","&after="+raw+">;")),"[]"),CLOCK);
            assertThrows(IOException.class,()->pages.next(cp("full-v1:issues:1:0:")),raw);
        }
    }
    @Test void endpointsAreBoundAndOrdinaryIssuesExcluded()throws Exception {
        List<String> urls=new ArrayList<>();
        var pages=new GitHubSourcePages((uri,timeout)->{urls.add(uri.toString());return new GitHubDiscovery.Response(200,List.of(),
                uri.getPath().endsWith("pulls")?"["+rowUnchecked(10,"Proposal","",true)+"]":"["+rowUnchecked(11,"Ordinary","",false)+","+rowUnchecked(12,"Grant #10 - Milestone 1: work","",false)+"]");},CLOCK);
        var p=pages.next(cp(""));assertEquals(10,p.items().getFirst().number());assertFalse(p.complete());
        var q=pages.next(cp(p.nextToken()));assertTrue(q.complete());assertEquals(12,q.items().getFirst().number());assertEquals(10L,q.items().getFirst().parentPR());
        assertTrue(urls.stream().allMatch(u->u.contains("per_page=50&sort=updated&direction=asc")));
        assertThrows(IOException.class,()->pages.next(cp("https://evil.invalid")));
    }
    static String rowUnchecked(long n,String title,String body,boolean pull){try{return row(n,title,body,pull);}catch(Exception e){throw new RuntimeException(e);}}
    @Test void splitRemainderResumesAfterRestartAndChangedPageFailsClosed()throws Exception {
        List<String> rows=new ArrayList<>();for(int i=1;i<=50;i++)rows.add(row(i,"P"+i,"x".repeat(32768),true));
        String body="["+String.join(",",rows)+"]";
        var first=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),body),CLOCK).next(cp(""));
        assertTrue(first.items().size()<50);assertFalse(first.complete());
        Set<Long> numbers=new HashSet<>();first.items().forEach(o->assertTrue(numbers.add(o.number())));
        String token=first.nextToken();int chunks=1;
        while(!token.equals("full-v1:issues:1:0:")) {
            assertTrue(chunks++<10,"split loop bounded");
            var next=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),body),CLOCK).next(cp(token));
            next.items().forEach(o->assertTrue(numbers.add(o.number()),"duplicate row after restart"));
            token=next.nextToken();
        }
        assertEquals(java.util.stream.LongStream.rangeClosed(1,50).boxed().collect(java.util.stream.Collectors.toSet()),numbers);
        var changed=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),body.replace("P1\"","changed\"")),CLOCK);
        var rewind=changed.next(cp(first.nextToken()));
        assertTrue(rewind.items().isEmpty());assertEquals("full-v1:pulls:1:0:",rewind.nextToken());
    }
    static final String AFTER="Y3Vyc29yOnYyOpLPAAABnbtmBpjO8ZWiWQ==";
    static final String OBSERVED="<https://api.github.com/repositories/1156484704/issues?state=all&per_page=50&sort=updated&direction=asc&page=2&after=Y3Vyc29yOnYyOpLPAAABnbtmBpjO8ZWiWQ%3D%3D>; rel=\"next\"";
    @Test void observedIssuesCursorSurvivesRestartAndUsesPinnedOutboundRepository()throws Exception {
        var first=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(OBSERVED),"[]"),CLOCK)
                .next(cp("full-v1:issues:1:0:"));
        assertEquals("full-v2:issues:2:0::"+AFTER,first.nextToken());assertFalse(first.complete());
        List<String> requests=new ArrayList<>();
        var second=new GitHubSourcePages((uri,t)->{requests.add(uri.toString());return new GitHubDiscovery.Response(200,List.of(),"[]");},CLOCK)
                .next(cp(first.nextToken()));
        assertTrue(second.complete());
        assertEquals(List.of("https://api.github.com/repos/canton-foundation/canton-dev-fund/issues?state=all&per_page=50&sort=updated&direction=asc&page=2&after=Y3Vyc29yOnYyOpLPAAABnbtmBpjO8ZWiWQ%3D%3D"),requests);
    }
    @Test void subsequentCursorPagesAdvanceWithoutDroppingBoundaryOrSkipping()throws Exception {
        List<String> requests=new ArrayList<>();String token="full-v1:issues:1:0:";
        for(int pageNumber=1;pageNumber<=3;pageNumber++) {
            final int n=pageNumber;
            var page=new GitHubSourcePages((uri,t)->{
                requests.add(uri.toString());
                String link=OBSERVED.replace("page=2&","page="+(n+1)+"&");
                return new GitHubDiscovery.Response(200,n<3?List.of(link):List.of(),"[]");
            },CLOCK).next(cp(token));
            token=page.nextToken();assertEquals(n==3,page.complete());
        }
        assertEquals(3,requests.size());
        for(int i=0;i<3;i++)assertTrue(requests.get(i).contains("page="+(i+1)));
        assertTrue(requests.get(1).contains("&after="));assertTrue(requests.get(2).contains("&after="));
        var missing=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(nextLink("issues",3)),"[]"),CLOCK);
        assertThrows(IOException.class,()->missing.next(cp("full-v2:issues:2:0::"+AFTER)));
    }
    @Test void issueSplitAndDigestRewindPreservePageBoundaryCursor()throws Exception {
        List<String> rows=new ArrayList<>();for(int i=1;i<=50;i++)rows.add(row(i,"Grant #100 - Milestone "+i+": work","x".repeat(32768),false));
        String boundary="Y3Vyc29yOnYyOpLPAAABnpfyZhDPAAAAARH-jdE=";
        String body="["+String.join(",",rows)+"]",start="full-v2:issues:2:0::"+boundary;
        var first=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),body),CLOCK).next(cp(start));
        assertFalse(first.complete());assertTrue(first.nextToken().endsWith(":"+boundary));assertTrue(first.nextToken().length()<512);
        var rewind=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),body.replace("work","changed")),CLOCK).next(cp(first.nextToken()));
        assertEquals(start,rewind.nextToken());assertTrue(rewind.items().isEmpty());
        Set<Long> seen=new HashSet<>();first.items().forEach(o->assertTrue(seen.add(o.number())));
        String token=first.nextToken();int cycles=0;
        while(!token.isEmpty()) {
            assertTrue(cycles++<10);
            var page=new GitHubSourcePages((uri,t)->{assertTrue(uri.toString().endsWith("&after="+boundary.replace("=","%3D")));return new GitHubDiscovery.Response(200,List.of(),body);},CLOCK).next(cp(token));
            page.items().forEach(o->assertTrue(seen.add(o.number())));token=page.nextToken();
        }
        assertEquals(50,seen.size());
    }
    @Test void observedCursorDoesNotRelaxScopeOrPermitUntrustedData() {
        for(String link:List.of(OBSERVED.replace("1156484704","1156484705"),OBSERVED.replace("api.github.com","evil.invalid"),
                OBSERVED.replace("/issues?","/pulls?"),OBSERVED.replace("page=2&","page=3&"),
                OBSERVED.replace("state=all","state=open"),OBSERVED.replace("sort=updated","sort=created"),
                OBSERVED.replace("direction=asc","direction=desc"),OBSERVED.replace("&after=","&labels=bug&after="),
                OBSERVED.replace("&after=","&since=2026-01-01&after="),OBSERVED.replace("&after=","&after=QQ%3D%3D&after="),
                OBSERVED.replace("&after=","&state=all&after="),OBSERVED.replace("%3D%3D","%0A"),
                OBSERVED.replace("%3D%3D","%ZZ"),OBSERVED.replace(AFTER.substring(0,32),"A".repeat(132)))) {
            var pages=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(link),"[]"),CLOCK);
            assertThrows(IOException.class,()->pages.next(cp("full-v1:issues:1:0:")),link);
        }
        for(String token:List.of("full-v2:issues:2:0::", "full-v2:issues:1:0::"+AFTER,"full-v2:pulls:2:0::"+AFTER,
                "full-v2:issues:2:0::https://evil.invalid", "full-v2:issues:2:0::"+"A".repeat(132),"full-v2:issues:2:0::QQ==\n")) {
            var pages=new GitHubSourcePages((uri,t)->{fail("invalid checkpoint must not request");return null;},CLOCK);
            assertThrows(IOException.class,()->pages.next(cp(token)),token);
        }
    }
    static String nextLink(String endpoint,int page) {
        return "<https://api.github.com/repos/canton-foundation/canton-dev-fund/"+endpoint+"?page="+page+"&direction=asc&sort=updated&per_page=50&state=all>; rel=\"next\"";
    }
    @Test void validReorderedNextLinksTraverseBothEndpointsAndCompleteFullSweep()throws Exception {
        List<String> requests=new ArrayList<>(),tokens=new ArrayList<>();
        var pages=new GitHubSourcePages((uri,t)->{
            String endpoint=uri.getPath().endsWith("pulls")?"pulls":"issues";
            boolean first=uri.getQuery().endsWith("page=1");requests.add(endpoint+":"+(first?1:2));
            return new GitHubDiscovery.Response(200,first?List.of(nextLink(endpoint,2)):List.of(),"[]");
        },CLOCK);
        String token="";
        for(int i=0;i<4;i++) {
            var page=pages.next(cp(token));token=page.nextToken();tokens.add(token);
            assertEquals(i==3,page.complete());
        }
        assertEquals(List.of("pulls:1","pulls:2","issues:1","issues:2"),requests);
        assertEquals(List.of("full-v1:pulls:2:0:","full-v1:issues:1:0:","full-v1:issues:2:0:",""),tokens);
    }
    @Test void nextLinksRejectForeignOriginWrongEndpointAndWrongPage() {
        String valid=nextLink("pulls",2);
        for(String link:List.of(valid.replace("api.github.com","evil.invalid"),valid.replace("/pulls?","/issues?"),valid.replace("page=2&","page=3&"))) {
            var pages=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(link),"[]"),CLOCK);
            assertThrows(IOException.class,()->pages.next(cp("")),link);
        }
    }
    @Test void observedNumericRepositoryHeaderAdvancesExactlyOnePage() throws Exception {
        String observed="<https://api.github.com/repositories/1156484704/pulls?state=all&per_page=50&sort=updated&direction=asc&page=2>; rel=\"next\", <https://api.github.com/repositories/1156484704/pulls?state=all&per_page=50&sort=updated&direction=asc&page=15>; rel=\"last\"";
        List<String> requests=new ArrayList<>();
        var pages=new GitHubSourcePages((uri,t)->{
            requests.add(uri.toString());
            return new GitHubDiscovery.Response(200,requests.size()==1?List.of(observed):List.of(),"[]");
        },CLOCK);
        var first=pages.next(cp(""));
        assertEquals("full-v1:pulls:2:0:",first.nextToken());assertFalse(first.complete());
        var second=pages.next(cp(first.nextToken()));
        assertEquals("full-v1:issues:1:0:",second.nextToken());
        assertEquals(List.of("https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=50&sort=updated&direction=asc&page=1",
                "https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=50&sort=updated&direction=asc&page=2"),requests);
    }
    @Test void numericAliasTraversesBothEndpointsWithoutSkippingPages() throws Exception {
        List<String> requests=new ArrayList<>();
        var pages=new GitHubSourcePages((uri,t)->{
            String endpoint=uri.getPath().endsWith("pulls")?"pulls":"issues";
            boolean first=uri.getQuery().endsWith("page=1");requests.add(endpoint+":"+(first?1:2));
            String link=nextLink(endpoint,2).replace("/repos/canton-foundation/canton-dev-fund/","/repositories/1156484704/");
            return new GitHubDiscovery.Response(200,first?List.of(link):List.of(),"[]");
        },CLOCK);
        String token="";
        for(int i=0;i<4;i++) {var page=pages.next(cp(token));token=page.nextToken();assertEquals(i==3,page.complete());}
        assertEquals(List.of("pulls:1","pulls:2","issues:1","issues:2"),requests);assertEquals("",token);
    }
    @Test void numericAliasDoesNotRelaxOriginEntityQueryOrPage() {
        String valid="<https://api.github.com/repositories/1156484704/pulls?state=all&per_page=50&sort=updated&direction=asc&page=2>; rel=\"next\"";
        for(String link:List.of(valid.replace("1156484704","1156484705"),valid.replace("/pulls?","/issues?"),
                valid.replace("api.github.com","evil.invalid"),valid.replace("https:","http:"),
                valid.replace("api.github.com","user@api.github.com"),valid.replace("page=2>","page=3>"),
                valid.replace("page=2>","page=0>"),valid.replace("page=2>","page=2#fragment>"),
                valid.replace("state=all&","state=all&state=all&"),valid.replace("sort=updated&",""),
                valid.replace("page=2>","page=2&extra=1>"),valid.replace("?state=all&per_page=50&sort=updated&direction=asc&page=2",""),
                valid+", "+valid,valid.replace("page=2>","page=2&extra="+"x".repeat(2048)+">"))) {
            var pages=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(link),"[]"),CLOCK);
            assertThrows(IOException.class,()->pages.next(cp("")),link);
        }
    }
    @Test void realHttpTruncatedBodyClosesAndNeverReturnsAdvancingPage() throws Exception {
        try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
            var workers=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var served=workers.submit(()->{
                try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var input=socket.getInputStream();int matched=0;
                    byte[] end="\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    for(int count=0;count<8192 && matched<4;count++) {
                        int b=input.read();if(b<0)throw new IOException("request closed");
                        matched=b==end[matched]?matched+1:0;
                    }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n[]".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return true;
            });
            var client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            var closed=new java.util.concurrent.atomic.AtomicBoolean();
            var requests=new java.util.concurrent.atomic.AtomicInteger();
            var pages=new GitHubSourcePages((uri,t)->{
                requests.incrementAndGet();
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+server.getLocalPort()+"/"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200,response.statusCode());
                return new GitHubDiscovery.Response(response.statusCode(),List.of(),new java.io.FilterInputStream(response.body()) {
                    @Override public void close() throws IOException {closed.set(true);super.close();}
                });
            },CLOCK);
            var checkpoint=cp("");
            var ledger=new JavaSourceIngestionTest.Ledger();
            ledger.checkpoint=checkpoint;
            var logs=new ArrayList<String>();
            var engine=new JavaSourceIngestion(ledger,pages,logs::add);
            assertThrows(IOException.class,engine::cycle);
            assertEquals(checkpoint,ledger.checkpoint);assertEquals(0,ledger.calls);
            assertTrue(ledger.items.isEmpty());assertTrue(logs.isEmpty());
            assertEquals("",checkpoint.token());assertTrue(closed.get());assertEquals(1,requests.get());
            assertTrue(served.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void truncatedCompressedBodyFailsClosed() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(bytes)) {gzip.write("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        byte[] truncated=Arrays.copyOf(bytes.toByteArray(),bytes.size()-4);
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var pages=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(truncated)) {
                    @Override public void close() throws IOException {closed.set(true);super.close();}
                }),CLOCK);
        assertThrows(IOException.class,()->pages.next(cp("")));assertTrue(closed.get());
    }
    @Test void rateErrorAndMalformedRecordNeverReturnAdvancingPage() {
        var rate=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(429,List.of(),"[]"),CLOCK);
        assertThrows(IOException.class,()->rate.next(cp("")));
        var malformed=new GitHubSourcePages((uri,t)->new GitHubDiscovery.Response(200,List.of(),"[{\"number\":1}]"),CLOCK);
        assertThrows(IOException.class,()->malformed.next(cp("")));
    }
}
