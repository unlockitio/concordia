// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Observation wire mapping. Metadata is evidence, never ContentDigestChecked.
 * Bounds intentionally use Java UTF-16 units, matching SourceObservation: this is
 * stricter than Daml code-point limits for supplementary characters, never looser.
 */
final class SourceCatalogueCodec {
    private SourceCatalogueCodec() { }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String PREFIX = "babycdf-source-metadata-v1:";
    static final String MODULE = "Cap.Examples.BabyCdf.SourceCatalogue";
    static final String REPOSITORY = "canton-foundation/canton-dev-fund";

    static ValueOuterClass.Value text(String value) {
        return ValueOuterClass.Value.newBuilder().setText(value).build();
    }
    static ValueOuterClass.Value integer(long value) {
        return ValueOuterClass.Value.newBuilder().setInt64(value).build();
    }
    static ValueOuterClass.Value bool(boolean value) {
        return ValueOuterClass.Value.newBuilder().setBool(value).build();
    }
    static ValueOuterClass.Value constructor(String name) {
        if (!Set.of("SourceProposal","SourceMilestone","SourceOpen","SourceClosed",
                "ObservedUnverified","ContentDigestChecked","FullSweep","OverlapSweep").contains(name))
            throw new IllegalArgumentException("unknown source constructor");
        return ValueOuterClass.Value.newBuilder().setEnum(ValueOuterClass.Enum.newBuilder()
                .setConstructor(name)).build();
    }
    static ValueOuterClass.Value record(Object... fields) {
        var record = ValueOuterClass.Record.newBuilder();
        for (int i=0; i<fields.length; i+=2) record.addFields(ValueOuterClass.RecordField.newBuilder()
                .setLabel((String)fields[i]).setValue((ValueOuterClass.Value)fields[i+1]));
        return ValueOuterClass.Value.newBuilder().setRecord(record).build();
    }
    private static final Instant MIN_TIME=Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME=Instant.parse("9999-12-31T23:59:59.999999Z");
    private static void validTime(Instant instant) throws IOException {
        if(instant.isBefore(MIN_TIME) || instant.isAfter(MAX_TIME)) throw new IOException("source timestamp out of Daml range");
    }
    private static void validText(String text) throws IOException {
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IOException("invalid source Unicode");
            } else if(Character.isLowSurrogate(c)) throw new IOException("invalid source Unicode");
        }
    }
    static ValueOuterClass.Value timestamp(Instant instant) throws IOException {
        validTime(instant);
        if (instant.getNano()%1000!=0) throw new IOException("source timestamp exceeds ledger microsecond precision");
        try { return ValueOuterClass.Value.newBuilder().setTimestamp(
                Math.addExact(Math.multiplyExact(instant.getEpochSecond(),1_000_000L),instant.getNano()/1000)).build(); }
        catch(ArithmeticException e) { throw new IOException("source timestamp out of range",e); }
    }
    static String metadata(SourceObservation observation) throws IOException {
        // Keep the full parent-link evidence; reject overflow rather than truncate it again.
        String encoded=PREFIX+JSON.writeValueAsString(List.of(observation.linkEvidence(),observation.merged(),
                observation.observedDigest(),observation.truncated()));
        if(encoded.length()>4096) throw new IOException("source metadata exceeds ledger evidence bound");
        return encoded;
    }
    static ValueOuterClass.Value observation(SourceObservation observation) throws IOException {
        for(String s:List.of(observation.url(),observation.title(),observation.body(),observation.linkEvidence())) validText(s);
        var optional=ValueOuterClass.Optional.newBuilder();
        if(observation.parentPR()!=null) optional.setValue(integer(observation.parentPR()));
        return record("number",integer(observation.number()),
                "kind",constructor(observation.kind()==SourceObservation.Kind.PROPOSAL?"SourceProposal":"SourceMilestone"),
                "sourceUrl",text(observation.url()),"title",text(observation.title()),"body",text(observation.body()),
                "state",constructor(observation.state().equals("open")?"SourceOpen":"SourceClosed"),
                "updatedAt",timestamp(observation.updatedAt()),"observedAt",timestamp(observation.observedAt()),
                "verification",constructor("ObservedUnverified"),
                "parentPR",ValueOuterClass.Value.newBuilder().setOptional(optional).build(),
                "linkEvidence",text(metadata(observation)));
    }
    static Map<String,ValueOuterClass.Value> fields(ValueOuterClass.Record record, Set<String> expected) throws IOException {
        var fields=new HashMap<String,ValueOuterClass.Value>();
        for(var field:record.getFieldsList()) {
            if(!expected.contains(field.getLabel()) || !field.hasValue() || fields.put(field.getLabel(),field.getValue())!=null)
                throw new IOException("malformed source record fields");
        }
        if(!fields.keySet().equals(expected)) throw new IOException("missing source record fields");
        return fields;
    }
    static String text(ValueOuterClass.Value value) throws IOException {
        if(!value.hasText()) throw new IOException("expected source text");
        validText(value.getText()); return value.getText();
    }
    static long integer(ValueOuterClass.Value value) throws IOException {
        if(!value.hasInt64()) throw new IOException("expected source integer"); return value.getInt64();
    }
    static String constructor(ValueOuterClass.Value value) throws IOException {
        if(!value.hasEnum() || !Set.of("SourceProposal","SourceMilestone","SourceOpen","SourceClosed",
                "ObservedUnverified","ContentDigestChecked","FullSweep","OverlapSweep")
                .contains(value.getEnum().getConstructor())) throw new IOException("expected source enum constructor");
        return value.getEnum().getConstructor();
    }
    static Instant timestamp(ValueOuterClass.Value value) throws IOException {
        if(!value.hasTimestamp()) throw new IOException("expected source timestamp");
        long micros=value.getTimestamp();
        Instant instant=Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000),Math.floorMod(micros,1_000_000)*1000);
        validTime(instant);
        return instant;
    }
    static SourceObservation observation(ValueOuterClass.Record record) throws IOException {
        var f=fields(record,Set.of("number","kind","sourceUrl","title","body","state","updatedAt","observedAt",
                "verification","parentPR","linkEvidence"));
        if(!constructor(f.get("verification")).equals("ObservedUnverified"))
            throw new IOException("unsupported source verification; never infer remote verification");
        SourceObservation.Kind kind=switch(constructor(f.get("kind"))) {
            case "SourceProposal" -> SourceObservation.Kind.PROPOSAL;
            case "SourceMilestone" -> SourceObservation.Kind.MILESTONE;
            default -> throw new IOException("unknown source kind");
        };
        String state=switch(constructor(f.get("state"))) {
            case "SourceOpen" -> "open"; case "SourceClosed" -> "closed";
            default -> throw new IOException("unknown source state");
        };
        var parent=f.get("parentPR");
        if(!parent.hasOptional()) throw new IOException("expected optional parent");
        Long parentNumber=parent.getOptional().hasValue()?integer(parent.getOptional().getValue()):null;
        String evidence=text(f.get("linkEvidence"));
        if(!evidence.startsWith(PREFIX) || evidence.length()>4096) throw new IOException("source metadata unavailable");
        try {
            var metadata=JSON.readTree(evidence.substring(PREFIX.length()));
            if(!metadata.isArray() || metadata.size()!=4 || !metadata.get(0).isTextual()
                    || !metadata.get(1).isBoolean() || !metadata.get(2).isTextual() || !metadata.get(3).isBoolean())
                throw new IOException("malformed source metadata");
            var decoded = new SourceObservation(integer(f.get("number")),kind,text(f.get("sourceUrl")),text(f.get("title")),
                    text(f.get("body")),state,timestamp(f.get("updatedAt")),timestamp(f.get("observedAt")),parentNumber,
                    metadata.get(0).textValue(),metadata.get(1).booleanValue(),metadata.get(2).textValue(),metadata.get(3).booleanValue());
            validText(decoded.linkEvidence());
            if (!metadata(decoded).equals(evidence)) throw new IOException("noncanonical source metadata");
            return decoded;
        } catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("invalid ledger source observation");
        }
    }
}
