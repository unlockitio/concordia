// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pure command planning, with no party, ledger, scheduling or network authority. */
public final class IntakePlan {
    private final IntakeSource source;
    private final String roundId;
    private final String commandId;
    private final IntakeSource.Evidence evidence;
    private com.daml.ledger.api.v2.ValueOuterClass.Value boundAgenda;
    private com.daml.ledger.api.v2.ValueOuterClass.Value boundSource;
    static IntakePlan bound(IntakeSource source,String roundId,com.daml.ledger.api.v2.ValueOuterClass.Value agenda,
                            com.daml.ledger.api.v2.ValueOuterClass.Value credential) {
        var plan=new IntakePlan(source,roundId,true);
        plan.boundAgenda=agenda;plan.boundSource=credential;return plan;
    }
    private IntakePlan(IntakeSource source,String roundId,boolean bound) {
        this.source=source;this.roundId=roundId;this.evidence=IntakeSource.Evidence.CURATED_UNVERIFIED;
        this.commandId="babycdf-intake-"+digest("SHA-256",("source-agenda/v1|"+source.sourceKey()+"|"+roundId).getBytes(StandardCharsets.UTF_8));
    }
    com.daml.ledger.api.v2.ValueOuterClass.Value boundAgenda() {return boundAgenda;}
    com.daml.ledger.api.v2.ValueOuterClass.Value boundSource() {return boundSource;}

    /**
     * Unverified planning. A grant is refused here: only bytes can verify a grant, so this
     * constructor cannot be used to reach {@code DIGEST_VERIFIED}.
     */
    public IntakePlan(IntakeSource source, String roundId) {
        this(source, roundId, null, false);
    }

    /**
     * The only verified-planning path. The digest is computed here from the supplied bytes, so a
     * caller cannot assert verification: possession of the pinned string is not possession of the
     * pinned content.
     */
    public IntakePlan(IntakeSource source, String roundId, byte[] grantBytes) {
        this(source, roundId, grantBytes == null ? null : gitBlobSha1(grantBytes), true);
    }

    /** Internal: {@code hashedHere} records that the digest came from bytes, never from a caller. */
    private IntakePlan(IntakeSource source, String roundId, String grantBlobDigest, boolean hashedHere) {
        IntakeSources.requireKnown(source);
        if (roundId == null || roundId.isBlank())
            throw new IllegalArgumentException("BLOCKED: round ID required");
        boolean grant = source.agendaConstructor() == IntakeSource.AgendaConstructor.Grant;
        if (grant && !(hashedHere && IntakeSources.GRANT_BLOB.equals(source.contentVersion())
                && IntakeSources.GRANT_BLOB.equals(grantBlobDigest)))
            throw new IllegalArgumentException("BLOCKED: pinned grant bytes required; an asserted digest never verifies");
        if (!grant && grantBlobDigest != null)
            throw new IllegalArgumentException("BLOCKED: milestone has no verified digest");
        this.source = source;
        this.roundId = roundId;
        this.evidence = grant ? IntakeSource.Evidence.DIGEST_VERIFIED
                : IntakeSource.Evidence.CURATED_UNVERIFIED;
        // Length-prefix both components to avoid delimiter ambiguity and keep IDs bounded.
        String key = source.sourceKey().length() + ":" + source.sourceKey()
                + roundId.length() + ":" + roundId;
        this.commandId = "babycdf-intake-" + digest("SHA-256", key.getBytes(StandardCharsets.UTF_8));
    }

    public IntakeSource source() { return source; }
    public String roundId() { return roundId; }
    public String commandId() { return commandId; }
    public IntakeSource.Evidence evidence() { return evidence; }

    /**
     * Byte-level verification seam, package-private so no caller outside this package can treat a
     * digest string as evidence. Fails closed on any length, case or content difference.
     */
    static boolean verifies(byte[] bytes, String expectedGitBlobSha1) {
        if (bytes == null || expectedGitBlobSha1 == null) return false;
        return MessageDigest.isEqual(gitBlobSha1(bytes).getBytes(StandardCharsets.US_ASCII),
                expectedGitBlobSha1.getBytes(StandardCharsets.US_ASCII));
    }

    // Same byte construction as Advisory:180-184, kept private to this pure core rather than
    // shared, because extracting it would mean editing Advisory.java, which already carries large
    // approved uncommitted changes in this worktree that must not be disturbed.
    static String gitBlobSha1(byte[] raw) {
        byte[] header = ("blob " + raw.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] blob = new byte[header.length + raw.length];
        System.arraycopy(header, 0, blob, 0, header.length);
        System.arraycopy(raw, 0, blob, header.length, raw.length);
        return digest("SHA-1", blob);
    }

    private static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("BLOCKED: digest algorithm unavailable", exception);
        }
    }
}
