// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.daml.ledger.api.v2.ValueOuterClass;
import java.time.Instant;

/** Expected hashes emitted by SDK 3.5.11 SourceCatalogueGolden:goldenVectors.
 * Reproduce: dpm script --dar demo/.daml/dist/cap-example-babycdf-demo-0.1.0.dar
 * --script-name Cap.Examples.BabyCdf.SourceCatalogueGolden:goldenVectors --ide-ledger
 * Source: demo/daml/Cap/Examples/BabyCdf/SourceCatalogueGolden.daml.
 */
class SourceCatalogueGoldenTest {
    @Test void actualDamlContentDigestVectors() throws Exception {
        String[] times={"00Z","00.100Z","00.123Z","00.123456Z"};
        String[] hashes={"c154d7becd32a7243f409bda364b08e121bb818407104a5a6471477a9f707b36",
            "7d36763731d3e2879fc49a85c6b8d39b53f07408c1755c928ce43188de2d8808",
            "a725b9414a0a3041b5fdac0f46c2fa112bbb13b8d5da48c1140e09905140a8fb",
            "4d8cbb70d15ab7eafd33f8279ad9defb8fe618eda9c8d7096d00d69bea5b760a"};
        for(int i=0;i<4;i++) {
            var r=SourceCatalogueWireTest.wire().toBuilder();
            for(int j=0;j<r.getFieldsCount();j++) {
                var field=r.getFields(j); var value=field.getValue();
                switch(field.getLabel()) {
                    case "title" -> value=SourceCatalogueWireTest.s("café2 😀3");
                    case "bodySha256" -> value=SourceCatalogueWireTest.s(SourceObservation.hash("line\n\t\"\\\u00014"));
                    case "linkEvidence" -> value=SourceCatalogueWireTest.s("evidence");
                    case "updatedAt" -> value=SourceCatalogueCodec.timestamp(Instant.parse("2026-01-01T00:00:"+times[i]));
                    case "parentPR" -> { if(i==0) value=ValueOuterClass.Value.newBuilder()
                            .setOptional(ValueOuterClass.Optional.getDefaultInstance()).build(); }
                }
                r.setFields(j,field.toBuilder().setValue(value));
            }
            assertEquals(hashes[i],SourceCatalogueDigest.digest(r.build()));
        }
    }
    @Test void eachGovernedFieldChangesDigestOnlyReceiptExcluded() throws Exception {
        var original=SourceCatalogueWireTest.wire(); String digest=SourceCatalogueDigest.digest(original);
        for(int i=0;i<original.getFieldsCount();i++) {
            var field=original.getFields(i); var value=field.getValue();
            if(field.getLabel().equals("bodySha256")) value=SourceCatalogueWireTest.s("0".repeat(64));
            else if(value.hasText()) value=SourceCatalogueWireTest.s(value.getText()+"x");
            else if(value.hasInt64()) value=SourceCatalogueWireTest.n(value.getInt64()+1);
            else if(value.hasTimestamp()) value=ValueOuterClass.Value.newBuilder().setTimestamp(value.getTimestamp()+1).build();
            else if(value.hasOptional()) value=ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()).build();
            else if(value.hasEnum()) value=SourceCatalogueWireTest.e(switch(field.getLabel()) {
                case "kind" -> "SourceProposal"; case "state" -> "SourceOpen"; default -> "ContentDigestChecked"; });
            var modified=original.toBuilder().setFields(i,field.toBuilder().setValue(value)).build();
            if(field.getLabel().equals("observedAt")) assertEquals(digest,SourceCatalogueDigest.digest(modified));
            else assertNotEquals(digest,SourceCatalogueDigest.digest(modified),field.getLabel());
        }
    }
}
