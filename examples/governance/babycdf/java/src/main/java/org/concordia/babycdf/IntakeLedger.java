// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Loopback demo adapter, explicitly actAs opener; never the read-only reviewer gateway. */
public final class IntakeLedger implements Intake.Transport, AutoCloseable {
    private final String party, impl, demo, governor, user;
    private final ManagedChannel channel;
    private final java.nio.file.Path journal;
    @FunctionalInterface interface Rpc { void submit(CommandServiceOuterClass.SubmitAndWaitRequest request); }
    @FunctionalInterface interface Inventory { List<EventOuterClass.CreatedEvent> active(String pkg,String module,String entity) throws IOException; }
    private Rpc rpc;
    private Inventory inventory;
    private boolean retryRejected;
    private static final String RULE = "Cap.Examples.BabyCdf.RuleVote";
    private static final String TRIGGER = "Cap.Examples.BabyCdf.Intake";
    public IntakeLedger(int port, String party, String impl, String demo, String governor, String user, java.nio.file.Path journal) {
        if (port < 1 || port > 65535 || party == null || party.isBlank() || party.contains(" ")
                || !impl.matches("[0-9a-f]{64}") || !demo.matches("[0-9a-f]{64}")
                || governor == null || governor.isBlank() || user == null || user.isBlank())
            throw new IllegalArgumentException("BLOCKED: explicit loopback opener configuration required");
        this.party=party; this.impl=impl; this.demo=demo; this.governor=governor; this.user=user;
        this.journal=validateJournal(journal);
        channel=NettyChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();
        rpc=request -> CommandServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).submitAndWait(request);
        inventory=this::readActive;
    }
    IntakeLedger(String party,String impl,String demo,String governor,String user,java.nio.file.Path journal,
                 Rpc rpc,Inventory inventory,boolean retryRejected) {
        this.party=party; this.impl=impl; this.demo=demo; this.governor=governor; this.user=user;
        this.journal=validateJournal(journal); this.channel=null;
        this.rpc=rpc; this.inventory=inventory; this.retryRejected=retryRejected;
    }
    public void allowOneRejectedRetry() { retryRejected=true; }
    private List<EventOuterClass.CreatedEvent> active(String pkg,String module,String entity) throws IOException {
        return inventory.active(pkg,module,entity);
    }
    static java.nio.file.Path validateJournal(java.nio.file.Path path) {
        if(path == null) throw new IllegalArgumentException("BLOCKED: journal required");
        path=path.toAbsolutePath().normalize();
        for(var p=path; p!=null; p=p.getParent())
            if(java.nio.file.Files.isSymbolicLink(p)) throw new IllegalArgumentException("BLOCKED: symlink journal path");
        if(!java.nio.file.Files.isDirectory(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !java.nio.file.Files.isWritable(path)) throw new IllegalArgumentException("BLOCKED: existing writable journal directory required");
        return path;
    }
    static void recordIntent(java.nio.file.Path journal,String command,byte[] payload) throws IOException {
        if(!command.matches("babycdf-intake-[0-9a-f]{64}")) throw new IOException("BLOCKED: invalid intent command");
        persist(journal,command+".intent",payload);
    }
    /** Source adapter uses the same exclusive durability primitive, not intake retry policy. */
    static void recordSourceMarker(java.nio.file.Path journal,String name,byte[] payload) throws IOException {
        if(!name.matches("babycdf-source-v2-[0-9a-f]{64}[.](intent|receipt)"))
            throw new IOException("BLOCKED: invalid source journal marker");
        persist(journal,name,payload);
    }
    private static void persist(java.nio.file.Path journal,String name,byte[] payload) throws IOException {
        try { journal=validateJournal(journal); }
        catch(IllegalArgumentException e) { throw new IOException(e.getMessage(),e); }
        var permissions=java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try(var output=java.nio.channels.FileChannel.open(journal.resolve(name),
                java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS),permissions)) {
            var buffer=java.nio.ByteBuffer.wrap(payload);
            while(buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        try(var directory=java.nio.channels.FileChannel.open(journal,java.nio.file.StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
    static ValueOuterClass.Identifier id(String pkg, String module, String entity) {
        return ValueOuterClass.Identifier.newBuilder().setPackageId(pkg).setModuleName(module).setEntityName(entity).build();
    }
    static void verifyActiveEvent(EventOuterClass.CreatedEvent event,ValueOuterClass.Identifier expected,String party) throws IOException {
        if(!expected.equals(event.getTemplateId()) || !event.hasCreateArguments()
                || !event.getWitnessPartiesList().contains(party)) throw new IOException("BLOCKED: unexpected ACS event");
    }
    static final String DEMO_PACKAGE_NAME = "#cap-example-babycdf-demo";
    static final String IMPL_PACKAGE_NAME = "#cap-example-babycdf";
    static ValueOuterClass.Identifier filterId(String module,String entity) {
        String name;
        if(TRIGGER.equals(module)) name=DEMO_PACKAGE_NAME;
        else if(RULE.equals(module)) name=IMPL_PACKAGE_NAME;
        else throw new IllegalArgumentException("unknown Intake ledger module: "+module);
        return id(name,module,entity);
    }
    static StateServiceOuterClass.GetActiveContractsRequest activeContractsRequest(String party,String pkg,String module,String entity,long offset) {
        // Filters resolve package names; event verification pins the deployed package ID separately.
        var filterIdentifier=filterId(module,entity);
        var filter=TransactionFilterOuterClass.Filters.newBuilder().addCumulative(
                TransactionFilterOuterClass.CumulativeFilter.newBuilder().setTemplateFilter(
                        TransactionFilterOuterClass.TemplateFilter.newBuilder().setTemplateId(filterIdentifier))).build();
        var format=TransactionFilterOuterClass.EventFormat.newBuilder().putFiltersByParty(party,filter).setVerbose(true);
        return StateServiceOuterClass.GetActiveContractsRequest.newBuilder().setActiveAtOffset(offset).setEventFormat(format).build();
    }
    private List<EventOuterClass.CreatedEvent> readActive(String pkg,String module,String entity) throws IOException {
        var identifier=id(pkg,module,entity);
        try {
            var state=StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS);
            long offset=state.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
            var stream=state.getActiveContracts(activeContractsRequest(party,pkg,module,entity,offset));
            List<EventOuterClass.CreatedEvent> found=new ArrayList<>();
            while(stream.hasNext()) {
                var response=stream.next();
                if(response.hasIncompleteAssigned() || response.hasIncompleteUnassigned()) throw new IOException("BLOCKED: incomplete ACS");
                if(!response.hasActiveContract()) continue;
                var event=response.getActiveContract().getCreatedEvent();
                verifyActiveEvent(event,identifier,party);
                found.add(event);
            }
            return found;
        } catch(io.grpc.StatusRuntimeException e) { throw new IOException("BLOCKED: ACS failed",e); }
    }
    static ValueOuterClass.Value field(ValueOuterClass.Record r,String label) throws IOException {
        var fields=r.getFieldsList().stream().filter(f->f.getLabel().equals(label)).toList();
        if(fields.size()!=1 || !fields.getFirst().hasValue()) throw new IOException("BLOCKED: malformed field "+label);
        return fields.getFirst().getValue();
    }
    static String text(ValueOuterClass.Record r,String label) throws IOException {
        var v=field(r,label); if(!v.hasText()) throw new IOException("BLOCKED: expected text"); return v.getText();
    }
    static long number(ValueOuterClass.Record r,String label) throws IOException {
        var v=field(r,label); if(!v.hasInt64()) throw new IOException("BLOCKED: expected int64"); return v.getInt64();
    }
    public List<Intake.Request> requests() throws IOException {
        List<Intake.Request> result=new ArrayList<>();
        for(var event:active(demo,TRIGGER,"IntakeRequest")) {
            var r=event.getCreateArguments(); var opener=field(r,"opener");
            if(!opener.hasParty() || !party.equals(opener.getParty()) || !event.getSignatoriesList().equals(List.of(party)))
                throw new IOException("BLOCKED: request not signed by configured opener");
            result.add(new Intake.Request(event.getContractId(),party,text(r,"runId"),text(r,"roundId"),
                    text(r,"sourceKey"),number(r,"windowSeconds"),number(r,"baseVersion")));
        }
        return result;
    }
    public IntakePlan plan(Intake.Request request,Intake.Bytes bytes) throws IOException {
        var rows=active(demo,TRIGGER,"IntakeRequest").stream().filter(e->e.getContractId().equals(request.cid())).toList();
        if(rows.size()!=1) throw new IOException("BLOCKED: exact source-bound request unavailable");
        var record=rows.getFirst().getCreateArguments();
        var agenda=field(record,"agenda");var source=field(record,"source");
        if(!agenda.hasVariant() || !agenda.getVariant().getValue().hasRecord() || !source.hasRecord())
            throw new IOException("BLOCKED: source-bound request schema required");
        var constructor=agenda.getVariant().getConstructor();
        if(!java.util.Set.of("Grant","Milestone").contains(constructor)) throw new IOException("BLOCKED: unsupported source agenda");
        var subject=agenda.getVariant().getValue().getRecord();
        var binding=source.getRecord();
        if(!field(binding,"credentialCid").hasContractId() || number(binding,"expectedVersion")<1
                || !text(binding,"expectedFingerprint").matches("[0-9a-f]{64}")) throw new IOException("BLOCKED: invalid source credential binding");
        var selected=new IntakeSource(request.sourceKey(),IntakeSource.AgendaConstructor.valueOf(constructor),text(subject,"kind"),
                text(subject,"url"),text(subject,"contentVersion"),IntakeSource.Evidence.CURATED_UNVERIFIED);
        if(!selected.contentVersion().equals(text(binding,"expectedFingerprint"))) throw new IOException("BLOCKED: subject fingerprint mismatch");
        return IntakePlan.bound(selected,request.roundId(),agenda,source);
    }
    public boolean proof(Intake.Request request, IntakePlan plan) throws IOException {
        if(plan.boundSource()==null) return proof(request,plan,active(impl,RULE,"AgendaProposal"),active(impl,RULE,"AgendaAction"));
        var receipts=active(impl,RULE,"AgendaRoundReceipt").stream()
                .filter(e->request.roundId().equals(safeText(e.getCreateArguments(),"roundId"))).toList();
        if(receipts.isEmpty()) return false;
        if(receipts.size()!=1) throw new IOException("BLOCKED: duplicate durable round receipt");
        var receipt=receipts.getFirst();var r=receipt.getCreateArguments();
        if(!receipt.getSignatoriesList().equals(List.of(party)) || !party.equals(field(r,"opener").getParty())
                || !party.equals(field(r,"proposer").getParty()) || number(r,"baseVersion")!=request.baseVersion()
                || !sameValue(field(r,"agenda"),plan.boundAgenda())) throw new IOException("BLOCKED: durable round receipt mismatch");
        var copied=field(r,"sourceSnapshot");
        if(!copied.hasOptional() || !copied.getOptional().hasValue()) throw new IOException("BLOCKED: copied source missing");
        verifySnapshot(copied.getOptional().getValue().getRecord(),plan);
        if(!party.equals(field(copied.getOptional().getValue().getRecord(),"opener").getParty()))
            throw new IOException("BLOCKED: copied source opener mismatch");
        String action=field(r,"actionCid").getContractId();
        if(action.isBlank()) throw new IOException("BLOCKED: receipt missing linked action identity");
        // Receipt retains immutable round identity after archival. A missing action
        // is not accepted as execution proof: retained transaction evidence is required.
        var actions=active(impl,RULE,"AgendaAction");
        var linked=actions.stream().filter(e->e.getContractId().equals(action)).toList();
        if(linked.size()>1) throw new IOException("BLOCKED: duplicate linked action");
        if(linked.isEmpty()) linked=historicalAction(action);
        if(linked.size()!=1) throw new IOException("BLOCKED: linked action unavailable; historical transaction proof required");
        if(!linked.isEmpty() && (!linked.getFirst().getSignatoriesList().equals(List.of(party))
                || !party.equals(field(linked.getFirst().getCreateArguments(),"opener").getParty())
                || number(linked.getFirst().getCreateArguments(),"baseVersion")!=request.baseVersion()
                || !sameValue(field(linked.getFirst().getCreateArguments(),"agenda"),field(r,"agenda"))
                || !sameValue(field(linked.getFirst().getCreateArguments(),"sourceSnapshot"),copied)))
            throw new IOException("BLOCKED: linked action mismatch");
        return true;
    }
    @FunctionalInterface interface HistoricalActions {List<EventOuterClass.CreatedEvent> find(String action)throws IOException;}
    private HistoricalActions historicalActions;
    void historicalActions(HistoricalActions historical){this.historicalActions=java.util.Objects.requireNonNull(historical);}
    private List<EventOuterClass.CreatedEvent> historicalAction(String action) throws IOException {
        if(historicalActions!=null)return historicalActions.find(action);
        if(channel==null) return List.of(); // Test seam never opens a channel.
        try {
            long end=StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS)
                .getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();
            var filter=TransactionFilterOuterClass.Filters.newBuilder().addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder().setTemplateId(filterId(RULE,"AgendaAction"))));
            var format=TransactionFilterOuterClass.TransactionFormat.newBuilder().setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                .setEventFormat(TransactionFilterOuterClass.EventFormat.newBuilder().putFiltersByParty(party,filter.build()).setVerbose(true));
            var updates=UpdateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).getUpdates(
                UpdateServiceOuterClass.GetUpdatesRequest.newBuilder().setBeginExclusive(0).setEndInclusive(end)
                .setUpdateFormat(TransactionFilterOuterClass.UpdateFormat.newBuilder().setIncludeTransactions(format)).build());
            var found=new ArrayList<EventOuterClass.CreatedEvent>();int count=0,bytes=0;
            while(updates.hasNext()) {var update=updates.next();bytes+=update.getSerializedSize();
                if(++count>10000 || bytes>20_000_000) throw new IOException("BLOCKED: historical action proof exceeds bound");
                if(update.hasTransaction()) for(var event:update.getTransaction().getEventsList())
                    if(event.hasCreated() && event.getCreated().getContractId().equals(action)) {
                        verifyActiveEvent(event.getCreated(),id(impl,RULE,"AgendaAction"),party);found.add(event.getCreated());
                    }
            }
            return found;
        } catch(io.grpc.StatusRuntimeException unavailable) {throw new IOException("BLOCKED: historical action evidence unavailable; no retry");}
    }
    private static String safeText(ValueOuterClass.Record r,String name) {try{return text(r,name);}catch(IOException e){return "";}}
    private static boolean sameValue(ValueOuterClass.Value a,ValueOuterClass.Value b) {
        return normalize(a).equals(normalize(b));
    }
    private static ValueOuterClass.Value normalize(ValueOuterClass.Value value) {
        var b=value.toBuilder();
        if(value.hasRecord()) {var r=value.getRecord().toBuilder().clearRecordId().clearFields();
            for(var f:value.getRecord().getFieldsList())r.addFields(f.toBuilder().setValue(normalize(f.getValue())));b.setRecord(r);}
        else if(value.hasVariant())b.setVariant(value.getVariant().toBuilder().clearVariantId().setValue(normalize(value.getVariant().getValue())));
        else if(value.hasEnum())b.setEnum(value.getEnum().toBuilder().clearEnumId());
        else if(value.hasOptional() && value.getOptional().hasValue())b.setOptional(value.getOptional().toBuilder().setValue(normalize(value.getOptional().getValue())));
        else if(value.hasList()) {var l=value.getList().toBuilder().clearElements();for(var v:value.getList().getElementsList())l.addElements(normalize(v));b.setList(l);}
        return b.build();
    }
    private static void verifySnapshot(ValueOuterClass.Record snapshot,IntakePlan plan) throws IOException {
        var source=plan.boundSource().getRecord();
        var observed=field(snapshot,"observation");
        var previous=field(snapshot,"previousDigest");
        if(!previous.hasOptional()) throw new IOException("BLOCKED: copied lineage shape mismatch");
        String prior=previous.getOptional().hasValue()?previous.getOptional().getValue().getText():null;
        if(!SourceCatalogueDigest.digest(observed.getRecord(),number(snapshot,"version"),prior).equals(text(snapshot,"fingerprint")))
            throw new IOException("BLOCKED: copied fingerprint mismatch");
        if(!observed.hasRecord() || !field(snapshot,"opener").hasParty()
                || !text(snapshot,"evidenceDigest").equals(text(observed.getRecord(),"bodySha256"))
                || !text(observed.getRecord(),"sourceUrl").equals(plan.source().url())) throw new IOException("BLOCKED: copied observation mismatch");
        if(!field(snapshot,"sourceCid").getContractId().equals(field(source,"credentialCid").getContractId())
                || !text(snapshot,"fingerprint").equals(text(source,"expectedFingerprint"))
                || number(snapshot,"version")!=number(source,"expectedVersion")
                || !sameValue(field(snapshot,"pin"),field(source,"pin"))
                || number(snapshot,"originalProposal")!=number(source,"originalProposal")
                || !sameValue(field(snapshot,"previousRound"),field(source,"previousRound"))
                || !text(snapshot,"roundId").equals(plan.roundId())) throw new IOException("BLOCKED: copied source pin mismatch");
    }
    static boolean proof(Intake.Request request,IntakePlan plan,List<EventOuterClass.CreatedEvent> proposals,
                         List<EventOuterClass.CreatedEvent> actions) throws IOException {
        String party=request.opener();
        int count=0;
        for(var proposal:proposals) {
            var p=proposal.getCreateArguments();
            if(!request.roundId().equals(text(p,"roundId"))) continue;
            if(!proposal.getSignatoriesList().contains(party)) throw new IOException("BLOCKED: proposal signatory mismatch");
            count++;
            var proposer=field(p,"proposer"); var opener=field(p,"opener"); var ref=field(p,"actionCid");
            if(!proposer.hasParty() || !party.equals(proposer.getParty()) || !opener.hasParty() || !party.equals(opener.getParty())
                    || !ref.hasContractId()) throw new IOException("BLOCKED: round identity mismatch");
            var linked=actions.stream().filter(a->a.getContractId().equals(ref.getContractId())).toList();
            if(linked.size()!=1) throw new IOException("BLOCKED: missing linked action");
            if(!linked.getFirst().getSignatoriesList().contains(party)) throw new IOException("BLOCKED: action signatory mismatch");
            var action=linked.getFirst().getCreateArguments(); var agenda=field(action,"agenda");
            if(number(action,"baseVersion")!=request.baseVersion() || !agenda.hasVariant()
                    || !agenda.getVariant().getConstructor().equals(plan.source().agendaConstructor().name())
                    || !agenda.getVariant().getValue().hasRecord())
                throw new IOException("BLOCKED: round agenda mismatch");
            var subject=agenda.getVariant().getValue().getRecord();
            if(!text(subject,"kind").equals(plan.source().kind()) || !text(subject,"url").equals(plan.source().url())
                    || !text(subject,"contentVersion").equals(plan.source().contentVersion()))
                throw new IOException("BLOCKED: round subject mismatch");
        }
        if(count>1) throw new IOException("BLOCKED: duplicate round");
        return count==1;
    }
        static ValueOuterClass.Value txt(String t) { return ValueOuterClass.Value.newBuilder().setText(t).build(); }
    static ValueOuterClass.RecordField f(String name,ValueOuterClass.Value value) {
        return ValueOuterClass.RecordField.newBuilder().setLabel(name).setValue(value).build();
    }
    static ValueOuterClass.Value agenda(IntakeSource source) {
        var subject=ValueOuterClass.Record.newBuilder().addFields(f("kind",txt(source.kind())))
                .addFields(f("url",txt(source.url()))).addFields(f("contentVersion",txt(source.contentVersion())));
        return ValueOuterClass.Value.newBuilder().setVariant(ValueOuterClass.Variant.newBuilder()
                .setConstructor(source.agendaConstructor().name()).setValue(ValueOuterClass.Value.newBuilder().setRecord(subject))).build();
    }
    static CommandServiceOuterClass.SubmitAndWaitRequest request(Intake.Request r,IntakePlan plan,
                                                                String demo,String governor,String user) {
        var args=ValueOuterClass.Record.newBuilder().addFields(f("governorCid",
                ValueOuterClass.Value.newBuilder().setContractId(governor).build()));
        var exercise=CommandsOuterClass.ExerciseCommand.newBuilder().setTemplateId(id(demo,TRIGGER,"IntakeRequest"))
                .setContractId(r.cid()).setChoice("OpenAgenda").setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(args));
        return CommandServiceOuterClass.SubmitAndWaitRequest.newBuilder().setCommands(CommandsOuterClass.Commands.newBuilder()
                .setUserId(user).setCommandId(plan.commandId()).addActAs(r.opener())
                .setDeduplicationDuration(com.google.protobuf.Duration.newBuilder().setSeconds(86400))
                .addCommands(CommandsOuterClass.Command.newBuilder().setExercise(exercise))).build();
    }
    public void submit(Intake.Request r,IntakePlan plan) throws IOException {
        // Production plans come exclusively from signed source-bound requests;
        // legacy plans remain a bounded test/old-journal compatibility seam.
        // Verify governor's opener, not the member-only SandboxBallots authorization path.
        var governors=active(impl,RULE,"AgendaGovernor").stream().filter(g->g.getContractId().equals(governor)).toList();
        if(!party.equals(r.opener()) || !plan.roundId().equals(r.roundId()) || !plan.source().sourceKey().equals(r.sourceKey()))
            throw new Intake.PreTransmission("BLOCKED: plan/request identity mismatch",null);
        if(governors.size()!=1 || !party.equals(field(governors.getFirst().getCreateArguments(),"opener").getParty()))
            throw new Intake.PreTransmission("BLOCKED: governor opener mismatch",null);
        // Durable create-new intent marker prevents restart-based blind resubmission.
        // Never delete automatically: an absent active proposal is not nonexecution proof.
        var command=request(r,plan,demo,governor,user);
        byte[] intent=command.toByteArray();
        String attempt=plan.commandId();
        try {
            if(retryRejected) {
                retryRejected=false;
                if(plan.boundSource()!=null ? proof(r,plan) : proof(r,plan,active(impl,RULE,"AgendaProposal"),active(impl,RULE,"AgendaAction")))
                    throw new IOException("BLOCKED: round already exists; reconcile instead of retry");
                if(!java.util.Arrays.equals(readMarker(journal.resolve(attempt+".intent")),intent))
                    throw new IOException("BLOCKED: retry payload changed");
                String outcome=new String(readMarker(journal.resolve(attempt+".rejected")),java.nio.charset.StandardCharsets.US_ASCII);
                if(!definite(outcome)) throw new IOException("BLOCKED: no definite rejection evidence");
                attempt=attempt+"-retry";
                persist(journal,attempt+".intent",intent);
            } else recordIntent(journal,attempt,intent);
        } catch(IOException e) { throw new Intake.PreTransmission("BLOCKED: intent exists or persistence/retry validation failed; no RPC submitted",e); }
        try { rpc.submit(command); }
        catch(io.grpc.StatusRuntimeException e) {
            String code=e.getStatus().getCode().name();
            if(definite(code)) {
                persist(journal,attempt+".rejected",code.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                throw new Intake.Rejected("REJECTED: "+code+"; recorded, explicit --retry-rejected permits at most one retry",e);
            }
            throw new IOException("BLOCKED: uncertain submit "+code+"; proof only, retry prohibited",e);
        }
    }
    static boolean definite(String code) {
        return java.util.Set.of("PERMISSION_DENIED","UNAUTHENTICATED","INVALID_ARGUMENT","NOT_FOUND").contains(code);
    }
    static byte[] readMarker(java.nio.file.Path path) throws IOException {
        try(var input=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if(input.size()>1_000_000) throw new IOException("BLOCKED: oversized marker");
            var bytes=java.nio.ByteBuffer.allocate((int)input.size());
            while(bytes.hasRemaining()) if(input.read(bytes)<0) throw new IOException("BLOCKED: truncated marker");
            return bytes.array();
        }
    }
    public void close() { if(channel!=null) channel.shutdownNow(); }
}
