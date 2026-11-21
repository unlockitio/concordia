// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** One-request-at-a-time opener. No votes, resolution, discovery expansion or LLM calls. */
public final class Intake {
    public record Request(String cid, String opener, String runId, String roundId,
                          String sourceKey, long windowSeconds, long baseVersion) {
        public Request {
            if (cid == null || cid.isBlank() || opener == null || opener.isBlank()
                    || runId == null || runId.isBlank() || ! ((runId + ":" + sourceKey).equals(roundId)
                        || (runId + ("proposal-184".equals(sourceKey) ? ":grant" : ":illustrative-milestone")).equals(roundId))
                    || IntakeSources.sources().stream().noneMatch(s -> s.sourceKey().equals(sourceKey))
                    || windowSeconds < 1 || windowSeconds > 86400 || baseVersion < 0)
                throw new IllegalArgumentException("BLOCKED: invalid intake request");
        }
    }
    public interface Transport {
        List<Request> requests() throws IOException;
        boolean proof(Request request, IntakePlan plan) throws IOException;
        void submit(Request request, IntakePlan plan) throws IOException;
    }
    static final class PreTransmission extends IOException {
        PreTransmission(String message, Throwable cause) { super(message, cause); }
    }
    static final class Rejected extends IOException {
        Rejected(String message, Throwable cause) { super(message,cause); }
    }
    @FunctionalInterface public interface Bytes { byte[] fetch(IntakeSource source) throws IOException; }
    private final Transport ledger;
    private final Bytes bytes;
    private boolean uncertain;
    public Intake(Transport ledger, Bytes bytes) { this.ledger = ledger; this.bytes = bytes; }

    public String step() throws IOException {
        var requests = ledger.requests();
        if (requests.isEmpty()) return "idle";
        if (requests.size() != 1) throw new IOException("BLOCKED: exactly one intake request required");
        Request r = requests.getFirst();
        var source = IntakeSources.sources().stream().filter(s -> s.sourceKey().equals(r.sourceKey())).findFirst().orElseThrow();
        var plan = source.agendaConstructor() == IntakeSource.AgendaConstructor.Grant
                ? new IntakePlan(source, r.roundId(), bytes.fetch(source)) : new IntakePlan(source, r.roundId());
        if (ledger.proof(r, plan)) { uncertain=false; return "reconciled " + plan.commandId(); }
        if (uncertain) throw new IOException("BLOCKED: uncertain submission without ledger proof; no resubmission");
        // Ledger bridge computes closesAt from Daml getTime in the transaction.
        // Latch before transmission: even a lost response must never trigger a blind retry.
        uncertain = true;
        try { ledger.submit(r, plan); }
        catch (PreTransmission failure) {
            uncertain = false;
            throw failure;
        }
        catch (Rejected failure) {
            uncertain = false;
            throw failure;
        }
        catch (IOException failure) {
            if (ledger.proof(r, plan)) { uncertain=false; return "reconciled " + plan.commandId(); }
            throw new IOException("BLOCKED: uncertain intake submission; reconciliation required", failure);
        }
        if (!ledger.proof(r, plan)) throw new IOException("BLOCKED: submission returned without matching ledger proof");
        uncertain = false;
        return "opened " + plan.commandId() + " evidence=" + plan.evidence();
    }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    static void poll(Intake intake,int attempts,long intervalMillis,Pause pause,java.util.function.Consumer<String> log)
            throws IOException,InterruptedException {
        if(attempts<1 || attempts>86400 || intervalMillis<1 || intervalMillis>60000)
            throw new IllegalArgumentException("BLOCKED: bounded polling required");
        String previous=null;
        for(int i=0;i<attempts;i++) {
            String result=intake.step();
            if(!result.equals(previous)) { log.accept(result); previous=result; }
            if(i+1<attempts) pause.sleep(intervalMillis);
        }
    }
    static GitHubSourcePages liveSourcePages() throws IOException {
        return liveSourcePages(IntakeGitHub.configured(System.getenv()).transport());
    }
    static GitHubSourcePages liveSourcePages(GitHubDiscovery.Transport transport) {
        return new GitHubSourcePages(transport,java.time.Clock.systemUTC());
    }
    static final class SourceStatus {
        String error;
        void completed(SourceSweepScheduler.TickOutcome outcome) {
            if(outcome==SourceSweepScheduler.TickOutcome.SUCCEEDED)error=null;
        }
        void failed(Throwable failure,java.util.function.Consumer<String> log) {
            String message="Source discovery paused: "+failure.getMessage();
            if(!message.equals(error))log.accept(message);
            error=message;
        }
    }
    static void pollWithSources(Intake intake,SourceSweepScheduler scheduler,int attempts,long intervalMillis,
                                Pause pause,java.util.function.Consumer<String> log)throws IOException,InterruptedException {
        if(attempts<1 || attempts>86400 || intervalMillis<1 || intervalMillis>60000)
            throw new IllegalArgumentException("bounded polling required");
        String previous=null;
        SourceStatus sourceStatus=new SourceStatus();
        // Separate source worker prevents bounded ledger proof reads from starving agenda polling.
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<SourceSweepScheduler.TickOutcome> running=null;
            for(int i=0;i<attempts;i++) {
                String result=intake.step();
                if(!result.equals(previous)){log.accept(result);previous=result;}
                if(running==null || running.isDone()) {
                    if(running!=null)try {
                        sourceStatus.completed(running.get());
                    }
                    catch(java.util.concurrent.ExecutionException failure) {
                        sourceStatus.failed(failure.getCause(),log);
                    }
                    running=executor.submit(scheduler::tick);
                }
                if(i+1<attempts)pause.sleep(intervalMillis);
            }
            if(running!=null)try{running.get();}catch(java.util.concurrent.ExecutionException e){throw new IOException("source worker failed",e.getCause());}
        }
    }
    static String discoveryMode(java.util.Map<String,String> environment) {
        String mode=environment.getOrDefault("INTAKE_DISCOVERY_MODE","live");
        if(!java.util.Set.of("live","fixture").contains(mode))
            throw new IllegalArgumentException("INTAKE_DISCOVERY_MODE must be live or explicit fixture");
        return mode;
    }
    static long sourceInterval(java.util.Map<String,String> environment) {
        long interval=Long.parseLong(environment.getOrDefault("INTAKE_SOURCE_INTERVAL_MILLIS","30000"));
        if(interval<30_000 || interval>3_600_000)
            throw new IllegalArgumentException("source interval must be 30000..3600000 milliseconds");
        return interval;
    }
    static Path sourceJournal(Path agendaJournal) throws IOException {
        Path parent=IntakeLedger.validateJournal(agendaJournal);
        Path child=parent.resolve("sources");
        try {
            Files.createDirectory(child,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        } catch(java.nio.file.FileAlreadyExistsException existing) {
            // Existing journals are validated, never repaired or recursively changed.
        }
        IntakeLedger.validateJournal(child);
        if(!Files.getOwner(child,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(parent,java.nio.file.LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("source journal owner differs from agenda journal");
        if(!Files.getPosixFilePermissions(child,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            throw new IOException("source journal must have owner-only 0700 permissions");
        return child;
    }
    @FunctionalInterface interface SourceLedgerFactory {
        SourceCatalogueLedger open(int port,String opener,String demoPackage,String user,Path journal);
    }
    static SourceCatalogueLedger openSources(String mode,int port,String opener,String demoPackage,
                                             String user,Path journal,SourceLedgerFactory factory) throws IOException {
        if("fixture".equals(mode)) return null; // Explicit offline mode never constructs a source worker/adapter.
        if(!"live".equals(mode)) throw new IllegalArgumentException("invalid source mode");
        return factory.open(port,opener,demoPackage,user,sourceJournal(journal));
    }
    public static void main(String[] args) throws Exception {
        boolean polling=args.length==12 && args[9].equals("--poll");
        if ((!polling && args.length != 9 && !(args.length == 10 && args[9].equals("--retry-rejected"))) || !args[0].equals("--offline-fixture"))
            throw new IllegalArgumentException("usage: --offline-fixture grant-file port opener impl-package demo-package governor-cid user-id existing-journal-directory [--retry-rejected | --poll ATTEMPTS INTERVAL_MILLIS] (polling never enables rejected retry)");
        // This flag pins curated grant bytes only; discovery mode is independent.
        String discoveryMode=discoveryMode(System.getenv());
        long sourceInterval=sourceInterval(System.getenv());
        // Validate live credentials before reading grants or opening any ledger/journal adapter.
        GitHubSourcePages pages=discoveryMode.equals("live")?liveSourcePages():null;
        byte[] grant = Files.readAllBytes(Path.of(args[1]));
        try (var ledger = new IntakeLedger(Integer.parseInt(args[2]), args[3], args[4], args[5], args[6], args[7], Path.of(args[8]))) {
            if(args.length==10) ledger.allowOneRejectedRetry();
            var intake=new Intake(ledger, source -> grant);
            if(discoveryMode.equals("fixture")) {
                System.out.println("Source discovery: explicit offline fixture inventory; not live SourceItems");
                IntakeInventory.startup("fixture",Path.of(args[8]));
                if(polling) poll(intake,Integer.parseInt(args[10]),Long.parseLong(args[11]),Thread::sleep,System.out::println);
                else System.out.println(intake.step());
            } else {
                try(var sources=openSources(discoveryMode,Integer.parseInt(args[2]),args[3],args[5],args[7],
                        Path.of(args[8]),SourceCatalogueLedger::new)) {
                    var scheduler=new SourceSweepScheduler(new JavaSourceIngestion(sources,pages,System.out::println),System::nanoTime,sourceInterval);
                    pollWithSources(intake,scheduler,polling?Integer.parseInt(args[10]):1,polling?Long.parseLong(args[11]):1000,Thread::sleep,System.out::println);
                }
            }
        }
    }
}
