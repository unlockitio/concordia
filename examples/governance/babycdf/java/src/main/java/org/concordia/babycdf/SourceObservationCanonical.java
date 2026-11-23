// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Fixed-order v2 UTF-8 JSON preimage. No raw body or receipt time is accepted. */
final class SourceObservationCanonical {
    static final String SCHEME = "babycdf-source-observation/v2";
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private SourceObservationCanonical() { }
    static String quote(String value) {
        var out = new StringBuilder("\"");
        for (int i=0; i<value.length(); i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i+1>=value.length() || !Character.isLowSurrogate(value.charAt(i+1)))
                    throw new IllegalArgumentException("invalid source Unicode");
                out.append(c).append(value.charAt(++i));
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid source Unicode");
            else switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    static String timestamp(Instant value) {
        if(value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")) || value.getNano()%1000!=0)
            throw new IllegalArgumentException("invalid source timestamp");
        return TIME.format(value);
    }
    static String serialize(String repo,long number,String kind,String title,String url,String bodySha256,
            String state,Instant updatedAt,Long parentPR,String linkEvidence,String verification,
            long version,String previousDigest) {
        if(!bodySha256.matches("[0-9a-f]{64}") || version<1
                || (version==1)!=(previousDigest==null)
                || (previousDigest!=null && !previousDigest.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("invalid source lineage");
        return "["+quote(SCHEME)+","+quote(repo)+","+number+","+quote(kind)+","+quote(title)+","+quote(url)
                +","+quote(bodySha256)+","+quote(state)+","+quote(timestamp(updatedAt))+","+parentPR+","
                +quote(linkEvidence)+","+quote(verification)+","+version+","
                +(previousDigest==null?"null":quote(previousDigest))+"]";
    }
}
