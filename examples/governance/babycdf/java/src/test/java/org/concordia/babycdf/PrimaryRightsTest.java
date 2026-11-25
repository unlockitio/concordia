package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.Empty;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimaryRightsTest {
    private static ValueOuterClass.Value electorate(int count) {
        var entries = ValueOuterClass.GenMap.newBuilder();
        for (int i = 0; i < count; i++) entries.addEntries(ValueOuterClass.GenMap.Entry.newBuilder()
                .setKey(ValueOuterClass.Value.newBuilder().setParty("primary-" + i))
                .setValue(ValueOuterClass.Value.newBuilder().setUnit(Empty.getDefaultInstance())));
        return ValueOuterClass.Value.newBuilder().setRecord(ValueOuterClass.Record.newBuilder()
                .setRecordId(ValueOuterClass.Identifier.newBuilder().setPackageId("0".repeat(64))
                        .setModuleName("DA.Set.Types").setEntityName("Set"))
                .addFields(ValueOuterClass.RecordField.newBuilder().setLabel("map")
                        .setValue(ValueOuterClass.Value.newBuilder().setGenMap(entries)))).build();
    }

    private static ValueOuterClass.RecordField field(String name, ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(value).build();
    }

    private static ValueOuterClass.Value party(String value) {
        return ValueOuterClass.Value.newBuilder().setParty(value).build();
    }

    private static ValueOuterClass.Value time(long value) {
        return ValueOuterClass.Value.newBuilder().setTimestamp(value).build();
    }

    private static com.daml.ledger.api.v2.EventOuterClass.CreatedEvent governor() {
        return com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setCreateArguments(
                ValueOuterClass.Record.newBuilder().addFields(field("opener", party("opener")))
                        .addFields(field("consortiumId", ValueOuterClass.Value.newBuilder().setText("cdf").build()))
                        .addFields(field("permanent", electorate(5)))).build();
    }

    private static java.util.List<com.daml.ledger.api.v2.EventOuterClass.CreatedEvent> rights(long expiry) {
        var result = new java.util.ArrayList<com.daml.ledger.api.v2.EventOuterClass.CreatedEvent>();
        for (int i = 0; i < 5; i++) {
            String member = "primary-" + i;
            var payload = ValueOuterClass.Record.newBuilder().addFields(field("cdf", party("opener")))
                    .addFields(field("consortiumId", ValueOuterClass.Value.newBuilder().setText("cdf").build()))
                    .addFields(field("member", party(member))).addFields(field("registryAdmin", party("opener")))
                    .addFields(field("issuedAt", time(0))).addFields(field("registeredAt", time(0)))
                    .addFields(field("expiresAt", time(expiry)))
                    .addFields(field("registrationExpiresAt", ValueOuterClass.Value.newBuilder().setOptional(
                            ValueOuterClass.Optional.newBuilder().setValue(time(expiry))).build()));
            result.add(com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder().setContractId("right-" + i)
                    .addSignatories("opener").addSignatories(member).setCreateArguments(payload).build());
        }
        return result;
    }

    @Test void includesAllFiveActualRegisteredReferences() throws Exception {
        var value = PrimaryRights.references(governor(), rights(30L * 86400 * 1_000_000), "opener",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(21L * 86400));
        assertEquals(5, value.getList().getElementsCount());
        assertEquals("RegisteredRight", value.getList().getElements(0).getRecord().getFields(1)
                .getValue().getVariant().getConstructor());
    }

    @Test void agedEightDaysStillCoversTwentyOneDays() throws Exception {
        var value = PrimaryRights.references(governor(), rights(30L * 86400 * 1_000_000), "opener",
                java.time.Instant.EPOCH.plusSeconds(8L * 86400), java.time.Instant.EPOCH.plusSeconds(29L * 86400));
        assertEquals(5, value.getList().getElementsCount());
    }

    @Test void rejectsExclusiveExpiryBoundaryAndAgedCoverage() {
        var expiry = 30L * 86400 * 1_000_000;
        assertThrows(IOException.class, () -> PrimaryRights.references(governor(), rights(expiry), "opener",
                java.time.Instant.EPOCH.plusSeconds(9L * 86400), java.time.Instant.EPOCH.plusSeconds(30L * 86400)));
        assertThrows(IOException.class, () -> PrimaryRights.references(governor(), rights(expiry), "opener",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(365L * 86400)));
    }

    @Test void rejectsDuplicateRegisteredRightForPrimary() {
        var rows = rights(30L * 86400 * 1_000_000);
        rows.add(rows.getFirst().toBuilder().setContractId("duplicate-right").build());
        assertThrows(IOException.class, () -> PrimaryRights.references(governor(), rows, "opener",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(21L * 86400)));
    }

    @Test void rejectsMissingNonparticipatingPrimaryAndForeignSigner() {
        var rows = rights(30L * 86400 * 1_000_000);
        rows.removeLast();
        var missing = rows;
        assertThrows(IOException.class, () -> PrimaryRights.references(governor(), missing, "opener",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(21L * 86400)));
        rows = rights(30L * 86400 * 1_000_000);
        rows.set(4, rows.get(4).toBuilder().clearSignatories().addSignatories("opener").addSignatories("foreign").build());
        var foreign = rows;
        assertThrows(IOException.class, () -> PrimaryRights.references(governor(), foreign, "opener",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(21L * 86400)));
    }

    @Test void rejectsDuplicatePrimarySeats() {
        var original = electorate(5).getRecord();
        var map = original.getFields(0).getValue().getGenMap().toBuilder();
        map.addEntries(map.getEntries(0));
        var duplicate = ValueOuterClass.Value.newBuilder().setRecord(original.toBuilder()
                .setFields(0, original.getFields(0).toBuilder().setValue(
                        ValueOuterClass.Value.newBuilder().setGenMap(map)))).build();
        assertThrows(IOException.class, () -> PrimaryRights.parties(duplicate));
    }

    @Test void acceptsExactlyFiveNamedPrimarySeats() throws Exception {
        assertEquals(5, PrimaryRights.parties(electorate(5)).size());
    }

    @Test void rejectsAlternatesAsExtraIndependentSeats() {
        assertThrows(IOException.class, () -> PrimaryRights.parties(electorate(7)));
        assertThrows(IOException.class, () -> PrimaryRights.parties(electorate(4)));
    }

    @Test void rejectsUnidentifiedOrMalformedSet() {
        var unnamed = electorate(5).toBuilder().setRecord(electorate(5).getRecord().toBuilder().clearRecordId()).build();
        assertThrows(IOException.class, () -> PrimaryRights.parties(unnamed));
        assertThrows(IOException.class, () -> PrimaryRights.parties(ValueOuterClass.Value.getDefaultInstance()));
    }
}
