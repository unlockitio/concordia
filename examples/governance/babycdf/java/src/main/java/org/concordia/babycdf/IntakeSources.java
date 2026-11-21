// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.util.List;

/** Closed demo catalogue. Future discovery implementations do not expand planning authority. */
public final class IntakeSources {
    private IntakeSources() { }
    public static final String GRANT_BLOB = "f591787a772848e4537aba5d8ba02be1764647da";
    // RuleVote.daml:135-141. Verification happens at planning, not catalogue construction.
    public static final IntakeSource GRANT = new IntakeSource("proposal-184",
            IntakeSource.AgendaConstructor.Grant, "proposal",
            "https://github.com/canton-foundation/canton-dev-fund/blob/c6964dc9545f4046d6f4e4c1cb81cfd01fd6b521/proposals/concordia.md",
            GRANT_BLOB, IntakeSource.Evidence.CURATED_UNVERIFIED);
    // RuleVote.daml:264-267; explicitly illustrative, never verified issue evidence.
    public static final IntakeSource MILESTONE = new IntakeSource("milestone-540",
            IntakeSource.AgendaConstructor.Milestone, "milestone",
            "https://github.com/canton-foundation/canton-dev-fund/issues/540",
            "illustrative-immutable-milestone-revision-v1", IntakeSource.Evidence.CURATED_UNVERIFIED);

    /** Network-free extension seam for a future discovery adapter. */
    @FunctionalInterface
    public interface Discovery { List<IntakeSource> sources(); }

    public static List<IntakeSource> sources() { return List.of(GRANT, MILESTONE); }
    public static Discovery curated() { return IntakeSources::sources; }

    static void requireKnown(IntakeSource source) {
        if (source == null || !sources().contains(source))
            throw new IllegalArgumentException("BLOCKED: source is not in the static intake catalogue");
    }
}
