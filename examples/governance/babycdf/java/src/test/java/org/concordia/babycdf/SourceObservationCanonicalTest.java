// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SourceObservationCanonicalTest {
    @Test void independentS0Vector() {
        String body="hello\r\nworld\n";
        assertEquals("4375539f2263c313c68efccaa296d00e561e44e5cb4863dfffd2fed733a8bad8",SourceObservation.hash(body));
        String canonical=SourceObservationCanonical.serialize("canton-foundation/canton-dev-fund",184,"proposal","café 😀",
                "https://github.com/canton-foundation/canton-dev-fund/pull/184",SourceObservation.hash(body),"closed",
                Instant.parse("2026-01-01T00:00:00Z"),null,"linked","observed_unverified",1,null);
        assertEquals("ffe8d1c205e45f8b20a747cd03ac42a9653cd064aff4ffc4a88fba013022ef44",SourceObservation.hash(canonical));
        assertFalse(canonical.contains(body));
    }
    @Test void escapingPreservesScalarValues() {
        assertEquals("\"café 😀\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f\"",
                SourceObservationCanonical.quote("café 😀\"\\\b\f\n\r\t\u0000\u001f"));
        assertThrows(IllegalArgumentException.class,()->SourceObservationCanonical.quote("\ud800"));
        assertThrows(IllegalArgumentException.class,()->SourceObservationCanonical.quote("\udc00"));
        assertNotEquals(SourceObservationCanonical.quote("é"),SourceObservationCanonical.quote("e\u0301"));
    }
    @Test void allLowControlsAndBoundariesMatchDamlTable() {
        String[] escaped={"\\u0000","\\u0001","\\u0002","\\u0003","\\u0004","\\u0005","\\u0006","\\u0007",
                "\\b","\\t","\\n","\\u000b","\\f","\\r","\\u000e","\\u000f",
                "\\u0010","\\u0011","\\u0012","\\u0013","\\u0014","\\u0015","\\u0016","\\u0017",
                "\\u0018","\\u0019","\\u001a","\\u001b","\\u001c","\\u001d","\\u001e","\\u001f"};
        for(int i=0;i<32;i++) assertEquals("\""+escaped[i]+"\"",SourceObservationCanonical.quote(String.valueOf((char)i)),"control "+i);
        for(String value:new String[]{"0001-01-01T00:00:00.000000Z","1969-12-31T23:59:59.999999Z",
                "9999-12-31T23:59:59.999999Z","2026-01-01T00:00:00.100000Z","2026-01-01T00:00:00.123000Z"})
            assertEquals(value,SourceObservationCanonical.timestamp(Instant.parse(value)));
    }
    @Test void timestampAndLineageFailClosed() {
        assertEquals("2026-01-01T00:00:00.123456Z",SourceObservationCanonical.timestamp(Instant.parse("2026-01-01T00:00:00.123456Z")));
        assertThrows(IllegalArgumentException.class,()->SourceObservationCanonical.timestamp(Instant.parse("2026-01-01T00:00:00.1234567Z")));
    }
}
