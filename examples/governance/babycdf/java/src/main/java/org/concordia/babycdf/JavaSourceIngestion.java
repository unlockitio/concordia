// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Ledger-authoritative, one atomic batch per cycle. No agenda operations. */
public final class JavaSourceIngestion {
    public record Checkpoint(String cid,long generation,String token,boolean inProgress) { }
    public record Stored(SourceObservation observation,long version,String fingerprint) {
        public Stored(SourceObservation observation,long version) { this(observation,version,observation.fingerprint()); }
    }
    public record Page(List<SourceObservation> items,String nextToken,boolean complete) {
        public Page {
            items=List.copyOf(items);
            if(items.size()>50 || items.stream().map(SourceObservation::number).distinct().count()!=items.size()
                    || nextToken==null || nextToken.length()>512 || complete!=nextToken.isEmpty())
                throw new IllegalArgumentException("invalid atomic source batch");
        }
    }
    public record Batch(Checkpoint before,Page page,String commandId) { }
    /** Adapter-attested transaction binding. Includes exact observations (receipt times included), cursor and pre-CID. */
    public record Proof(Batch committedBatch) {
        public boolean matches(Batch expected) { return expected.equals(committedBatch); }
    }
    /** Must resume the exact persisted cursor, retain uncommitted page remainder, and validate Link targets. */
    @FunctionalInterface public interface Discovery { Page next(Checkpoint checkpoint) throws IOException; }
    /** Implementations must read authoritative ledger state, never a client journal/cache. */
    public static final class PreTransmission extends IOException {
        public PreTransmission(String message) {super(message);}
    }
    public interface Ledger {
        Checkpoint checkpoint() throws IOException;
        Map<Long,Stored> items(Set<Long> numbers) throws IOException;
        void commit(Batch batch) throws IOException;
        /** Transaction proof must bind consumed CID, command ID, item effects and resulting checkpoint. */
        Optional<Proof> proof(Batch batch) throws IOException;
    }
    private final Ledger ledger;
    private final Discovery discovery;
    private final Consumer<String> log;
    private Batch uncertain;
    public JavaSourceIngestion(Ledger ledger,Discovery discovery,Consumer<String> log) {
        this.ledger=Objects.requireNonNull(ledger); this.discovery=Objects.requireNonNull(discovery); this.log=Objects.requireNonNull(log);
    }
    private boolean confirmed(Batch batch) throws IOException {
        return ledger.proof(batch).filter(proof->proof.matches(batch)).isPresent();
    }
    public synchronized void cycle() throws IOException {
        if(uncertain!=null) {
            if(!confirmed(uncertain)) throw new IOException("uncertain source commit; proof required, no blind replay");
            uncertain=null;
            return; // Do not invent mutation labels after loss of pre-state.
        }
        Checkpoint checkpoint=ledger.checkpoint();
        Page fetched=discovery.next(checkpoint);
        if(!fetched.complete() && fetched.nextToken().equals(checkpoint.token()))
            throw new IOException("source cursor did not advance");
        Set<Long> numbers=new TreeSet<>(); fetched.items().forEach(o->numbers.add(o.number()));
        Map<Long,Stored> previous=ledger.items(numbers);
        List<SourceObservation> accepted=new ArrayList<>();
        List<String> changes=new ArrayList<>();
        for(var observation:fetched.items().stream().sorted(Comparator.comparingLong(SourceObservation::number)).toList()) {
            Stored old=previous.get(observation.number());
            if(old!=null) {
                if(old.observation().kind()!=observation.kind()) throw new IOException("source kind conflict; reconcile required");
                if(observation.updatedAt().isBefore(old.observation().updatedAt())
                        || observation.observedAt().isBefore(old.observation().observedAt())) continue;
                if(old.observation().fingerprint().equals(observation.fingerprint())) continue;
            }
            accepted.add(observation);
            changes.add((observation.kind()==SourceObservation.Kind.PROPOSAL?"Proposal #":"Milestone #")
                    +observation.number()+(old==null?" added":" updated"));
        }
        Page page=new Page(accepted,fetched.nextToken(),fetched.complete());
        StringBuilder identity=new StringBuilder(checkpoint.cid()).append('|').append(checkpoint.generation())
                .append('|').append(page.nextToken()).append('|').append(page.complete());
        page.items().forEach(o->identity.append('|').append(o.fingerprint()));
        Batch batch=new Batch(checkpoint,page,"babycdf-source-v2-"+SourceObservation.hash(identity.toString()));
        // Catch a competing writer before submit; a ledger consuming choice remains the final concurrency guard.
        if(!ledger.checkpoint().equals(checkpoint)) throw new IOException("stale source checkpoint; reread next cycle");
        uncertain=batch;
        try { ledger.commit(batch); }
        catch(PreTransmission failure) {uncertain=null;throw failure;}
        catch(IOException failure) {
            if(!confirmed(batch)) throw new IOException("uncertain source commit; no replay",failure);
        }
        if(!confirmed(batch)) throw new IOException("source submit lacks authoritative proof");
        uncertain=null;
        changes.forEach(log);
    }
}
