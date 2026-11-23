// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

class SourceCatalogueDigestTest {
    @TempDir Path journal;
    @Test void showEscapesAreNotJson() {
        assertEquals("\"caf\\233\"",SourceCatalogueDigest.text("café"));
        assertEquals("\"\\233\\&2\"",SourceCatalogueDigest.text("é2"));
        assertEquals("\"\\128512\"",SourceCatalogueDigest.text("😀"));
        assertEquals("\"\\SO\\&H\"",SourceCatalogueDigest.text("\u000eH"));
        assertEquals("\"line\\n\\t\\\"\\\\\"",SourceCatalogueDigest.text("line\n\t\"\\"));
    }
    @Test void digestIsNotJavaFingerprintAndExcludesReceiptTime() throws Exception {
        var o=SourceCatalogueCodecTest.sample();
        var later=new SourceObservation(o.number(),o.kind(),o.url(),o.title(),o.bodySha256(),o.state(),o.updatedAt(),
                o.observedAt().plusSeconds(5),o.parentPR(),o.linkEvidence(),o.merged(),o.observedDigest(),o.truncated());
        var digest=SourceCatalogueDigest.digest(SourceCatalogueCodec.observation(o).getRecord());
        assertEquals(o.fingerprint(),digest);
        assertEquals(digest,SourceCatalogueDigest.digest(SourceCatalogueCodec.observation(later).getRecord()));
    }
    @Test void receiptNamespaceAndSymlinkRemainFailClosed() throws Exception {
        journal=journal.toRealPath();
        String command="babycdf-source-v2-"+"b".repeat(64);
        IntakeLedger.recordSourceMarker(journal,command+".receipt",new byte[]{9});
        assertThrows(IOException.class,()->IntakeLedger.recordSourceMarker(journal,command+".receipt",new byte[]{9}));
        assertThrows(IOException.class,()->IntakeLedger.recordSourceMarker(journal,command.toUpperCase()+".receipt",new byte[]{9}));
        var link=journal.resolve("link");
        Files.createSymbolicLink(link,journal);
        assertThrows(IOException.class,()->IntakeLedger.recordSourceMarker(link,command+".intent",new byte[]{9}));
    }
    @Test void sourceJournalIsExclusiveAndDoesNotBroadenIntakeNamespace() throws Exception {
        journal=journal.toRealPath();
        String command="babycdf-source-v2-"+"a".repeat(64);
        IntakeLedger.recordSourceMarker(journal,command+".intent",new byte[]{1,2,3});
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(journal.resolve(command+".intent")));
        assertThrows(IOException.class,()->IntakeLedger.recordSourceMarker(journal,command+".intent",new byte[]{4}));
        assertThrows(IOException.class,()->IntakeLedger.recordIntent(journal,command,new byte[]{4}));
        assertThrows(IOException.class,()->IntakeLedger.recordSourceMarker(journal,"../escape.intent",new byte[]{4}));
    }
}
