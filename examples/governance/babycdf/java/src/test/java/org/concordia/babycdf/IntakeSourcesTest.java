// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntakeSourcesTest {
    /** Narrow extraction of one top-level declaration, so re-wrapping the Daml cannot break the pin check. */
    private String declaration(String daml, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + " = GithubSubject\\b(.*?)(?=\\n\\S|\\n\\n|\\z)",
                Pattern.DOTALL).matcher(daml);
        assertTrue(matcher.find(), "declaration not found: " + name);
        return matcher.group(1).replaceAll("\\s+", " ").trim();
    }

    @Test void subjectsEqualDamlPinsExactly() throws Exception {
        String daml = Files.readString(Path.of("../impl/daml/Cap/Examples/BabyCdf/RuleVote.daml"));
        // The blob constant is compared exactly; only layout whitespace is normalised.
        assertTrue(daml.contains("proposalBlob = \"" + IntakeSources.GRANT.contentVersion() + "\""));
        assertEquals("\"" + IntakeSources.GRANT.kind() + "\" \"" + IntakeSources.GRANT.url() + "\" proposalBlob",
                declaration(daml, "proposalSubject"));
        assertEquals("\"" + IntakeSources.MILESTONE.kind() + "\" \"" + IntakeSources.MILESTONE.url()
                + "\" \"" + IntakeSources.MILESTONE.contentVersion() + "\"",
                declaration(daml, "illustrativeMilestone"));
        // The catalogue must track the subject validAgenda actually accepts.
        assertTrue(daml.contains("validAgenda (Grant subject) = subject == proposalSubject"));
        assertTrue(daml.contains("validAgenda (Milestone subject) = subject == illustrativeMilestone"));
        assertEquals(2, IntakeSources.curated().sources().size());
        assertEquals(IntakeSource.AgendaConstructor.Grant, IntakeSources.GRANT.agendaConstructor());
        assertEquals(IntakeSource.AgendaConstructor.Milestone, IntakeSources.MILESTONE.agendaConstructor());
        assertThrows(UnsupportedOperationException.class, () -> IntakeSources.sources().add(IntakeSources.GRANT));
    }

    private void blocked(Runnable action) {
        assertTrue(assertThrows(IllegalArgumentException.class, action::run).getMessage().startsWith("BLOCKED: "));
    }

    @Test void unknownOrExtraSourceIsRefused() {
        blocked(() -> new IntakePlan(new IntakeSource("extra", IntakeSource.AgendaConstructor.Milestone,
                "milestone", IntakeSources.MILESTONE.url(), IntakeSources.MILESTONE.contentVersion(),
                IntakeSource.Evidence.CURATED_UNVERIFIED), "round"));
        blocked(() -> new IntakePlan(null, "round"));
    }

    @Test void nonPinnedGrantIsRefused() {
        blocked(() -> new IntakePlan(new IntakeSource(IntakeSources.GRANT.sourceKey(),
                IntakeSource.AgendaConstructor.Grant, "proposal", IntakeSources.GRANT.url(),
                "a".repeat(40), IntakeSource.Evidence.CURATED_UNVERIFIED), "round",
                "any bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void missingAndMismatchedDigestsFailClosed() {
        // No bytes at all can never verify a grant, even with the pinned string in the catalogue.
        blocked(() -> new IntakePlan(IntakeSources.GRANT, "round"));
        blocked(() -> new IntakePlan(IntakeSources.GRANT, "round", (byte[]) null));
        blocked(() -> new IntakePlan(IntakeSources.GRANT, "round", "changed fixture".getBytes(StandardCharsets.UTF_8)));
        // The pinned digest string itself is not evidence: as bytes it hashes to something else.
        blocked(() -> new IntakePlan(IntakeSources.GRANT, "round",
                IntakeSources.GRANT_BLOB.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * No public API may promote a caller-asserted digest string to verified status: the only
     * public signatures are (source, roundId) and (source, roundId, bytes). A second String
     * parameter would be a digest the caller asserts, so it must not be public.
     */
    @Test void noPublicConstructorAcceptsADigestString() {
        var signatures = new java.util.HashSet<java.util.List<Class<?>>>();
        for (var constructor : IntakePlan.class.getConstructors()) {
            signatures.add(java.util.List.of(constructor.getParameterTypes()));
            long strings = java.util.Arrays.stream(constructor.getParameterTypes())
                    .filter(String.class::equals).count();
            assertEquals(1, strings, "only roundId may be a String on a public constructor");
        }
        assertEquals(java.util.Set.of(
                java.util.List.of(IntakeSource.class, String.class),
                java.util.List.of(IntakeSource.class, String.class, byte[].class)), signatures);
    }

    /**
     * Known Git blob vectors, independently computed in the isolated copy with:
     *   printf '' | git hash-object --stdin
     *   printf 'hello\n' | git hash-object --stdin
     *   printf 'h\303\251llo \342\202\254\n' | git hash-object --stdin
     * The third is the non-ASCII case: UTF-8 "héllo €\n", bytes 68 c3 a9 6c 6c 6f 20 e2 82 ac 0a.
     */
    @Test void gitBlobSha1MatchesKnownVectors() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", IntakePlan.gitBlobSha1(new byte[0]));
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                IntakePlan.gitBlobSha1("hello\n".getBytes(StandardCharsets.UTF_8)));
        byte[] nonAscii = {0x68, (byte) 0xc3, (byte) 0xa9, 0x6c, 0x6c, 0x6f, 0x20,
                (byte) 0xe2, (byte) 0x82, (byte) 0xac, 0x0a};
        assertEquals("9486dcb34a8858ad46400ba38bb0a24fb628ffb0", IntakePlan.gitBlobSha1(nonAscii));
        assertArrayEquals(nonAscii, "héllo €\n".getBytes(StandardCharsets.UTF_8));
        // The header length is the byte count, not the character count.
        assertNotEquals(IntakePlan.gitBlobSha1(nonAscii),
                IntakePlan.gitBlobSha1("hello \u20ac\n".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Positive byte verification against the real pinned content is exercised through the
     * package-private seam, not the catalogue: the PR #184 blob is third-party
     * canton-dev-fund content and is not checked in here, and tests must never fetch it.
     */
    @Test void byteVerificationAcceptsMatchingBytesAndRejectsTampering() {
        byte[] bytes = "hello\n".getBytes(StandardCharsets.UTF_8);
        String digest = "ce013625030ba8dba906f756967f9e9ca394464a";
        assertTrue(IntakePlan.verifies(bytes, digest));
        assertFalse(IntakePlan.verifies("hello".getBytes(StandardCharsets.UTF_8), digest));
        assertFalse(IntakePlan.verifies(bytes, digest.toUpperCase()));
        assertFalse(IntakePlan.verifies(bytes, "a".repeat(40)));
        assertFalse(IntakePlan.verifies(null, digest));
        assertFalse(IntakePlan.verifies(bytes, null));
    }

    @Test void milestoneIsNeverDigestVerified() {
        assertEquals(IntakeSource.Evidence.CURATED_UNVERIFIED, IntakeSources.MILESTONE.evidence());
        assertEquals(IntakeSource.Evidence.CURATED_UNVERIFIED, new IntakePlan(IntakeSources.MILESTONE, "r").evidence());
        blocked(() -> new IntakeSource("m", IntakeSource.AgendaConstructor.Milestone, "milestone", "url", "v",
                IntakeSource.Evidence.DIGEST_VERIFIED));
        blocked(() -> new IntakePlan(IntakeSources.MILESTONE, "r",
                "any bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void commandIdsAreStableAndRoundSpecific() {
        IntakePlan first = new IntakePlan(IntakeSources.MILESTONE, "r");
        assertEquals(first.commandId(), new IntakePlan(IntakeSources.MILESTONE, "r").commandId());
        assertNotEquals(first.commandId(), new IntakePlan(IntakeSources.MILESTONE, "r2").commandId());
        assertTrue(first.commandId().startsWith("babycdf-intake-"));
        assertEquals("babycdf-intake-".length() + 64, first.commandId().length());
        assertEquals("r", first.roundId());
        assertEquals(IntakeSources.MILESTONE, first.source());
    }

    @Test void invalidConstructorInputsAreBlocked() {
        blocked(() -> new IntakePlan(IntakeSources.MILESTONE, " "));
        blocked(() -> new IntakeSource("", IntakeSource.AgendaConstructor.Grant, "proposal", "url", "v",
                IntakeSource.Evidence.CURATED_UNVERIFIED));
        blocked(() -> new IntakeSource("key", null, "proposal", "url", "v", IntakeSource.Evidence.CURATED_UNVERIFIED));
    }
}
