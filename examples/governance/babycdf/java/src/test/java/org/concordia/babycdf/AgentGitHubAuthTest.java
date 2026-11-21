package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
class AgentGitHubAuthTest {
    @Test void fileAuthAndHostRestriction(@TempDir Path root) throws Exception {
        Path token=root.resolve("synthetic"); Files.writeString(token,"synthetic-pat-sentinel\n");
        var auth=IntakeGitHub.configured(Map.of("INTAKE_GITHUB_TOKEN_FILE",token.toString()));
        URI target=URI.create("https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls/184");
        var request=HttpRequest.newBuilder(target); auth.authorizeRead(request,target);
        assertEquals("Bearer synthetic-pat-sentinel",request.GET().build().headers().firstValue("Authorization").orElseThrow());
        var failure=assertThrows(java.io.IOException.class,()->auth.authorizeRead(HttpRequest.newBuilder(URI.create("https://example.com")),URI.create("https://example.com")));
        assertFalse(failure.toString().contains("sentinel")); assertFalse(auth.toString().contains("sentinel"));
        assertDoesNotThrow(()->new Advisory.GitHub(Map.of("GITHUB_TOKEN","ignored")));
        var invalid=assertThrows(IllegalArgumentException.class,()->new Advisory.GitHub(Map.of("INTAKE_GITHUB_TOKEN_FILE","relative")));
        assertFalse(invalid.toString().contains("relative"));
    }
    @Test void fixtureManifest() throws Exception {
        Path fixture=Path.of("../demo/fixtures");
        for(String line:Files.readAllLines(fixture.resolve("SHA256SUMS"))) {
            String[] parts=line.split("  ",2); Path file=fixture.resolve(Path.of(parts[1]).getFileName());
            assertEquals(parts[0],HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        var issue=Advisory.JSON.readTree(Files.readAllBytes(fixture.resolve("issue540.json")));
        assertEquals(540,issue.path("number").asInt()); assertTrue(issue.path("title").asText().startsWith("Concordia #184"));
        assertFalse(issue.path("body").asText().isBlank());
    }
}
