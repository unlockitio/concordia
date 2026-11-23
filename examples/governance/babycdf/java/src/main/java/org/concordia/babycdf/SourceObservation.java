// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Public, unverified source evidence. Never an agenda authorization. */
public record SourceObservation(long number, Kind kind, String url, String title, String bodySha256,
        String state, Instant updatedAt, Instant observedAt, Long parentPR, String linkEvidence,
        boolean merged, String observedDigest, boolean truncated) {
    public enum Kind { PROPOSAL, MILESTONE }
    private static final String WEB="https://github.com/canton-foundation/canton-dev-fund/";
    private static final Pattern MILESTONE=Pattern.compile("^.+ #([1-9][0-9]*) - Milestone [0-9]+: .+$");
    public SourceObservation {
        if(number<1 || kind==null || !url.equals(WEB+(kind==Kind.PROPOSAL?"pull/":"issues/")+number)
                || title.isEmpty() || title.length()>512 || !bodySha256.matches("[0-9a-f]{64}") || linkEvidence.length()>4096
                || !java.util.Set.of("open","closed").contains(state) || updatedAt==null || observedAt==null
                || updatedAt.isAfter(observedAt) || (parentPR!=null && (parentPR<1 || parentPR==number || kind!=Kind.MILESTONE))
                || !observedDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid source observation");
    }
    /** Null means an explicitly excluded PR mirror or ordinary issue, not a malformed record. */
    public static SourceObservation parse(JsonNode row,boolean pulls,Instant observedAt) throws IOException {
        try {
            if(!row.isObject()) throw new IllegalArgumentException();
            if(!pulls && row.has("pull_request")) return null;
            var n=row.get("number");
            if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<1) throw new IllegalArgumentException();
            String title=text(row,"title"), url=text(row,"html_url"), state=text(row,"state");
            String body;
            if(!row.has("body")) throw new IllegalArgumentException();
            body=row.get("body").isNull()?"":text(row,"body");
            Instant updated=Instant.parse(text(row,"updated_at"));
            Long parent=null; String evidence="";
            if(!pulls) {
                var match=MILESTONE.matcher(title);
                if(!match.matches()) return null;
                parent=Long.parseLong(match.group(1));
                evidence="title convention: "+title;
            }
            boolean merged=false;
            if(pulls) {
                if(!row.has("merged_at")) throw new IllegalArgumentException();
                if(!row.get("merged_at").isNull()) {
                    Instant merge=Instant.parse(text(row,"merged_at"));
                    if(merge.isAfter(observedAt) || !state.equals("closed")) throw new IllegalArgumentException();
                    merged=true;
                }
            }
            // Raw body exists only within this discovery frame; retain its byte hash only.
            SourceObservationCanonical.quote(body); // Reject unpaired surrogates before UTF-8 encoding.
            if(evidence.length()>4096) throw new IllegalArgumentException();
            String digest=hash(body);
            return new SourceObservation(n.longValue(),pulls?Kind.PROPOSAL:Kind.MILESTONE,url,title,
                    hash(body),state,updated,observedAt,parent,evidence,false,digest,false);
        } catch(RuntimeException e) { throw new IOException("invalid source record; page not committed"); }
    }
    private static String text(JsonNode row,String key) {
        var v=row.get(key); if(v==null || !v.isTextual()) throw new IllegalArgumentException(); return v.textValue();
    }
    /** Content comparison at initial lineage; receipt time is never content. */
    public String fingerprint() { return fingerprint(1,null); }
    public String fingerprint(long version,String previousDigest) {
        return hash(SourceObservationCanonical.serialize(SourceCatalogueCodec.REPOSITORY,number,
                kind==Kind.PROPOSAL?"proposal":"milestone",title,url,bodySha256,state,updatedAt,
                parentPR,linkEvidence,"observed_unverified",version,previousDigest));
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
