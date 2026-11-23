// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Bounded demo scope derived from immutable changed paths and known root pin. */
public final class ConcordiaDemoScope {
 public static final String REPO="canton-foundation/canton-dev-fund";
 public static final String ROOT_HEAD="c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521";
 public static final String ROOT_BLOB="f591787a772848e4537aba5d8ba02be1764647da";
 private ConcordiaDemoScope(){}
 public static boolean proposal(long number,String repo,JsonNode pin) {
  if(!REPO.equals(repo)||!pin.isArray()||pin.size()!=2||!pin.get(0).asText().matches("[0-9a-f]{40}")||!pin.get(1).isArray()||pin.get(1).isEmpty())return false;
  if(number==184)return pin.get(0).asText().equals(ROOT_HEAD)&&java.util.stream.StreamSupport.stream(pin.get(1).spliterator(),false).anyMatch(f->f.isArray()&&f.size()==4&&f.get(0).asText().equals("proposals/concordia.md")&&f.get(2).asText().equals(ROOT_BLOB));
  // Independent PR identity, never amendment lineage. Immutable file membership
  // must name Concordia, not a mutable title or a concatenated PR allowlist.
  return java.util.stream.StreamSupport.stream(pin.get(1).spliterator(),false).anyMatch(f->f.isArray()&&f.size()==4&&f.get(0).asText().toLowerCase(Locale.ROOT).matches("(?:proposals|rfps)/(?:[^/]+/)*concordia(?:[-_.][a-z0-9_.-]+)?\\.md")&&!f.get(1).asText().equals("removed")&&f.get(2).asText().matches("[0-9a-f]{40}"));
 }
 public static boolean milestone(String repo,long originalProposal){return REPO.equals(repo)&&originalProposal==184;}
}
