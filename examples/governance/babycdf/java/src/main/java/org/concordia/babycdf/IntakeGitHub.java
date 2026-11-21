// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit file-backed credential, confined to the fixed public repository. Never ambient credentials. */
final class IntakeGitHub {
    private static final int MAX_TOKEN_BYTES=4096;
    private final String token;
    private IntakeGitHub(String token) { this.token=token; }
    @Override public String toString() { return "IntakeGitHub[credential redacted]"; }
    static IntakeGitHub configured(Map<String,String> env) throws IOException {
        String file=env.get("INTAKE_GITHUB_TOKEN_FILE");
        if(file==null || file.isBlank()) throw blocked();
        byte[] bytes=new byte[MAX_TOKEN_BYTES+2];
        try {
            Path path=Path.of(file);
            if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) throw blocked();
            // NOFOLLOW on open also rejects a final-component symlink substituted after validation.
            try(SeekableByteChannel channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining() && channel.read(buffer)!=-1) { }
                int length=buffer.position();
                if(length>0 && bytes[length-1]=='\n')length--;
                if(length<1 || length>MAX_TOKEN_BYTES)throw blocked();
                for(int i=0;i<length;i++)if(bytes[i]<33 || bytes[i]>126)throw blocked();
                return new IntakeGitHub(new String(bytes,0,length,java.nio.charset.StandardCharsets.US_ASCII));
            }
        } catch(IOException | RuntimeException failure) {
            // Do not include path, file contents, or underlying exception/cause.
            throw blocked();
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    void authorizeRead(HttpRequest.Builder request,URI uri) throws IOException {
        if(uri==null||!"https".equals(uri.getScheme())||!"api.github.com".equals(uri.getRawAuthority())||uri.getUserInfo()!=null||uri.getFragment()!=null)
            throw new IOException("BLOCKED: unsafe GitHub target");
        request.header("Authorization","Bearer "+token);
    }
    private static IOException blocked() {
        return new IOException("BLOCKED: live GitHub requires a readable regular non-symlink INTAKE_GITHUB_TOKEN_FILE containing one bounded ASCII token");
    }
    @FunctionalInterface interface Sender {
        GitHubDiscovery.Response send(HttpRequest request) throws IOException,InterruptedException;
    }
    static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }
    GitHubDiscovery.Transport transport() {
        HttpClient client=client();
        return transport(request->{
            var r=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            return new GitHubDiscovery.Response(r.statusCode(),r.headers().allValues("Link"),r.body(),r.headers().map());
        });
    }
    GitHubDiscovery.Transport transport(Sender sender) {
        return (uri,timeout)->{
            if(!safeTarget(uri))throw new IOException("BLOCKED: unsafe GitHub target");
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept","application/vnd.github+json")
                    .header("X-GitHub-Api-Version","2022-11-28")
                    .header("Authorization","Bearer "+token).GET().build();
            try { return sender.send(request); }
            catch(IOException | RuntimeException failure) {
                // HTTP/client exception messages may contain request headers; never propagate them.
                throw new IOException("GitHub transport failed (details redacted)");
            } catch(InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("GitHub transport interrupted");
            }
        };
    }
    private static boolean safeTarget(URI uri) {
        if(uri==null || !"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getRawAuthority())
                || uri.getFragment()!=null || uri.getUserInfo()!=null || uri.toString().length()>4096)return false;
        if(!Set.of("/repos/canton-foundation/canton-dev-fund/pulls","/repos/canton-foundation/canton-dev-fund/issues").contains(uri.getRawPath()))return false;
        String query=uri.getRawQuery();if(query==null)return false;
        Map<String,String> values=new HashMap<>();
        for(String part:query.split("&",-1)) {
            int equals=part.indexOf('=');if(equals<1)return false;
            if(values.putIfAbsent(part.substring(0,equals),part.substring(equals+1))!=null)return false;
        }
        if(!"all".equals(values.get("state")))return false;
        if("100".equals(values.get("per_page"))) {
            if(!Set.of("state","per_page","page").containsAll(values.keySet()))return false;
        } else if("50".equals(values.get("per_page"))) {
            if(!"updated".equals(values.get("sort")) || !"asc".equals(values.get("direction"))
                    || !Set.of("state","per_page","sort","direction","page","after").containsAll(values.keySet()))return false;
            String after=values.get("after");
            if(after!=null && (!uri.getPath().endsWith("/issues") || after.length()>1536
                    || !after.matches("(?:[A-Za-z0-9+/_=-]|%[0-9A-Fa-f]{2})+")))return false;
        } else return false;
        return !values.containsKey("page") || values.get("page").matches("[1-9][0-9]{0,8}");
    }
}
