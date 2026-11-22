// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Explicit offline PR184 seed, not fresh GitHub discovery. collect still verifies blob bytes. */
final class OfflineFile implements Advisory.Api {
    private static final String BLOB = "f591787a772848e4537aba5d8ba02be1764647da";
    private final byte[] bytes;
    OfflineFile(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
            throw new IOException("offline fixture must be a direct regular file");
        bytes = Files.readAllBytes(path);
    }
    public void logMode(String agent,java.io.PrintStream log){log.println("["+agent+"] GitHub mode=offline_fixture");}
    private JsonNode issue() throws IOException {
        Path root=Path.of(System.getProperty("babycdf.fixtures.root",Files.isDirectory(Path.of("/app/fixtures"))?"/app/fixtures":"../demo/fixtures"));
        return Advisory.JSON.readTree(Files.readAllBytes(root.resolve("issue540.json")));
    }
    public boolean offlineSeed() { return true; }
    public JsonNode get(String path) throws IOException {
        if (path.startsWith("search/issues?"))
            return Advisory.JSON.readTree(path.contains("is%3Apr")
                    ? "{\"items\":[{\"number\":184}]}" : issue().path("title").asText().startsWith("Concordia #184 - Milestone ")?"{\"items\":[{\"number\":540}]}":"{\"items\":[]}");
        if(path.equals(Advisory.PREFIX+"/issues/540"))return issue();
        if (path.endsWith("/pulls/184"))
            return Advisory.JSON.readTree("{\"number\":184,\"head\":{\"sha\":\"c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521\"},\"html_url\":\"https://github.com/canton-foundation/canton-dev-fund/pull/184\"}");
        if (path.contains("/pulls/184/files?"))
            return Advisory.JSON.readTree("[{\"filename\":\"proposals/concordia.md\",\"sha\":\"" + BLOB + "\"}]");
        if (path.endsWith("/git/blobs/" + BLOB))
            return Advisory.JSON.createObjectNode().put("sha", BLOB).put("encoding", "base64")
                    .put("content", Base64.getEncoder().encodeToString(bytes));
        throw new GitHubFetchFailure(GitHubFetchFailure.Code.offline_no_evidence);
    }
}
