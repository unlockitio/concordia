// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** One bounded request per engine cycle. Tokens are data, never URLs. Full sweeps only. */
public final class GitHubSourcePages implements JavaSourceIngestion.Discovery {
    private static final String ROOT="https://api.github.com/repos/canton-foundation/canton-dev-fund/";
    // GitHub's observed pagination alias; independently verified against repository metadata.
    private static final String CANONICAL_PATH="/repositories/1156484704/";
    private static final int RESPONSE_LIMIT=2*1024*1024;
    // Conservative UTF-8/protobuf envelope reserve; exact adapter limit remains fail closed.
    private static final int OBSERVATION_BUDGET=700_000;
    private final GitHubDiscovery.Transport transport;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(256*1024).maxNumberLength(20).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public GitHubSourcePages(GitHubDiscovery.Transport transport,Clock clock) {
        this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
    }
    // Observed GitHub cursor is standard padded base64. Keep it opaque and bounded;
    // never persist a supplied URL or use it as a repository/query selector.
    private static final int AFTER_LIMIT=128;
    private static boolean validAfter(String after) {
        if(after.isEmpty() || after.length()>AFTER_LIMIT)return false;
        boolean url=after.indexOf('-')>=0 || after.indexOf('_')>=0;
        if(!after.matches(url?"[A-Za-z0-9_-]+={0,2}":"[A-Za-z0-9+/]+={0,2}"))return false;
        // Validate canonical padding and pad bits in the original alphabet. Never normalize
        // opaque cursor bytes; GitHub has returned padded Base64URL as well as Base64.
        try{return (url?Base64.getUrlEncoder():Base64.getEncoder()).encodeToString(
                (url?Base64.getUrlDecoder():Base64.getDecoder()).decode(after)).equals(after);}
        catch(IllegalArgumentException invalid){return false;}
    }
    private record Cursor(String endpoint,int page,int index,String digest,String after) {
        Cursor(String endpoint,int page,int index,String digest){this(endpoint,page,index,digest,"");}
        String token(){return after.isEmpty()?"full-v1:"+endpoint+":"+page+":"+index+":"+digest
                :"full-v2:"+endpoint+":"+page+":"+index+":"+digest+":"+after;}
    }
    private static Cursor cursor(String token) throws IOException {
        if(token.isEmpty()) return new Cursor("pulls",1,0,"");
        String[] p=token.split(":",-1);
        boolean v2=p.length==6 && p[0].equals("full-v2");
        if(!(p.length==5 && p[0].equals("full-v1") || v2) || !Set.of("pulls","issues").contains(p[1])
                || !p[2].matches("[1-9][0-9]{0,6}") || !p[3].matches("[0-9]{1,2}")
                || !(p[4].isEmpty() || p[4].matches("[a-f0-9]{64}"))) throw new IOException("invalid full-sweep cursor");
        int index=Integer.parseInt(p[3]);
        if(index>49 || (index>0 && p[4].isEmpty()))throw new IOException("invalid page remainder cursor");
        if(v2 && (!validAfter(p[5]) || !p[1].equals("issues") || Integer.parseInt(p[2])<2))
            throw new IOException("invalid pagination cursor");
        return new Cursor(p[1],Integer.parseInt(p[2]),index,p[4],v2?p[5]:"");
    }
    @Override public JavaSourceIngestion.Page next(JavaSourceIngestion.Checkpoint checkpoint) throws IOException {
        Cursor c=cursor(checkpoint.token());
        URI uri=URI.create(ROOT+c.endpoint()+"?state=all&per_page=50&sort=updated&direction=asc&page="+c.page()
                +(c.after().isEmpty()?"":"&after="+java.net.URLEncoder.encode(c.after(),java.nio.charset.StandardCharsets.UTF_8)));
        try(var response=transport.get(uri,Duration.ofSeconds(5))) {
            if(response.status()!=200)throw new SourceHttpFailure(response,clock);
            byte[] bytes;
            // A streaming response needs a body deadline as well as the transport header deadline.
            try(var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
                var timeout=timer.schedule(()->{try{response.close();}catch(IOException ignored){}},5,java.util.concurrent.TimeUnit.SECONDS);
                try {bytes=response.body().readNBytes(RESPONSE_LIMIT+1);}finally{timeout.cancel(false);}
            }
            if(bytes.length>RESPONSE_LIMIT)throw new IOException("GitHub page exceeds response bound");
            JsonNode rows=mapper.readTree(bytes);
            if(rows==null || !rows.isArray() || rows.size()>50)throw new IOException("invalid GitHub source page");
            // Bind a split remainder to exact source fields, excluding receipt time. A changed page
            // fails closed instead of interpreting an old item index against different data.
            String digest=SourceObservation.hash(rows.toString());
            if(c.index()>0 && !digest.equals(c.digest())) {
                // Rewind this page durably before interpreting any shifted indices. Engine
                // fingerprint reconciliation suppresses already confirmed records on reread.
                return new JavaSourceIngestion.Page(List.of(),new Cursor(c.endpoint(),c.page(),0,"",c.after()).token(),false);
            }
            if(c.index()>rows.size())throw new IOException("GitHub remainder no longer exists");
            List<SourceObservation> all=new ArrayList<>();
            Set<Long> seen=new HashSet<>();
            var receipt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for(JsonNode row:rows) {
                SourceObservation o=SourceObservation.parse(row,c.endpoint().equals("pulls"),receipt);
                if(o!=null && !seen.add(o.number()))throw new IOException("duplicate source number in page");
                all.add(o);
            }
            List<SourceObservation> accepted=new ArrayList<>();
            int size=0,index=c.index();
            for(;index<all.size();index++) {
                SourceObservation o=all.get(index);
                if(o==null)continue;
                // Measure the actual certified protobuf observation; reserve 300KB for
                // the bounded command/checkpoint envelope. Adapter rechecks exact request bytes.
                int cost=SourceCatalogueCodec.observation(o).getSerializedSize()+1024;
                if(cost>OBSERVATION_BUDGET)throw new IOException("single source exceeds batch budget");
                if(size+cost>OBSERVATION_BUDGET)break;
                accepted.add(o);size+=cost;
            }
            if(index<all.size())return new JavaSourceIngestion.Page(accepted,new Cursor(c.endpoint(),c.page(),index,digest,c.after()).token(),false);
            Cursor next=nextCursor(response.links(),uri,c);
            if(next!=null)return new JavaSourceIngestion.Page(accepted,next.token(),false);
            if(c.endpoint().equals("pulls"))return new JavaSourceIngestion.Page(accepted,new Cursor("issues",1,0,"").token(),false);
            return new JavaSourceIngestion.Page(accepted,"",true);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("source discovery interrupted",e);}
        catch(IllegalArgumentException e){throw new IOException("invalid source page",e);}
    }
    private static List<String> sortedQuery(URI uri) {
        // Preserve multiplicity: repeated parameters are not equivalent to a single value.
        List<String> parameters=new ArrayList<>(Arrays.asList(uri.getRawQuery().split("&",-1)));
        Collections.sort(parameters);
        return parameters;
    }
    private static Cursor nextCursor(List<String> links,URI current,Cursor c)throws IOException {
        Cursor found=null;
        URI expected=URI.create(ROOT+c.endpoint()+"?state=all&per_page=50&sort=updated&direction=asc&page="+(c.page()+1));
        for(String header:links)for(String part:header.split(",")) {
            String[] sections=part.trim().split(";");boolean next=false;
            for(int i=1;i<sections.length;i++)if(sections[i].trim().matches("rel=\"?next\"?"))next=true;
            if(!next)continue;
            String target=sections[0].trim();
            if(found!=null || !target.startsWith("<") || !target.endsWith(">"))throw new IOException("invalid next link");
            URI actual=current.resolve(target.substring(1,target.length()-1));
            // Query order is not significant, but every parameter and origin is bound.
            if(!Objects.equals(expected.getScheme(),actual.getScheme()) || !Objects.equals(expected.getRawAuthority(),actual.getRawAuthority())
                    || !(expected.getRawPath().equals(actual.getRawPath()) || (CANONICAL_PATH+c.endpoint()).equals(actual.getRawPath()))
                    || actual.getUserInfo()!=null || actual.getFragment()!=null || actual.toString().length()>2048 || actual.getRawQuery()==null
                    )
                throw new IOException("unsafe next link");
            List<String> query=sortedQuery(actual);
            String after="";
            for(var iterator=query.iterator();iterator.hasNext();) {
                String parameter=iterator.next();
                if(parameter.startsWith("after=")) {
                    if(!after.isEmpty() || !c.endpoint().equals("issues"))throw new IOException("invalid after cursor");
                    String raw=parameter.substring(6);
                    // Opaque query data, not form data: RFC 3986 permits literal + / =.
                    // Decode percent escapes once only; never translate a literal + to space.
                    if(raw.length()>AFTER_LIMIT*3 || !raw.matches("(?:[A-Za-z0-9+/_=-]|%[0-9A-Fa-f]{2})+"))
                        throw new IOException("invalid after encoding");
                    StringBuilder decoded=new StringBuilder();
                    for(int i=0;i<raw.length();i++) {
                        char ch=raw.charAt(i);
                        if(ch=='%') {ch=(char)Integer.parseInt(raw.substring(i+1,i+3),16);i+=2;}
                        decoded.append(ch);
                    }
                    after=decoded.toString();
                    if(!validAfter(after))throw new IOException("invalid after cursor");
                    iterator.remove();
                }
            }
            if(!sortedQuery(expected).equals(query) || (!c.after().isEmpty() && after.isEmpty())
                    || c.page()>=9_999_999)throw new IOException("unsafe next query");
            found=new Cursor(c.endpoint(),c.page()+1,0,"",after);
        }
        return found;
    }
}
