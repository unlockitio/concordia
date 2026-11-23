// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.*;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.concordia.babycdf.SourceCatalogueCodec.*;

/** Explicit loopback catalogue writer used by the integrated Intake sweep worker. */
public final class SourceCatalogueLedger implements JavaSourceIngestion.Ledger, AutoCloseable {
    interface Rpc {
        long end() throws IOException;
        Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest request) throws IOException;
        TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request) throws IOException;
        Iterator<UpdateServiceOuterClass.GetUpdatesResponse> updates(UpdateServiceOuterClass.GetUpdatesRequest request) throws IOException;
    }
    private final String party,pkg,user;
    private String credentialPackage;
    public SourceCatalogueLedger(int port,String opener,String implPackage,String demoPackage,String user,Path journal) {
        this(opener,demoPackage,user,journal,connectExplicit(port,implPackage));
        credentialPackage=implPackage;
    }
    private static ManagedChannel connectExplicit(int port,String implPackage) {
        if(implPackage==null || !implPackage.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("explicit credential package required");
        return connect(port);
    }
    private final Path journal;
    private final Rpc rpc;
    private final ManagedChannel channel;
    private static final int MAX_EVENTS=10000, MAX_BYTES=20_000_000;
    record State(String cid,ValueOuterClass.Record record,long high,long generation,String phase,String token,
                 boolean progress,long full,long overlaps,String fingerprint) {
        JavaSourceIngestion.Checkpoint checkpoint() {return new JavaSourceIngestion.Checkpoint(cid,generation,token,progress);}
    }
    private static ManagedChannel connect(int port) {
        if(port<1 || port>65535) throw new IllegalArgumentException("invalid catalogue port");
        return NettyChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().maxInboundMessageSize(MAX_BYTES).build();
    }
    private SourceCatalogueLedger(String party,String pkg,String user,Path journal,ManagedChannel channel) {
        this(party,pkg,user,journal,new Rpc() {
            public long end() {return StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS)
                    .getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance()).getOffset();}
            public Iterator<StateServiceOuterClass.GetActiveContractsResponse> active(StateServiceOuterClass.GetActiveContractsRequest r) {
                return StateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).getActiveContracts(r);}
            public TransactionOuterClass.Transaction submit(CommandServiceOuterClass.SubmitAndWaitForTransactionRequest r) {
                return CommandServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).submitAndWaitForTransaction(r).getTransaction();}
            public Iterator<UpdateServiceOuterClass.GetUpdatesResponse> updates(UpdateServiceOuterClass.GetUpdatesRequest r) {
                return UpdateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(15,TimeUnit.SECONDS).getUpdates(r);}
        },channel);
    }
    SourceCatalogueLedger(String party,String pkg,String user,Path journal,Rpc rpc) {this(party,pkg,user,journal,rpc,null);}
    private SourceCatalogueLedger(String party,String pkg,String user,Path journal,Rpc rpc,ManagedChannel channel) {
        if(party==null || party.isBlank() || party.chars().anyMatch(Character::isWhitespace)
                || pkg==null || !pkg.matches("[0-9a-f]{64}") || user==null || user.isBlank())
            throw new IllegalArgumentException("explicit catalogue opener/package/user required");
        this.party=party;this.pkg=pkg;this.credentialPackage=pkg;this.user=user;this.journal=IntakeLedger.validateJournal(journal);
        this.rpc=Objects.requireNonNull(rpc);this.channel=channel;
    }
    private ValueOuterClass.Identifier id(String entity) {
        return IntakeLedger.id(entity.equals("SourceObservationCredential")?credentialPackage:pkg,
                entity.equals("SourceObservationCredential")?"Cap.Examples.BabyCdf.SourceObservationCredential":MODULE,entity);
    }
    private TransactionFilterOuterClass.EventFormat format(String... entities) {
        var filter=TransactionFilterOuterClass.Filters.newBuilder();
        for(String name:entities) filter.addCumulative(
                TransactionFilterOuterClass.CumulativeFilter.newBuilder().setTemplateFilter(
                        TransactionFilterOuterClass.TemplateFilter.newBuilder().setTemplateId(IntakeLedger.id(name.equals("SourceObservationCredential")?IntakeLedger.IMPL_PACKAGE_NAME:IntakeLedger.DEMO_PACKAGE_NAME, name.equals("SourceObservationCredential")?"Cap.Examples.BabyCdf.SourceObservationCredential":MODULE,name))));
        return TransactionFilterOuterClass.EventFormat.newBuilder().putFiltersByParty(party,filter.build()).setVerbose(true).build();
    }
    private TransactionFilterOuterClass.TransactionFormat transactions() {
        return TransactionFilterOuterClass.TransactionFormat.newBuilder().setEventFormat(format("SourceCheckpoint","SourceObservationCredential"))
                .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS).build();
    }
    private void identity(EventOuterClass.CreatedEvent event,String entity) throws IOException {
        if(!event.getTemplateId().equals(id(entity)) || !event.hasCreateArguments() || event.getContractId().isBlank()
                || !event.getWitnessPartiesList().contains(party) || !event.getSignatoriesList().equals(List.of(party)))
            throw new IOException("unexpected catalogue event identity");
        var r=event.getCreateArguments();var opener=IntakeLedger.field(r,"opener");
        if(!opener.hasParty() || !party.equals(opener.getParty()) || !REPOSITORY.equals(IntakeLedger.text(r,"repo")))
            throw new IOException("catalogue source key mismatch");
    }
    private List<EventOuterClass.CreatedEvent> active(long offset,String entityFilter) throws IOException {
        try {
            var stream=rpc.active(StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                    .setActiveAtOffset(offset).setEventFormat(format(entityFilter)).build());
            var result=new ArrayList<EventOuterClass.CreatedEvent>(); int count=0,bytes=0;
            while(stream.hasNext()) {
                var response=stream.next(); bytes+=response.getSerializedSize();
                if(++count>MAX_EVENTS || bytes>MAX_BYTES || response.hasIncompleteAssigned() || response.hasIncompleteUnassigned())
                    throw new IOException("bounded coherent catalogue ACS unavailable");
                if(response.hasActiveContract()) {
                    var event=response.getActiveContract().getCreatedEvent();
                    String entity=event.getTemplateId().getEntityName();
                    if(!entityFilter.equals(entity)) throw new IOException("unexpected catalogue template");
                    identity(event,entity);result.add(event);
                }
            }
            return result;
        } catch(io.grpc.StatusRuntimeException e) {throw new IOException("catalogue ACS unavailable");}
    }
    private State state(EventOuterClass.CreatedEvent event) throws IOException {
        identity(event,"SourceCheckpoint");
        var r=event.getCreateArguments();
        fields(r,Set.of("opener","repo","schema","highWater","generation","phase","pageToken","inProgress",
                "completedFullGeneration","overlapsSinceFull","scanFingerprint"));
        if(!IntakeLedger.text(r,"schema").equals(SourceObservationCanonical.SCHEME)) throw new IOException("legacy source checkpoint requires explicit migration");
        long high=IntakeLedger.number(r,"highWater"),gen=IntakeLedger.number(r,"generation"),full=IntakeLedger.number(r,"completedFullGeneration"),
                overlaps=IntakeLedger.number(r,"overlapsSinceFull");
        String phase=constructor(IntakeLedger.field(r,"phase")),token=IntakeLedger.text(r,"pageToken"),fingerprint=IntakeLedger.text(r,"scanFingerprint");
        var progress=IntakeLedger.field(r,"inProgress");
        if(high<0 || gen<0 || full<0 || full>gen || overlaps<0 || overlaps>3 || token.length()>512 || fingerprint.length()>64
                || !Set.of("FullSweep","OverlapSweep").contains(phase) || !progress.hasBool())
            throw new IOException("invalid catalogue checkpoint");
        return new State(event.getContractId(),r,high,gen,phase,token,progress.getBool(),full,overlaps,fingerprint);
    }
    private Optional<State> checkpoint(List<EventOuterClass.CreatedEvent> rows) throws IOException {
        State found=null;
        for(var e:rows) if(e.getTemplateId().equals(id("SourceCheckpoint"))) {
            if(found!=null) throw new IOException("duplicate catalogue checkpoint key");found=state(e);
        }
        return Optional.ofNullable(found);
    }
    private CommandsOuterClass.Commands commands(String command,CommandsOuterClass.Command action) {
        return CommandsOuterClass.Commands.newBuilder().setUserId(user).setCommandId(command).addActAs(party)
                .setDeduplicationDuration(com.google.protobuf.Duration.newBuilder().setSeconds(86400)).addCommands(action).build();
    }
    private CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request(CommandsOuterClass.Commands commands) {
        return CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder().setCommands(commands)
                .setTransactionFormat(transactions()).build();
    }
    @FunctionalInterface interface IntentWriter {void write(Path journal,String name,byte[] payload) throws IOException;}
    IntentWriter intentWriter=IntakeLedger::recordSourceMarker;
    private void intent(String command,CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request,long offset) throws IOException {
        // Offset and exact typed command survive process restart. Exclusive creation is the send gate.
        if(request.getSerializedSize()>999_992) throw new IOException("source intent exceeds durable payload limit; split batch before submission");
        var payload=java.nio.ByteBuffer.allocate(8+request.getSerializedSize()).putLong(offset).put(request.toByteArray()).array();
        intentWriter.write(journal,command+".intent",payload);
    }
    private long end() throws IOException {
        try {return rpc.end();} catch(io.grpc.StatusRuntimeException e) {throw new IOException("catalogue ledger end unavailable");}
    }
    private Path marker(String command,String suffix) throws IOException {
        if(!command.matches("babycdf-source-v2-[0-9a-f]{64}")) throw new IOException("invalid catalogue command identity");
        return journal.resolve(command+suffix);
    }
    private void reconcilePending() throws IOException {
        try(var legacy=Files.newDirectoryStream(journal,"babycdf-source-*.intent")) {
            for(Path path:legacy) if(!path.getFileName().toString().startsWith("babycdf-source-v2-"))
                throw new IOException("legacy source intent requires explicit reconciliation; no replay");
        }
        try(var paths=Files.newDirectoryStream(journal,"babycdf-source-v2-*.intent")) {
            int count=0;
            for(Path path:paths) {
                if(++count>10000) throw new IOException("source journal reconciliation bound exceeded");
                byte[] bytes=IntakeLedger.readMarker(path);
                if(bytes.length<9) throw new IOException("invalid source intent");
                var request=CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.parseFrom(Arrays.copyOfRange(bytes,8,bytes.length));
                var commands=request.getCommands();
                if(commands.getCommandsCount()!=1) throw new IOException("invalid source intent command count");
                if(!path.getFileName().toString().equals(commands.getCommandId()+".intent")) throw new IOException("source intent filename mismatch");
                if(commands.getCommands(0).hasCreate()) {
                    var create=commands.getCommands(0).getCreate();
                    if(!create.getTemplateId().equals(id("SourceCheckpoint"))
                            || !IntakeLedger.text(create.getCreateArguments(),"schema").equals(SourceObservationCanonical.SCHEME))
                        throw new IOException("legacy source creation schema; no replay");
                    continue;
                }
                var pending=commands.getCommands(0).getExercise();
                if(!pending.getTemplateId().equals(id("SourceCheckpoint")) || !pending.getChoice().equals("CommitBatch"))
                    throw new IOException("unsupported source intent command schema");
                canonicalArgument(pending.getChoiceArgument()); // Validate v2 even when a terminal receipt exists.
                Path receipt=marker(commands.getCommandId(),".receipt");
                if(Files.exists(receipt,LinkOption.NOFOLLOW_LINKS)) {
                    String terminal=new String(IntakeLedger.readMarker(receipt),StandardCharsets.US_ASCII);
                    String binding=SourceObservation.hash(Base64.getEncoder().encodeToString(bytes));
                    var status=receipt(terminal,binding,beginOffset(bytes));
                    if(status.rejected) throw new IOException("source command definitely rejected; operator reconciliation required; no automatic retry");
                    continue; // Local completion status only. proof() still requires ledger evidence.
                }
                var exercise=commands.getCommands(0).getExercise();
                long begin=java.nio.ByteBuffer.wrap(bytes).getLong();
                var pre=checkpoint(active(begin,"SourceCheckpoint"))
                        .orElseThrow(()->new IOException("historical source checkpoint unavailable; reconciliation blocked"));
                if(!pre.cid.equals(exercise.getContractId())) throw new IOException("historical source checkpoint CID mismatch");
                var args=fields(exercise.getChoiceArgument().getRecord(),Set.of("items","scanPhase","nextToken","complete"));
                var observations=new ArrayList<SourceObservation>();
                for(var value:args.get("items").getList().getElementsList()) observations.add(observation(value.getRecord()));
                var batch=new JavaSourceIngestion.Batch(pre.checkpoint(),new JavaSourceIngestion.Page(observations,
                        text(args.get("nextToken")),args.get("complete").getBool()),commands.getCommandId());
                if(proof(batch).isEmpty()) throw new IOException("pending source command requires transaction proof; no new scan or replay");
            }
        }
    }
    public synchronized JavaSourceIngestion.Checkpoint checkpoint() throws IOException {
        reconcilePending();
        try {
            var found=checkpoint(active(end(),"SourceCheckpoint")); if(found.isPresent()) return found.get().checkpoint();
            String command="babycdf-source-v2-"+SourceObservation.hash("create|"+pkg+"|"+party+"|"+REPOSITORY);
            var initial=record("opener",ValueOuterClass.Value.newBuilder().setParty(party).build(),"repo",text(REPOSITORY),"schema",text(SourceObservationCanonical.SCHEME),
                    "highWater",integer(0),"generation",integer(0),"phase",constructor("FullSweep"),"pageToken",text(""),
                    "inProgress",bool(false),"completedFullGeneration",integer(0),"overlapsSinceFull",integer(0),"scanFingerprint",text(""));
            var create=CommandsOuterClass.Command.newBuilder().setCreate(CommandsOuterClass.CreateCommand.newBuilder()
                    .setTemplateId(id("SourceCheckpoint")).setCreateArguments(initial.getRecord())).build();
            var request=request(commands(command,create));
            try {
                Path existing=marker(command,".intent");
                if(Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes=IntakeLedger.readMarker(existing);beginOffset(bytes);
                    var original=CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.parseFrom(Arrays.copyOfRange(bytes,8,bytes.length));
                    if(!original.equals(request)) throw new IOException("checkpoint creation intent changed; no RPC");
                    // Only keyed CREATE may resume identical bytes: uniqueness is the ledger concurrency guard.
                    rpc.submit(original);
                } else {intent(command,request,end());rpc.submit(request);}
            }
            catch(IOException | io.grpc.StatusRuntimeException failure) {
                // A keyed creation race is reconciled only by authoritative source-key/CID readback.
                found=checkpoint(active(end(),"SourceCheckpoint"));
                if(found.isEmpty()) throw new IOException("checkpoint creation uncertain; no replay");
                return found.get().checkpoint();
            }
            return checkpoint(active(end(),"SourceCheckpoint")).orElseThrow(()->new IOException("checkpoint create lacks source-key proof")).checkpoint();
        } catch(io.grpc.StatusRuntimeException e) {throw new IOException("catalogue checkpoint unavailable");}
    }
    private Map<Long,JavaSourceIngestion.Stored> stored(List<EventOuterClass.CreatedEvent> rows) throws IOException {
        var result=new HashMap<Long,JavaSourceIngestion.Stored>();
        for(var event:rows) if(event.getTemplateId().equals(id("SourceObservationCredential"))) {
            identity(event,"SourceObservationCredential");var r=event.getCreateArguments();
            // Canton 3.4+ normal form omits trailing Optional None fields.
            // Only this declared trailing optional may be synthesized; all other fields stay mandatory.
            if(r.getFieldsList().stream().noneMatch(f->f.getLabel().equals("previousDigest")))
                r=r.toBuilder().addFields(ValueOuterClass.RecordField.newBuilder().setLabel("previousDigest")
                        .setValue(ValueOuterClass.Value.newBuilder().setOptional(ValueOuterClass.Optional.getDefaultInstance()))).build();
            fields(r,Set.of("opener","repo","observation","fingerprint","version","previousDigest"));
            var value=IntakeLedger.field(r,"observation");
            if(!value.hasRecord()) throw new IOException("invalid source observation record");
            var observation=observation(value.getRecord());long version=IntakeLedger.number(r,"version");
            var previous=IntakeLedger.field(r,"previousDigest");
            if(version<1 || !previous.hasOptional() || (version==1)==previous.getOptional().hasValue()
                    || !SourceCatalogueDigest.digest(value.getRecord(),version,previous.getOptional().hasValue()?text(previous.getOptional().getValue()):null).equals(IntakeLedger.text(r,"fingerprint")))
                throw new IOException("source item content/version mismatch");
            if(previous.getOptional().hasValue() && !text(previous.getOptional().getValue()).matches("[0-9a-f]{64}"))
                throw new IOException("invalid previous source digest");
            if(result.put(observation.number(),new JavaSourceIngestion.Stored(observation,version,IntakeLedger.text(r,"fingerprint")))!=null)
                throw new IOException("duplicate source number key");
        }
        return result;
    }
    public Map<Long,JavaSourceIngestion.Stored> items(Set<Long> numbers) throws IOException {
        if(numbers.size()>50) throw new IOException("source lookup exceeds batch bound");
        var all=stored(active(end(),"SourceObservationCredential"));all.keySet().retainAll(numbers);return Map.copyOf(all);
    }
    private CommandServiceOuterClass.SubmitAndWaitForTransactionRequest batchRequest(JavaSourceIngestion.Batch batch,State before) throws IOException {
        String phase=before.progress?before.phase:"FullSweep"; // Full sweeps are always valid; no hidden overlap cursor policy.
        if(!batch.before().equals(before.checkpoint()) || (!batch.page().complete() && batch.page().nextToken().equals(before.token)))
            throw new IOException("stale source checkpoint; reread authoritative state");
        var values=ValueOuterClass.List.newBuilder();
        for(var o:batch.page().items()) values.addElements(observation(o));
        var args=record("items",ValueOuterClass.Value.newBuilder().setList(values).build(),"scanPhase",constructor(phase),
                "nextToken",text(batch.page().nextToken()),"complete",bool(batch.page().complete()));
        var exercise=CommandsOuterClass.Command.newBuilder().setExercise(CommandsOuterClass.ExerciseCommand.newBuilder()
                .setTemplateId(id("SourceCheckpoint")).setContractId(before.cid).setChoice("CommitBatch").setChoiceArgument(args)).build();
        return request(commands(batch.commandId(),exercise));
    }
    public synchronized void commit(JavaSourceIngestion.Batch batch) throws IOException {
        marker(batch.commandId(),".intent");
        long offset;
        CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request;
        try {
            offset=end();var before=checkpoint(active(offset,"SourceCheckpoint")).orElseThrow(()->new IOException("source checkpoint absent"));
            request=batchRequest(batch,before);
            if(request.getSerializedSize()>999_992) throw new IOException("source intent exceeds limit; split batch");
        } catch(IOException failure) {throw new JavaSourceIngestion.PreTransmission("source preflight failed before durable intent or RPC");}
        try {intent(batch.commandId(),request,offset);}
        catch(IOException | SecurityException failure) {
            if(Files.notExists(marker(batch.commandId(),".intent"),LinkOption.NOFOLLOW_LINKS))
                throw new JavaSourceIngestion.PreTransmission("source intent persistence failed without marker; no RPC");
            throw new IOException("source intent exists or persistence state uncertain; no RPC or replay");
        } // Existing/partial marker never authorizes a second batch submit.
        try {
            rpc.submit(request); // Response alone is never terminal proof.
        } catch(io.grpc.StatusRuntimeException e) {
            String code=e.getStatus().getCode().name();
            if(IntakeLedger.definite(code)) {
                byte[] bytes=IntakeLedger.readMarker(marker(batch.commandId(),".intent"));
                terminal(batch.commandId(),bytes,"REJECTED",code+"|-");
                throw new IOException("source command definitely rejected; cursor unchanged; explicit operator reconciliation required");
            }
            throw new IOException("source submission unconfirmed; reconcile only");
        }
    }
    record Receipt(boolean rejected,long offset,String updateId) { }
    private static long beginOffset(byte[] bytes) throws IOException {
        if(bytes.length<9) throw new IOException("invalid source intent");
        long begin=java.nio.ByteBuffer.wrap(bytes).getLong();
        if(begin<0) throw new IOException("invalid source intent offset");return begin;
    }
    private static Receipt receipt(String value,String binding,long begin) throws IOException {
        var parts=value.split("[|]",-1);
        if(parts.length!=4 || !parts[1].equals(binding)) throw new IOException("invalid source terminal receipt binding");
        if(parts[0].equals("REJECTED")) {
            if(!IntakeLedger.definite(parts[2]) || !parts[3].equals("-")) throw new IOException("invalid source rejection receipt");
            return new Receipt(true,0,"");
        }
        if(!parts[0].equals("CONFIRMED") || !parts[2].matches("[0-9]{1,19}")
                || !parts[3].matches("[A-Za-z0-9_:-]{1,255}")) throw new IOException("invalid source confirmation receipt");
        try {long offset=Long.parseLong(parts[2]);if(offset<=begin) throw new IOException("invalid source confirmation offset");
            return new Receipt(false,offset,parts[3]);}
        catch(NumberFormatException e) {throw new IOException("invalid source confirmation offset");}
    }
    private void terminal(String command,byte[] intent,String outcome,String detail) throws IOException {
        String binding=SourceObservation.hash(Base64.getEncoder().encodeToString(intent));
        byte[] payload=(outcome+"|"+binding+"|"+detail).getBytes(StandardCharsets.US_ASCII);
        Path path=marker(command,".receipt");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            if(!Arrays.equals(payload,IntakeLedger.readMarker(path))) throw new IOException("conflicting source terminal receipt");
        } else IntakeLedger.recordSourceMarker(journal,command+".receipt",payload);
    }
    public synchronized Optional<JavaSourceIngestion.Proof> proof(JavaSourceIngestion.Batch expected) throws IOException {
        Path path=marker(expected.commandId(),".intent");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        byte[] bytes=IntakeLedger.readMarker(path);
        if(bytes.length<9) throw new IOException("invalid source intent");
        var buffer=java.nio.ByteBuffer.wrap(bytes);long begin=buffer.getLong();
        // Local intent binds the lookup range and the pre-checkpoint, never establishes execution.
        var request=CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.parseFrom(Arrays.copyOfRange(bytes,8,bytes.length));
        if(!request.getCommands().getCommandId().equals(expected.commandId())) return Optional.empty();
        long end=end();
        String terminalUpdateId=null;
        Path receipt=marker(expected.commandId(),".receipt");
        if(Files.exists(receipt,LinkOption.NOFOLLOW_LINKS)) {
            String outcome=new String(IntakeLedger.readMarker(receipt),StandardCharsets.US_ASCII);
            String binding=SourceObservation.hash(Base64.getEncoder().encodeToString(bytes));
            var terminal=receipt(outcome,binding,begin);
            if(terminal.rejected) return Optional.empty();
            end=terminal.offset;terminalUpdateId=terminal.updateId;
        }
        if(end<=begin) return Optional.empty();
        try {
            var updates=rpc.updates(UpdateServiceOuterClass.GetUpdatesRequest.newBuilder().setBeginExclusive(begin).setEndInclusive(end)
                    .setUpdateFormat(TransactionFilterOuterClass.UpdateFormat.newBuilder().setIncludeTransactions(transactions())).build());
            int count=0,size=0;Optional<JavaSourceIngestion.Proof> found=Optional.empty();
            while(updates.hasNext()) {
                var response=updates.next();size+=response.getSerializedSize();
                if(++count>MAX_EVENTS || size>MAX_BYTES) throw new IOException("source reconciliation window exceeds bound");
                if(response.hasTransaction() && response.getTransaction().getCommandId().equals(expected.commandId())) {
                    if(found.isPresent()) throw new IOException("ambiguous source command transactions");
                    if(terminalUpdateId!=null && (!terminalUpdateId.equals(response.getTransaction().getUpdateId())
                            || response.getTransaction().getOffset()!=end)) return Optional.empty();
                    found=attest(response.getTransaction(),request,expected,begin);
                    if(found.isPresent()) terminal(expected.commandId(),bytes,"CONFIRMED",response.getTransaction().getOffset()+"|"+response.getTransaction().getUpdateId());
                }
            }
            return found;
        } catch(io.grpc.StatusRuntimeException e) {throw new IOException("source transaction evidence unavailable");}
    }
    // Verbose readback enriches type IDs absent from submitted values. Compare
    // the closed, decoded argument schema, retaining all observation evidence.
    private ValueOuterClass.Value canonicalArgument(ValueOuterClass.Value value) throws IOException {
        if(!value.hasRecord()) throw new IOException("invalid source choice argument");
        var args=fields(value.getRecord(),Set.of("items","scanPhase","nextToken","complete"));
        if(!args.get("items").hasList() || !args.get("complete").hasBool()) throw new IOException("invalid source choice argument");
        var items=ValueOuterClass.List.newBuilder();
        for(var item:args.get("items").getList().getElementsList()) {
            if(!item.hasRecord()) throw new IOException("invalid source choice observation");
            items.addElements(observation(observation(item.getRecord())));
        }
        return record("items",ValueOuterClass.Value.newBuilder().setList(items).build(),
                "scanPhase",constructor(constructor(args.get("scanPhase"))),
                "nextToken",text(text(args.get("nextToken"))),"complete",bool(args.get("complete").getBool()));
    }
    private boolean sameArgument(ValueOuterClass.Value submitted,ValueOuterClass.Value committed) {
        try {return canonicalArgument(submitted).equals(canonicalArgument(committed));}
        catch(IOException invalid) {return false;}
    }
    private Optional<JavaSourceIngestion.Proof> attest(TransactionOuterClass.Transaction tx,
            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest intent,JavaSourceIngestion.Batch expected,long proofBegin) throws IOException {
        if(tx.getUpdateId().isBlank() || !tx.getCommandId().equals(expected.commandId()) || tx.getEventsCount()>256
                || tx.getSerializedSize()>MAX_BYTES) return Optional.empty();
        var creates=new ArrayList<EventOuterClass.CreatedEvent>();EventOuterClass.ExercisedEvent commit=null;
        for(var event:tx.getEventsList()) {
            if(event.hasCreated()) creates.add(event.getCreated());
            if(event.hasExercised() && event.getExercised().getTemplateId().equals(id("SourceCheckpoint"))
                    && event.getExercised().getChoice().equals("CommitBatch")) {
                if(commit!=null) return Optional.empty();commit=event.getExercised();
            }
        }
        if(commit==null || !commit.getConsuming() || !commit.getActingPartiesList().equals(List.of(party))
                || !commit.getContractId().equals(expected.before().cid()) || !commit.hasChoiceArgument()) return Optional.empty();
        var submitted=intent.getCommands();
        if(submitted.getCommandsCount()!=1 || !submitted.getActAsList().equals(List.of(party))
                || !submitted.getUserId().equals(user) || !submitted.getCommands(0).hasExercise()) return Optional.empty();
        var exercise=submitted.getCommands(0).getExercise();
        if(!exercise.getTemplateId().equals(id("SourceCheckpoint")) || !exercise.getContractId().equals(commit.getContractId())
                || !exercise.getChoice().equals("CommitBatch") || !sameArgument(exercise.getChoiceArgument(),commit.getChoiceArgument())) return Optional.empty();
        if(!commit.getChoiceArgument().hasRecord()) return Optional.empty();
        var args=fields(commit.getChoiceArgument().getRecord(),Set.of("items","scanPhase","nextToken","complete"));
        if(!args.get("items").hasList() || !args.get("complete").hasBool()) return Optional.empty();
        var observations=new ArrayList<SourceObservation>();
        for(var value:args.get("items").getList().getElementsList()) {
            if(!value.hasRecord()) return Optional.empty();observations.add(observation(value.getRecord()));
        }
        if(observations.size()>50) return Optional.empty();
        var after=checkpoint(creates); if(after.isEmpty()) return Optional.empty();var state=after.get();
        String token=text(args.get("nextToken")),phase=constructor(args.get("scanPhase"));boolean complete=args.get("complete").getBool();
        long generation=expected.before().inProgress()?expected.before().generation():expected.before().generation()+1;
        if(state.cid.equals(commit.getContractId()) || state.generation!=generation || !state.token.equals(token)
                || state.progress==complete || !state.phase.equals(phase) || (complete && !token.isEmpty())
                || (!complete && (token.isEmpty() || token.equals(expected.before().token())))
                || (complete && phase.equals("FullSweep") && (state.full!=generation || state.overlaps!=0))) return Optional.empty();
        // Verify created item effects from this transaction, never current ACS (items may since be overwritten).
        var effects=stored(creates);
        var preItemsRows=active(proofBegin,"SourceObservationCredential");
        var preItems=stored(preItemsRows);
        int required=0;
        for(var observation:observations) {
            var old=preItems.get(observation.number());
            var effect=effects.get(observation.number());
            if(old!=null && old.observation().kind()!=observation.kind()) return Optional.empty();
            if(old!=null && old.observation().fingerprint().equals(observation.fingerprint())) {
                if(effect!=null) return Optional.empty();
                continue;
            }
            required++;
            if(effect==null || !effect.observation().equals(observation)
                    || effect.version()!=(old==null?1:old.version()+1)
                    || !effect.fingerprint().equals(SourceCatalogueDigest.digest(observation(observation).getRecord(),
                        old==null?1:old.version()+1,old==null?null:old.fingerprint()))) return Optional.empty();
            if(old!=null) {
                var created=creates.stream().filter(e->e.getTemplateId().equals(id("SourceObservationCredential")))
                        .filter(e->{try{return observation(IntakeLedger.field(e.getCreateArguments(),"observation").getRecord()).number()==observation.number();}
                            catch(IOException failure){return false;}}).findFirst().orElseThrow();
                var previous=IntakeLedger.field(created.getCreateArguments(),"previousDigest");
                String digest=old.fingerprint();
                if(!previous.hasOptional() || !previous.getOptional().hasValue()
                        || !text(previous.getOptional().getValue()).equals(digest)) return Optional.empty();
            }
        }
        if(effects.size()!=required) return Optional.empty();
        var preRows=active(proofBegin,"SourceCheckpoint");
        var preOptional=checkpoint(preRows);
        if(preOptional.isEmpty()) return Optional.empty();
        var pre=preOptional.get();
        if(!pre.checkpoint().equals(expected.before())) return Optional.empty();
        long high=pre.high;
        for(var observation:observations) high=Math.max(high,observation.number());
        String scanPreimage="("+SourceCatalogueDigest.text(pre.progress?pre.fingerprint:"")+",["
                +String.join(",",observations.stream().map(o->{try{return SourceCatalogueDigest.text(
                        SourceCatalogueDigest.digest(observation(o).getRecord()));}catch(IOException e){throw new IllegalArgumentException();}}).toList())+"])";
        if(state.high!=high || !state.fingerprint.equals(SourceObservation.hash(scanPreimage))
                || state.full!=(complete && phase.equals("FullSweep")?generation:pre.full)
                || state.overlaps!=(complete?(phase.equals("FullSweep")?0:pre.overlaps+1):pre.overlaps)
                || (pre.progress && !phase.equals(pre.phase))) return Optional.empty();
        var reconstructed=new JavaSourceIngestion.Batch(pre.checkpoint(),
                new JavaSourceIngestion.Page(observations,token,complete),tx.getCommandId());
        // Consumed generation/token were decoded from authoritative ACS at the journaled begin offset.
        return reconstructed.equals(expected)?Optional.of(new JavaSourceIngestion.Proof(reconstructed)):Optional.empty();
    }
    public void close() {if(channel!=null) channel.shutdownNow();}
}
