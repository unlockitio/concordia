// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class SourceCodecFailureCategoryTest {
 @Test void decodeFailuresAreCategoricalWithoutPayloadOrCause() {
  var original=SourceCatalogueWireTest.wire();
  for(var bad:java.util.List.of(original.toBuilder().removeFields(0).build(),
    SourceCatalogueWireTest.replace("bodySha256",SourceCatalogueWireTest.s("RAW_SENTINEL")),
    SourceCatalogueWireTest.replace("title",SourceCatalogueWireTest.s("RAW_SENTINEL\ud800")),
    SourceCatalogueWireTest.replace("kind",SourceCatalogueWireTest.e("RAW_SENTINEL")),
    SourceCatalogueWireTest.replace("updatedAt",SourceCatalogueWireTest.s("RAW_SENTINEL")))) {
   var failure=assertThrows(IOException.class,()->SourceCatalogueCodec.observation(bad));
   assertEquals("invalid v2 source observation record",failure.getMessage());assertNull(failure.getCause());
  }
 }
}
