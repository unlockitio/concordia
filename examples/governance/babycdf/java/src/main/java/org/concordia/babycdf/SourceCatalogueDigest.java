// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import java.io.IOException;
import java.util.Set;

/** Canonical v2 credential digest cross-checked against SDK 3.5.11 vectors.
 * A matching digest establishes integrity only, never transaction execution proof.
 * text() is retained solely for the separate legacy-shaped scan digest.
 */
final class SourceCatalogueDigest {
    private SourceCatalogueDigest() { }
    static String text(String input) {
        String[] controls={"NUL","SOH","STX","ETX","EOT","ENQ","ACK","a","b","t","n","v","f","r","SO","SI",
                "DLE","DC1","DC2","DC3","DC4","NAK","SYN","ETB","CAN","EM","SUB","ESC","FS","GS","RS","US"};
        int[] points=input.codePoints().toArray(); var out=new StringBuilder("\"");
        for(int i=0;i<points.length;i++) {
            int p=points[i], next=i+1<points.length?points[i+1]:-1;
            if(p=='"' || p=='\\') out.append('\\').append((char)p);
            else if(p<32) { out.append('\\').append(controls[p]); if(p==14 && next=='H') out.append("\\&"); }
            else if(p==127) out.append("\\DEL");
            else if(p>127) { out.append('\\').append(p); if(next>='0' && next<='9') out.append("\\&"); }
            else out.appendCodePoint(p);
        }
        return out.append('"').toString();
    }
    static String digest(ValueOuterClass.Record observation) throws IOException { return digest(observation,1,null); }
    static String digest(ValueOuterClass.Record observation,long version,String previous) throws IOException {
        var f=SourceCatalogueCodec.fields(observation,Set.of("number","kind","sourceUrl","title","bodySha256","state",
                "updatedAt","observedAt","verification","parentPR","linkEvidence"));
        var parent=f.get("parentPR"); if(!parent.hasOptional()) throw new IOException("invalid digest parent");
        try {
            return SourceObservation.hash(SourceObservationCanonical.serialize(SourceCatalogueCodec.REPOSITORY,
                SourceCatalogueCodec.integer(f.get("number")),
                SourceCatalogueCodec.constructor(f.get("kind")).equals("SourceProposal")?"proposal":"milestone",
                SourceCatalogueCodec.text(f.get("title")),SourceCatalogueCodec.text(f.get("sourceUrl")),
                SourceCatalogueCodec.text(f.get("bodySha256")),
                SourceCatalogueCodec.constructor(f.get("state")).equals("SourceOpen")?"open":"closed",
                SourceCatalogueCodec.timestamp(f.get("updatedAt")),parent.getOptional().hasValue()?SourceCatalogueCodec.integer(parent.getOptional().getValue()):null,
                SourceCatalogueCodec.text(f.get("linkEvidence")),
                SourceCatalogueCodec.constructor(f.get("verification")).equals("ObservedUnverified")?"observed_unverified":"content_digest_checked",
                version,previous));
        } catch(IllegalArgumentException invalid) {throw new IOException("invalid v2 source digest");}
    }
}
