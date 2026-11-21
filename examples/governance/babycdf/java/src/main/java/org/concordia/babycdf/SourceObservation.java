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
public record SourceObservation(long number, Kind kind, String url, String title, String body,
        String state, Instant updatedAt, Instant observedAt, Long parentPR, String linkEvidence,
        boolean merged, String observedDigest, boolean truncated) {
    public enum Kind { PROPOSAL, MILESTONE }
    private static final String WEB="https://github.com/canton-foundation/canton-dev-fund/";
    private static final Pattern MILESTONE=Pattern.compile("^.+ #([1-9][0-9]*) - Milestone [0-9]+: .+$");
    public SourceObservation {
        if(number<1 || kind==null || !url.equals(WEB+(kind==Kind.PROPOSAL?"pull/":"issues/")+number)
                || title.isEmpty() || title.length()>512 || body.length()>32768 || linkEvidence.length()>4096
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
            // Hash the full observed field bytes before model truncation; not JSON property order.
            StringBuilder canonical=new StringBuilder();
            for(Object field:new Object[]{n.longValue(),url,title,body,state,updated,parent,evidence,merged}) {
                String value=String.valueOf(field); canonical.append(value.length()).append(':').append(value);
            }
            String digest=hash(canonical.toString());
            boolean truncated=body.length()>32768 || evidence.length()>4096;
            return new SourceObservation(n.longValue(),pulls?Kind.PROPOSAL:Kind.MILESTONE,url,title,
                    bounded(body,32768),state,updated,observedAt,parent,bounded(evidence,4096),merged,digest,truncated);
        } catch(RuntimeException e) { throw new IOException("invalid source record; page not committed",e); }
    }
    private static String text(JsonNode row,String key) {
        var v=row.get(key); if(v==null || !v.isTextual()) throw new IllegalArgumentException(); return v.textValue();
    }
    private static String bounded(String value,int max) {
        if(value.length()<=max) return value;
        String suffix="\n[TRUNCATED; full bounded response record SHA-256 recorded separately]";
        int end=max-suffix.length();
        if(Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end)+suffix;
    }
    /** Length-prefixed canonical fields; receipt time is deliberately excluded. Not the Daml digest. */
    public String fingerprint() {
        var b=new StringBuilder();
        for(Object field:new Object[]{number,kind,url,title,body,state,updatedAt,parentPR,linkEvidence,merged,observedDigest,truncated}) {
            String s=String.valueOf(field); b.append(s.length()).append(':').append(s);
        }
        return hash(b.toString());
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
