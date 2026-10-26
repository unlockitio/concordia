package org.concordia.babycdf;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.TransactionFilterOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import com.daml.ledger.api.v2.testing.TimeServiceGrpc;
import com.daml.ledger.api.v2.testing.TimeServiceOuterClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Explicit loopback-only ballot opener. No vote, invitation, resolution or coupon command. */
public final class SandboxBallots implements Advisory.LedgerApi, AutoCloseable {
    static final String MODULE = "Cap.Examples.BabyCdf.RuleVote";
    private final String party, packageId, governorCid, userId;
    public String party() { return party; }
    public String governorCid() { return governorCid; }
    private final ManagedChannel channel;
    private final StateServiceGrpc.StateServiceBlockingStub state;
    private final CommandServiceGrpc.CommandServiceBlockingStub commands;

    public SandboxBallots(int port, String party, String packageId, String governorCid, String userId) {
        if (port < 1 || port > 65535 || party == null || party.isBlank() || party.contains(" ")
                || packageId == null || !packageId.matches("[0-9a-f]{64}")
                || governorCid == null || governorCid.isBlank() || userId == null || userId.isBlank())
            throw new IllegalArgumentException("local sandbox port, demo member, deployed package ID, governor CID and sandbox user ID required");
        this.party = party;
        this.packageId = packageId;
        this.governorCid = governorCid;
        this.userId = userId;
        channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        state = StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS);
        commands = CommandServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS);
    }

    private ValueOuterClass.Identifier id(String entity) {
        return ValueOuterClass.Identifier.newBuilder().setPackageId(packageId).setModuleName(MODULE).setEntityName(entity).build();
    }

    private List<EventOuterClass.CreatedEvent> active(String entity) throws IOException {
        var filter = TransactionFilterOuterClass.Filters.newBuilder()
                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                        .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                .setTemplateId(ValueOuterClass.Identifier.newBuilder()
                                        .setPackageId("#cap-example-babycdf").setModuleName(MODULE).setEntityName(entity)))).build();
        var format = TransactionFilterOuterClass.EventFormat.newBuilder().putFiltersByParty(party, filter).setVerbose(true).build();
        try {
            long offset = state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
            var response = state.getActiveContracts(StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                    .setActiveAtOffset(offset).setEventFormat(format).build());
            List<EventOuterClass.CreatedEvent> found = new ArrayList<>();
            while (response.hasNext()) {
                var next = response.next();
                if (next.hasIncompleteAssigned() || next.hasIncompleteUnassigned())
                    throw new IOException("BLOCKED: incomplete sandbox ACS reassignment");
                if (!next.hasActiveContract()) continue;
                var event = next.getActiveContract().getCreatedEvent();
                if (!event.hasTemplateId() || !id(entity).equals(event.getTemplateId())
                        || !event.hasCreateArguments() || !event.getWitnessPartiesList().contains(party))
                    throw new IOException("BLOCKED: unexpected or invisible " + entity + " contract");
                found.add(event);
            }
            return found;
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("BLOCKED: sandbox ACS read failed: " + e.getStatus(), e);
        }
    }

    private static ValueOuterClass.Value field(ValueOuterClass.Record record, String name) throws IOException {
        ValueOuterClass.Value found = null;
        for (var f : record.getFieldsList()) if (name.equals(f.getLabel()) && f.hasValue()) {
            if (found != null) throw new IOException("BLOCKED: duplicate " + name);
            found = f.getValue();
        }
        if (found == null) throw new IOException("BLOCKED: missing " + name);
        return found;
    }
    private static String text(ValueOuterClass.Value v) throws IOException {
        if (!v.hasText()) throw new IOException("BLOCKED: expected text");
        return v.getText();
    }
    private static ValueOuterClass.Record record(ValueOuterClass.Value v) throws IOException {
        if (!v.hasRecord()) throw new IOException("BLOCKED: expected record");
        return v.getRecord();
    }
    static boolean matches(EventOuterClass.CreatedEvent proposal, EventOuterClass.CreatedEvent action,
                           String roundId, String version) throws IOException {
        var p = proposal.getCreateArguments();
        if (!roundId.equals(text(field(p, "roundId")))) return false;
        var actionRef = field(p, "actionCid");
        if (!actionRef.hasContractId() || !actionRef.getContractId().equals(action.getContractId())) return false;
        var agenda = field(action.getCreateArguments(), "agenda");
        if (!agenda.hasVariant() || !"Grant".equals(agenda.getVariant().getConstructor())) return false;
        var s = record(agenda.getVariant().getValue());
        return "proposal".equals(text(field(s, "kind")))
                && version.equals(text(field(s, "contentVersion")))
                && ("https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521/proposals/concordia.md")
                        .equals(text(field(s, "url")));
    }
    private static Set<String> setParties(ValueOuterClass.Value value) throws IOException {
        if (!value.hasRecord()) throw new IOException("BLOCKED: malformed governor electorate");
        var set = value.getRecord();
        if (!set.hasRecordId() || !"DA.Set.Types".equals(set.getRecordId().getModuleName())
                || !"Set".equals(set.getRecordId().getEntityName()))
            throw new IOException("BLOCKED: malformed governor electorate");
        var map = field(set, "map");
        if (!map.hasGenMap() || set.getFieldsCount() != 1)
            throw new IOException("BLOCKED: malformed governor electorate");
        Set<String> parties = new HashSet<>();
        for (var entry : map.getGenMap().getEntriesList()) {
            if (!entry.getKey().hasParty() || !entry.getValue().hasUnit()
                    || !parties.add(entry.getKey().getParty()))
                throw new IOException("BLOCKED: malformed electorate");
        }
        return parties;
    }
    public boolean authorizedProposer(String candidate, String governor) throws IOException {
        if (!party.equals(candidate) || !governorCid.equals(governor)) return false;
        for (var event : active("AgendaGovernor")) if (governor.equals(event.getContractId())) {
            var args = event.getCreateArguments();
            var permanent = setParties(field(args, "permanent"));
            var alternates = setParties(field(args, "alternates"));
            if (permanent.size() != 5 || alternates.size() != 2)
                throw new IOException("BLOCKED: unexpected demo electorate");
            Set<String> members = new HashSet<>(permanent);
            members.addAll(alternates);
            return members.size() == 7 && members.contains(party) && field(args, "opener").hasParty()
                    && event.getSignatoriesList().contains(field(args, "opener").getParty());
        }
        return false;
    }
    public int activeRoundCount(String roundId) throws IOException {
        if (!roundId.matches("babycdf:grant:184:[0-9a-f]{40}")) throw new IOException("BLOCKED: invalid grant round");
        String version = roundId.substring("babycdf:grant:184:".length());
        if (!"f591787a772848e4537aba5d8ba02be1764647da".equals(version))
            throw new IOException("BLOCKED: deployed demo validAgenda does not accept this PR blob revision");
        List<EventOuterClass.CreatedEvent> proposals = active("AgendaProposal");
        List<EventOuterClass.CreatedEvent> actions = active("AgendaAction");
        int matches = 0;
        for (var p : proposals) if (roundId.equals(text(field(p.getCreateArguments(), "roundId")))) {
            int linked = 0;
            for (var a : actions) if (matches(p, a, roundId, version)) linked++;
            if (linked != 1) throw new IOException("BLOCKED: round has missing or mismatched active action");
            matches++;
        }
        if (matches > 1) throw new IOException("BLOCKED: duplicate active round");
        return matches;
    }
    public boolean roundExists(String roundId) throws IOException { return activeRoundCount(roundId) == 1; }
    private static ValueOuterClass.Value valueText(String text) { return ValueOuterClass.Value.newBuilder().setText(text).build(); }
    private static ValueOuterClass.RecordField fieldValue(String name, ValueOuterClass.Value v) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(v).build();
    }
    static CommandServiceOuterClass.SubmitAndWaitRequest request(Advisory.BallotCommand c, String pkg, String user,
                                                                 long ledgerTimeSeconds) {
        var subject = ValueOuterClass.Record.newBuilder()
                .addFields(fieldValue("kind", valueText("proposal")))
                .addFields(fieldValue("url", valueText("https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521/proposals/concordia.md")))
                .addFields(fieldValue("contentVersion", valueText(c.contentVersion()))).build();
        var agenda = ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder()
                .setConstructor("Grant").setValue(ValueOuterClass.Value.newBuilder().setRecord(subject))).build();
        var args = ValueOuterClass.Record.newBuilder()
                .addFields(fieldValue("proposer", ValueOuterClass.Value.newBuilder().setParty(c.proposer()).build()))
                .addFields(fieldValue("roundId", valueText(c.roundId())))
                .addFields(fieldValue("agenda", agenda))
                .addFields(fieldValue("baseVersion", ValueOuterClass.Value.newBuilder().setInt64(0).build()))
                .addFields(fieldValue("closesAt", ValueOuterClass.Value.newBuilder().setTimestamp(
                        Math.multiplyExact(Math.addExact(ledgerTimeSeconds, 3600), 1_000_000)).build())).build();
        var exercise = CommandsOuterClass.ExerciseCommand.newBuilder().setTemplateId(ValueOuterClass.Identifier.newBuilder()
                .setPackageId(pkg).setModuleName(MODULE).setEntityName("AgendaGovernor"))
                .setContractId(c.governorContractId()).setChoice("ProposeAgenda")
                .setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(args)).build();
        return CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder().setCommands(CommandsOuterClass.Commands.newBuilder()
                .setUserId(user).setCommandId("babycdf-grant-" + c.roundId().substring("babycdf:grant:184:".length()))
                .addActAs(c.proposer()).setDeduplicationDuration(com.google.protobuf.Duration.newBuilder().setSeconds(3600))
                .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise))).build();
    }
    public void propose(Advisory.BallotCommand c) throws IOException {
        if (!"grant".equals(c.kind()) || !"https://github.com/canton-foundation/canton-dev-fund/pull/184".equals(c.subjectUrl())
                || !Advisory.SHA.matcher(c.contentVersion()).matches()
                || !c.roundId().equals("babycdf:grant:184:" + c.contentVersion())
                || !party.equals(c.proposer()) || !governorCid.equals(c.governorContractId()))
            throw new IOException("BLOCKED: invalid sandbox grant command");
        // This demo Daml choice accepts only the hard-pinned proposalSubject, not arbitrary PR revisions.
        if (!"f591787a772848e4537aba5d8ba02be1764647da".equals(c.contentVersion()))
            throw new IOException("BLOCKED: deployed demo validAgenda does not accept this PR blob revision");
        long ledgerTime;
        try {
            var clock = TimeServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS)
                    .getTime(TimeServiceOuterClass.GetTimeRequest.getDefaultInstance());
            if (!clock.hasCurrentTime()) throw new IOException("BLOCKED: sandbox ledger clock unavailable");
            ledgerTime = clock.getCurrentTime().getSeconds();
        } catch (io.grpc.StatusRuntimeException e) {
            throw new IOException("BLOCKED: sandbox ledger clock unavailable: " + e.getStatus(), e);
        }
        try { commands.submitAndWait(request(c, packageId, userId, ledgerTime)); }
        catch (io.grpc.StatusRuntimeException e) { throw new IOException("BLOCKED: uncertain sandbox submit: " + e.getStatus(), e); }
    }
    public void close() { channel.shutdownNow(); }
}
