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
        String[] hashes={"03b8ff8786ac7c340830fd9a6c3c104228792389b228a6f293c4dd0553839777",
            "ec1cc6135b58eec7cceee8399a5dafc2283201c085b5eb309b8fb498b91a0e38",
            "d2e34e1aa5798039db3a23182dde86141342d1527b891b644719bbeb6292add1",
            "d0f507803952a3a4f9a3ae6302a851994020635821ce7a6dfd52d3afcbbbce5c"};
        for(int i=0;i<4;i++) {
            var r=SourceCatalogueWireTest.wire().toBuilder();
            for(int j=0;j<r.getFieldsCount();j++) {
                var field=r.getFields(j); var value=field.getValue();
                switch(field.getLabel()) {
                    case "title" -> value=SourceCatalogueWireTest.s("café2 😀3");
                    case "body" -> value=SourceCatalogueWireTest.s("line\n\t\"\\\u00014");
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
            if(value.hasText()) value=SourceCatalogueWireTest.s(value.getText()+"x");
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
