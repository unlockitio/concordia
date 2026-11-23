// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.net.URI;

/** Bounded resumable evidence fetch; credentials belong only to injected runtime transport. */
public final class SourceEvidenceFetch {
 public record Response(int status,byte[] body,String etag,String next,Map<String,List<String>> headers) {
  public Response {body=body.clone();headers=Map.copyOf(headers);}
 }
 @FunctionalInterface public interface Transport {Response get(URI uri,String etag)throws IOException;}
 public record Page(String source,Instant retrievedAt,byte[] body,String etag) {public Page {body=body.clone();}}
 public record Partial(List<Page> pages,String resume,String status) {public Partial {pages=List.copyOf(pages);}}
 private final Transport transport;private final Clock clock;private final Map<String,Page> cache=new HashMap<>();
 private final Map<String,String> cachedNext=new HashMap<>();
 private long cachedBytes;
 private static final long MAX_CACHE_BYTES=16L*1024*1024;
 private Instant blockedUntil=Instant.EPOCH;private boolean suspended;
 public SourceEvidenceFetch(Transport transport,Clock clock){this.transport=Objects.requireNonNull(transport);this.clock=clock;}
 private static URI safe(String value)throws IOException {
  URI uri;try{uri=URI.create(value);}catch(RuntimeException e){throw new IOException("unsafe evidence URI");}
  if(!"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getRawAuthority()) || uri.getUserInfo()!=null || uri.getFragment()!=null
    || !uri.getPath().startsWith("/repos/canton-foundation/canton-dev-fund/"))throw new IOException("unsafe evidence URI");return uri;
 }
 public synchronized Page fresh(String source)throws IOException {
  URI uri=safe(source);if(suspended||clock.instant().isBefore(blockedUntil))throw new IOException("evidence backoff");
  var response=transport.get(uri,"");
  if(response.status()==403||response.status()==429){
   var policy=new SourceHttpFailure(new GitHubDiscovery.Response(response.status(),List.of(),new java.io.ByteArrayInputStream(new byte[0]),response.headers()),clock);
   suspended=policy.suspended;blockedUntil=clock.instant().plusNanos(policy.delayNanos);
   throw GitHubFetchFailure.http(response.status(),java.net.http.HttpHeaders.of(response.headers(),(a,b)->true),new byte[0]);
  }
  if(response.status()!=200||response.body().length>2*1024*1024)throw new IOException("uncached evidence reread failed");
  return new Page(source,clock.instant(),response.body(),response.etag());
 }
 public synchronized Partial pages(String first,int requestBudget,int pageBudget,Duration totalTime)throws IOException {
  if(requestBudget<1||requestBudget>60||pageBudget<1||pageBudget>100||totalTime.isNegative()||totalTime.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("invalid evidence bounds");
  if(suspended||clock.instant().isBefore(blockedUntil))return new Partial(List.of(),first,"backoff");
  var pages=new ArrayList<Page>();var seen=new HashSet<String>();String next=first;Instant deadline=clock.instant().plus(totalTime);
  for(int count=0;!next.isEmpty();count++) {
   if(count>=requestBudget||pages.size()>=pageBudget||!clock.instant().isBefore(deadline))return new Partial(pages,next,"partial");
   if(!seen.add(next))throw new IOException("evidence pagination cycle");URI uri=safe(next);var previous=cache.get(next);
   Response response=transport.get(uri,previous==null?"":previous.etag());
   if(clock.instant().isAfter(deadline))return new Partial(pages,next,"deadline");
   if(response.status()==403||response.status()==429){
    var policy=new SourceHttpFailure(new GitHubDiscovery.Response(response.status(),List.of(),new java.io.ByteArrayInputStream(new byte[0]),response.headers()),clock);
    suspended=policy.suspended;blockedUntil=clock.instant().plusNanos(policy.delayNanos);
    GitHubFetchFailure categorical=GitHubFetchFailure.http(response.status(),java.net.http.HttpHeaders.of(response.headers(),(a,b)->true),new byte[0]);
    return new Partial(pages,next,categorical.code.name());}
   if(response.status()==304){if(previous==null)throw new IOException("cacheless evidence304");pages.add(previous);}
   else if(response.status()==200){if(response.body().length>2*1024*1024)throw new IOException("evidence response bound");var page=new Page(next,clock.instant(),response.body(),response.etag());if(cache.size()>=256&&!cache.containsKey(next))throw new IOException("evidence cache bound");long replacement=cachedBytes-(previous==null?0:previous.body().length)+page.body().length;if(replacement>MAX_CACHE_BYTES)throw new IOException("evidence cache byte bound");cache.put(next,page);cachedBytes=replacement;cachedNext.put(next,response.next());pages.add(page);}
   else return new Partial(pages,next,"http_"+response.status());
   next=response.status()==304?cachedNext.getOrDefault(next,""):response.next();if(!next.isEmpty())safe(next);
  }return new Partial(pages,"","complete");
 }
 private static String single(Map<String,List<String>> h,String key){return h.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).filter(e->e.getValue().size()==1).map(e->e.getValue().getFirst()).findFirst().orElse("");}
}
