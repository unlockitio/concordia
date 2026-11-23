// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import java.time.*;import java.util.*;import java.io.IOException;
/** Bounded continuous inventory scheduler, separate from ballots/reviewer/journey. */
public final class SourceOpenerScheduler {
 private final SourceEvidenceCollector collector;private final SourceOpenerLedger ledger;private final SourceOpenerQueue queue;private final Clock clock;
 public SourceOpenerScheduler(SourceEvidenceCollector collector,SourceOpenerLedger ledger,SourceOpenerQueue queue,Clock clock){this.collector=collector;this.ledger=ledger;this.queue=queue;this.clock=clock;}
 public String automaticCycle(java.nio.file.Path manifest)throws IOException {
  for(var c:queue.commitments().entrySet())ledger.restoreCommitment(c.getKey(),c.getValue());
  var proposals=ledger.inventory("proposal");var milestones=ledger.inventory("milestone");var pins=new HashMap<Long,SourceEvidence.Proposal>();
  var ready=new HashMap<Long,SourceEvidenceCollector.Ready>();
  for(long number:proposals){var result=collector.readyProposal(number,clock.instant());var batch=collector.lastProposalBatch(number);if(batch!=null)queue.retainEvidence(batch);if(result.isPresent()){var r=result.get();ready.put(number,r);var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.pinJson());var files=new ArrayList<SourceEvidence.FilePin>();for(var f:json.get(1))files.add(new SourceEvidence.FilePin(f.get(0).asText(),f.get(1).asText(),f.get(2).asText(),f.get(3).asText()));pins.put(number,new SourceEvidence.Proposal(number,json.get(0).asText(),files,true,"ready"));}}
  List<VerifiedAmendment> amendments=List.of();
  if(manifest!=null){if(java.nio.file.Files.isSymbolicLink(manifest)||!java.nio.file.Files.isRegularFile(manifest)||java.nio.file.Files.size(manifest)>131072)throw new IOException("invalid amendment manifest file");byte[] bytes=java.nio.file.Files.readAllBytes(manifest);
   // Source IDs must be collector-observed comment IDs; arbitrary manifest IDs cannot self-authorize.
   var sourceIds=new HashSet<Long>();for(long number:proposals)sourceIds.addAll(collector.observedSourceIds(number));amendments=VerifiedAmendment.parse(bytes,pins,sourceIds);}
  var amended=amendments.stream().map(VerifiedAmendment::amending).collect(java.util.stream.Collectors.toSet());
  for(var amendment:amendments){var capability=collector.readyAmendment(amendment,clock.instant());var batch=collector.lastProposalBatch(amendment.amending());if(batch!=null)queue.retainEvidence(batch);if(capability.isPresent()){var binding=ledger.bindAmendment(capability.get(),amendment);queue.commitAmendment(binding.roundId(),ledger.commitment(binding.roundId()));queue.enqueue(capability.get(),binding);}}
  for(var r:ready.values())if(!amended.contains(r.number()))queue.enqueue(r,ledger.bind(r));
  for(long number:milestones){var collection=collector.collectMilestones(number,clock.instant());queue.retainEvidence(collection.batch());ledger.retainMilestoneEvidence(collection.batch());for(var capability:collection.ready())queue.enqueue(capability,ledger.bind(capability));}
  return queue.tick();
 }
 public String cycleAmendments(List<VerifiedAmendment> inventory)throws IOException {
  if(inventory.size()>50)throw new IOException("amendment inventory bound");
  for(var amendment:inventory){var ready=collector.readyAmendment(amendment,clock.instant());if(ready.isPresent()){var binding=ledger.bindAmendment(ready.get(),amendment);queue.commitAmendment(binding.roundId(),ledger.commitment(binding.roundId()));queue.enqueue(ready.get(),binding);}}
  return queue.tick();
 }
 public String cycleMilestones(List<Long> inventory)throws IOException {
  if(inventory.size()>50)throw new IOException("milestone inventory bound");
  for(long number:inventory){var collection=collector.collectMilestones(number,clock.instant());
   // S2 runtime cannot establish historical trigger-body and explicit commit proof
   // automatically; retain diagnostic evidence without manufacturing Ready.
   queue.retainEvidence(collection.batch());ledger.retainMilestoneEvidence(collection.batch());
   for(var ready:collection.ready())queue.enqueue(ready,ledger.bind(ready));
  }
  return queue.tick();
 }
 public String cycleInventory(List<Long> proposals,List<Long> milestones,List<VerifiedAmendment> amendments)throws IOException {
  if(proposals.size()+milestones.size()+amendments.size()>50)throw new IOException("total inventory bound");
  var amended=amendments.stream().map(VerifiedAmendment::amending).collect(java.util.stream.Collectors.toSet());
  for(var amendment:amendments){var ready=collector.readyAmendment(amendment,clock.instant());if(ready.isPresent()){var binding=ledger.bindAmendment(ready.get(),amendment);queue.commitAmendment(binding.roundId(),ledger.commitment(binding.roundId()));queue.enqueue(ready.get(),binding);}}
  for(long number:proposals.stream().sorted().toList())if(!amended.contains(number)){var ready=collector.readyProposal(number,clock.instant());if(ready.isPresent())queue.enqueue(ready.get(),ledger.bind(ready.get()));}
  for(long number:milestones.stream().sorted().toList()){var collection=collector.collectMilestones(number,clock.instant());queue.retainEvidence(collection.batch());ledger.retainMilestoneEvidence(collection.batch());for(var ready:collection.ready())queue.enqueue(ready,ledger.bind(ready));}
  return queue.tick();
 }
 public String cycle(List<Long> inventory)throws IOException {
  if(inventory.size()>50||inventory.stream().distinct().count()!=inventory.size())throw new IOException("bounded unique source inventory required");
  for(long number:inventory.stream().sorted().toList()) {
   var ready=collector.readyProposal(number,clock.instant());if(ready.isPresent())queue.enqueue(ready.get(),ledger.bind(ready.get()));
  }
  return queue.tick();
 }
 @FunctionalInterface public interface Sleeper {void sleep(long millis)throws InterruptedException;}
 public void poll(List<Long> inventory,int attempts,long intervalMillis,Sleeper sleeper,java.util.function.Consumer<String> log)throws Exception {
  if(attempts<1||attempts>10000||intervalMillis<30000||intervalMillis>3600000)throw new IllegalArgumentException("bounded source polling required");
  for(int i=0;i<attempts;i++){log.accept(cycle(inventory));if(i+1<attempts)sleeper.sleep(intervalMillis);}
 }
}
