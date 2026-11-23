// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;

class SourceCatalogueCodecTest {
    static SourceObservation sample() {
        return new SourceObservation(7,SourceObservation.Kind.MILESTONE,
                "https://github.com/canton-foundation/canton-dev-fund/issues/7","Milestone",SourceObservation.hash("raw\nbody"),
                "closed",Instant.parse("2026-01-01T00:00:00.123456Z"),Instant.parse("2026-01-02T00:00:00Z"),
                3L,"title convention: parent #3",false,SourceObservation.hash("raw\nbody"),false);
    }
    @Test void roundTripRetainsRawEvidenceAndMicroseconds() throws Exception {
        var source=sample(); var encoded=SourceCatalogueCodec.observation(source);
        assertEquals(source,SourceCatalogueCodec.observation(encoded.getRecord()));
        assertEquals(source.fingerprint(),SourceCatalogueCodec.observation(encoded.getRecord()).fingerprint());
        assertEquals("ObservedUnverified",IntakeLedger.field(encoded.getRecord(),"verification").getEnum().getConstructor());
    }
    @Test void neverSilentlyRoundsReceiptTime() {
        assertThrows(IOException.class,()->SourceCatalogueCodec.timestamp(Instant.parse("2026-01-01T00:00:00.000000001Z")));
    }
    @Test void metadataOverflowFailsWithoutTruncatingParentEvidence() {
        var o=sample();
        var large=new SourceObservation(o.number(),o.kind(),o.url(),o.title(),o.bodySha256(),o.state(),o.updatedAt(),o.observedAt(),
                o.parentPR(),"x".repeat(4096),o.merged(),o.observedDigest(),o.truncated());
        assertDoesNotThrow(()->SourceCatalogueCodec.observation(large));
    }
    @Test void duplicateFieldsFailClosed() throws Exception {
        var record=SourceCatalogueCodec.observation(sample()).getRecord();
        var duplicate=record.toBuilder().addFields(record.getFields(0)).build();
        assertThrows(IOException.class,()->SourceCatalogueCodec.observation(duplicate));
    }
    @Test void negativeEpochMicrosecondsRoundTrip() throws Exception {
        Instant instant=Instant.parse("1969-12-31T23:59:59.999999Z");
        assertEquals(instant,SourceCatalogueCodec.timestamp(SourceCatalogueCodec.timestamp(instant)));
    }
}
