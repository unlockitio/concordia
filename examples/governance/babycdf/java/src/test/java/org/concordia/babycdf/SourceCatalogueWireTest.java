// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Independent Ledger API fixtures: no codec builder is used to construct input records. */
class SourceCatalogueWireTest {
    static ValueOuterClass.Value s(String s) {return ValueOuterClass.Value.newBuilder().setText(s).build();}
    static ValueOuterClass.Value n(long n) {return ValueOuterClass.Value.newBuilder().setInt64(n).build();}
    static ValueOuterClass.Value e(String e) {return ValueOuterClass.Value.newBuilder().setEnum(
            ValueOuterClass.Enum.newBuilder().setConstructor(e)).build();}
    static ValueOuterClass.RecordField f(String name,ValueOuterClass.Value v) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build();
    }
    static final String META="babycdf-source-metadata-v1:[\"title convention: parent #3\",false,\""
            +SourceObservation.hash("full raw body")+"\",true]";
    static ValueOuterClass.Record wire() {
        return ValueOuterClass.Record.newBuilder().addFields(f("number",n(7)))
                .addFields(f("kind",e("SourceMilestone")))
                .addFields(f("sourceUrl",s("https://github.com/canton-foundation/canton-dev-fund/issues/7")))
                .addFields(f("title",s("Milestone"))).addFields(f("body",s("raw\nbody")))
                .addFields(f("state",e("SourceClosed")))
                .addFields(f("updatedAt",ValueOuterClass.Value.newBuilder().setTimestamp(1767225600123456L).build()))
                .addFields(f("observedAt",ValueOuterClass.Value.newBuilder().setTimestamp(1767312000000000L).build()))
                .addFields(f("verification",e("ObservedUnverified")))
                .addFields(f("parentPR",ValueOuterClass.Value.newBuilder().setOptional(
                        ValueOuterClass.Optional.newBuilder().setValue(n(3))).build()))
                .addFields(f("linkEvidence",s(META))).build();
    }
    static ValueOuterClass.Record replace(String label,ValueOuterClass.Value value) {
        var r=wire().toBuilder();
        for(int i=0;i<r.getFieldsCount();i++) if(r.getFields(i).getLabel().equals(label)) r.setFields(i,f(label,value));
        return r.build();
    }
    static void rejects(ValueOuterClass.Record record) {
        var error=assertThrows(IOException.class,()->SourceCatalogueCodec.observation(record));
        assertFalse(error.toString().contains("SECRET_SENTINEL"));
        assertNull(error.getCause(),"opaque payload must not survive in exception cause");
    }
    @Test void independentWireRecordMatchesBothDirections() throws Exception {
        assertEquals(SourceCatalogueCodecTest.sample(),SourceCatalogueCodec.observation(wire()));
        assertEquals(wire(),SourceCatalogueCodec.observation(SourceCatalogueCodecTest.sample()).getRecord());
        for(String name:List.of("SourceProposal","SourceMilestone","SourceOpen","SourceClosed","ObservedUnverified",
                "ContentDigestChecked","FullSweep","OverlapSweep")) {
            assertEquals(e(name),SourceCatalogueCodec.constructor(name));
        }
    }
    @Test void recordShapeAndTypesFailClosed() {
        rejects(wire().toBuilder().removeFields(0).build());
        rejects(wire().toBuilder().addFields(f("extra",s("SECRET_SENTINEL"))).build());
        rejects(wire().toBuilder().addFields(f("",s("SECRET_SENTINEL"))).build());
        rejects(wire().toBuilder().addFields(wire().getFields(0)).build());
        rejects(replace("number",s("SECRET_SENTINEL")));
        rejects(replace("kind",e("SECRET_SENTINEL")));
        rejects(replace("kind",ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder()
                .setConstructor("SourceMilestone").setValue(ValueOuterClass.Value.newBuilder()
                        .setUnit(com.google.protobuf.Empty.getDefaultInstance()))).build()));
        rejects(replace("verification",e("ContentDigestChecked")));
        rejects(replace("parentPR",n(3)));
        rejects(replace("parentPR",ValueOuterClass.Value.newBuilder().setOptional(
                ValueOuterClass.Optional.newBuilder().setValue(s("SECRET_SENTINEL"))).build()));
    }
    @Test void metadataMustBeTypedCanonicalAndEntireInput() {
        for(String bad:List.of("SECRET_SENTINEL", "babycdf-source-metadata-v1:[]",
                "babycdf-source-metadata-v1:[\"SECRET_SENTINEL\",false,\"x\"]",
                "babycdf-source-metadata-v1:[1,false,\"x\",true]",
                "babycdf-source-metadata-v1:[\"x\",\"false\",\"x\",true]",
                "babycdf-source-metadata-v1:[\"x\",false,1,true]",
                "babycdf-source-metadata-v1:[\"x\",false,\"x\",\"true\"]",
                META+" {}",META+" SECRET_SENTINEL",META+" ",META.replace("false", " false")))
            rejects(replace("linkEvidence",s(bad)));
    }
    @Test void timeBoundsAndUnicode() throws Exception {
        for(Instant instant:List.of(Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-12-31T23:59:59.999999Z"),Instant.parse("1969-12-31T23:59:59.999999Z")))
            assertEquals(instant,SourceCatalogueCodec.timestamp(SourceCatalogueCodec.timestamp(instant)));
        for(Instant instant:List.of(Instant.parse("0000-12-31T23:59:59Z"),Instant.parse("+10000-01-01T00:00:00Z")))
            assertThrows(IOException.class,()->SourceCatalogueCodec.timestamp(instant));
        rejects(replace("updatedAt",ValueOuterClass.Value.newBuilder().setTimestamp(Long.MIN_VALUE).build()));
        rejects(replace("title",s("SECRET_SENTINEL\ud800")));
        rejects(replace("title",s("SECRET_SENTINEL\udc00")));
        assertEquals("pair 😀",SourceCatalogueCodec.observation(replace("title",s("pair 😀"))).title());
    }
    @Test void metadataBoundIsExact() throws Exception {
        var o=SourceCatalogueCodecTest.sample();
        int overhead=SourceCatalogueCodec.metadata(o).length()-o.linkEvidence().length();
        var exact=new SourceObservation(o.number(),o.kind(),o.url(),o.title(),o.body(),o.state(),o.updatedAt(),o.observedAt(),
                o.parentPR(),"x".repeat(4096-overhead),o.merged(),o.observedDigest(),o.truncated());
        assertEquals(4096,SourceCatalogueCodec.metadata(exact).length());
        assertEquals(exact,SourceCatalogueCodec.observation(SourceCatalogueCodec.observation(exact).getRecord()));
        var excess=new SourceObservation(o.number(),o.kind(),o.url(),o.title(),o.body(),o.state(),o.updatedAt(),o.observedAt(),
                o.parentPR(),exact.linkEvidence()+"x",o.merged(),o.observedDigest(),o.truncated());
        assertThrows(IOException.class,()->SourceCatalogueCodec.observation(excess));
    }
}
