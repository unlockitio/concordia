package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

final class PrimaryRights {
    private PrimaryRights() {}

    static ValueOuterClass.Value field(ValueOuterClass.Record record, String label) throws IOException {
        var matches = record.getFieldsList().stream().filter(f -> label.equals(f.getLabel())).toList();
        if (matches.size() != 1) throw new IOException("BLOCKED: missing or duplicate " + label);
        return matches.getFirst().getValue();
    }

    static ValueOuterClass.Value references(com.daml.ledger.api.v2.EventOuterClass.CreatedEvent governor,
            java.util.List<com.daml.ledger.api.v2.EventOuterClass.CreatedEvent> rights,
            String opener, java.time.Instant now, java.time.Instant closesAt) throws IOException {
        var gov = governor.getCreateArguments();
        if (!opener.equals(field(gov, "opener").getParty())) throw new IOException("BLOCKED: governor opener mismatch");
        var consortiumValue = field(gov, "consortiumId");
        if (!consortiumValue.hasText() || consortiumValue.getText().isEmpty())
            throw new IOException("BLOCKED: malformed governor consortium");
        var consortium = consortiumValue.getText();
        var primary = parties(field(gov, "permanent"));
        var result = ValueOuterClass.List.newBuilder();
        for (String member : primary.stream().sorted().toList()) {
            var matches = new java.util.ArrayList<com.daml.ledger.api.v2.EventOuterClass.CreatedEvent>();
            for (var event : rights) {
                var payload = event.getCreateArguments();
                if (opener.equals(field(payload, "cdf").getParty())
                        && consortium.equals(field(payload, "consortiumId").getText())
                        && member.equals(field(payload, "member").getParty())) matches.add(event);
            }
            if (matches.size() != 1) throw new IOException("BLOCKED: renewal_required: exactly one registered primary right required");
            var event = matches.getFirst();
            var payload = event.getCreateArguments();
            if (event.getSignatoriesCount() != 2 || !new HashSet<>(event.getSignatoriesList()).equals(Set.of(opener, member))
                    || !opener.equals(field(payload, "registryAdmin").getParty()))
                throw new IOException("BLOCKED: foreign registered-right authority");
            long current = micros(now), deadline = micros(closesAt);
            var issued = field(payload, "issuedAt");
            var expires = field(payload, "expiresAt");
            var registered = field(payload, "registeredAt");
            var registrationExpires = field(payload, "registrationExpiresAt");
            if (!issued.hasTimestamp() || !expires.hasTimestamp() || !registered.hasTimestamp()
                    || !registrationExpires.hasOptional() || !registrationExpires.getOptional().hasValue()
                    || !registrationExpires.getOptional().getValue().hasTimestamp()
                    || issued.getTimestamp() > current || registered.getTimestamp() > current
                    || expires.getTimestamp() <= deadline || expires.getTimestamp() <= current
                    || registrationExpires.getOptional().getValue().getTimestamp() <= deadline
                    || registrationExpires.getOptional().getValue().getTimestamp() <= current)
                throw new IOException("BLOCKED: renewal_required: primary credential must remain valid strictly beyond closesAt");
            var tuple = ValueOuterClass.Record.newBuilder()
                    .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("_1").setValue(ValueOuterClass.Value.newBuilder().setParty(member)))
                    .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("_2").setValue(ValueOuterClass.Value.newBuilder().setVariant(
                            ValueOuterClass.Variant.newBuilder().setConstructor("RegisteredRight")
                                    .setValue(ValueOuterClass.Value.newBuilder().setContractId(event.getContractId())))));
            result.addElements(ValueOuterClass.Value.newBuilder().setRecord(tuple));
        }
        return ValueOuterClass.Value.newBuilder().setList(result).build();
    }

    private static long micros(java.time.Instant time) {
        return Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000L), time.getNano() / 1000);
    }

    static Set<String> parties(ValueOuterClass.Value value) throws IOException {
        if (!value.hasRecord()) throw new IOException("BLOCKED: malformed governor electorate");
        var set = value.getRecord();
        if (!set.hasRecordId() || !"DA.Set.Types".equals(set.getRecordId().getModuleName())
                || !"Set".equals(set.getRecordId().getEntityName()) || set.getFieldsCount() != 1
                || !"map".equals(set.getFields(0).getLabel()))
            throw new IOException("BLOCKED: malformed governor electorate");
        var map = set.getFields(0).getValue();
        if (!map.hasGenMap()) throw new IOException("BLOCKED: malformed governor electorate");
        Set<String> parties = new HashSet<>();
        for (var entry : map.getGenMap().getEntriesList()) {
            if (!entry.getKey().hasParty() || !entry.getValue().hasUnit()
                    || !parties.add(entry.getKey().getParty()))
                throw new IOException("BLOCKED: malformed electorate");
        }
        if (parties.size() != 5) throw new IOException("BLOCKED: five primary governors required");
        return Set.copyOf(parties);
    }
}
