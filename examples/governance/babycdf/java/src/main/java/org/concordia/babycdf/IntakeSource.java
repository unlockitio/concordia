// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

/** Immutable subject data; curated evidence is not a claim of digest verification. */
public record IntakeSource(String sourceKey, AgendaConstructor agendaConstructor, String kind,
                           String url, String contentVersion, Evidence evidence) {
    public enum AgendaConstructor { Grant, Milestone }
    public enum Evidence { DIGEST_VERIFIED, CURATED_UNVERIFIED }

    public IntakeSource {
        if (sourceKey == null || sourceKey.isBlank() || agendaConstructor == null
                || kind == null || kind.isBlank() || url == null || url.isBlank()
                || contentVersion == null || contentVersion.isBlank() || evidence == null)
            throw new IllegalArgumentException("BLOCKED: complete intake source required");
        if (agendaConstructor == AgendaConstructor.Milestone && evidence == Evidence.DIGEST_VERIFIED)
            throw new IllegalArgumentException("BLOCKED: illustrative milestone is not digest verified");
    }
}
