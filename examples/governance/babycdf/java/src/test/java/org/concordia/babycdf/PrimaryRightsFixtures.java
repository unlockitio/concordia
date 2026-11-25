package org.concordia.babycdf;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.Empty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class PrimaryRightsFixtures {
    private PrimaryRightsFixtures() {}

    static ValueOuterClass.Value electorate(List<String> members) {
        var map = ValueOuterClass.GenMap.newBuilder();
        for (String member : members) map.addEntries(ValueOuterClass.GenMap.Entry.newBuilder()
                .setKey(party(member)).setValue(ValueOuterClass.Value.newBuilder().setUnit(Empty.getDefaultInstance())));
        return ValueOuterClass.Value.newBuilder().setRecord(ValueOuterClass.Record.newBuilder()
                .setRecordId(ValueOuterClass.Identifier.newBuilder().setPackageId("0".repeat(64))
                        .setModuleName("DA.Set.Types").setEntityName("Set"))
                .addFields(field("map", ValueOuterClass.Value.newBuilder().setGenMap(map).build()))).build();
    }

    static EventOuterClass.CreatedEvent governor(EventOuterClass.CreatedEvent event, String consortium, List<String> members) {
        var record = event.getCreateArguments().toBuilder();
        record.addFields(field("consortiumId", ValueOuterClass.Value.newBuilder().setText(consortium).build()));
        record.addFields(field("permanent", electorate(members)));
        return event.toBuilder().setCreateArguments(record).build();
    }

    static List<EventOuterClass.CreatedEvent> rights(String opener, String consortium, List<String> members, Instant now) {
        var result = new ArrayList<EventOuterClass.CreatedEvent>();
        for (String member : members) {
            long issued = micros(now.minusSeconds(1));
            long expiry = micros(now.plusSeconds(30L * 86400));
            var payload = ValueOuterClass.Record.newBuilder()
                    .addFields(field("cdf", party(opener))).addFields(field("member", party(member)))
                    .addFields(field("consortiumId", ValueOuterClass.Value.newBuilder().setText(consortium).build()))
                    .addFields(field("registryAdmin", party(opener))).addFields(field("issuedAt", time(issued)))
                    .addFields(field("registeredAt", time(issued))).addFields(field("expiresAt", time(expiry)))
                    .addFields(field("registrationExpiresAt", ValueOuterClass.Value.newBuilder().setOptional(
                            ValueOuterClass.Optional.newBuilder().setValue(time(expiry))).build()));
            result.add(EventOuterClass.CreatedEvent.newBuilder().setContractId("primary-right-" + member)
                    .addSignatories(opener).addSignatories(member).setCreateArguments(payload).build());
        }
        return result;
    }

    private static ValueOuterClass.RecordField field(String name, ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(value).build();
    }
    private static ValueOuterClass.Value party(String value) { return ValueOuterClass.Value.newBuilder().setParty(value).build(); }
    private static ValueOuterClass.Value time(long value) { return ValueOuterClass.Value.newBuilder().setTimestamp(value).build(); }
    private static long micros(Instant value) { return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000), value.getNano() / 1000); }
}
