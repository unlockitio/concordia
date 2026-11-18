// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
class OfflineFileTest {
    @TempDir Path root;
    @Test void corruptedFixtureDoesNotBecomePinnedEvidence() throws Exception {
        Path file = root.resolve("proposal.txt");
        Files.writeString(file, "not the pinned blob");
        var result = Advisory.collect(new OfflineFile(file));
        assertFalse(result.path("errors").isEmpty());
        for (var item : result.path("inputs")) assertFalse(item.path("pinned").asBoolean());
    }
    @Test void missingFileFailsClosed() {
        assertThrows(java.io.IOException.class, () -> new OfflineFile(root.resolve("missing")));
    }
}
