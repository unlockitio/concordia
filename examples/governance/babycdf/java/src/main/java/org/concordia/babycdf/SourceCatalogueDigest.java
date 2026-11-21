// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import com.daml.ledger.api.v2.ValueOuterClass;
import java.io.IOException;
import java.util.Set;

/** Daml Show mapping cross-checked against SDK 3.5.11 SourceCatalogueGolden vectors.
 * A matching digest establishes content integrity only, never transaction execution proof.
 * Distinct from the Java canonical observation fingerprint.
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
    static String digest(ValueOuterClass.Record observation) throws IOException {
        var f=SourceCatalogueCodec.fields(observation,Set.of("number","kind","sourceUrl","title","body","state",
                "updatedAt","observedAt","verification","parentPR","linkEvidence"));
        var parent=f.get("parentPR"); if(!parent.hasOptional()) throw new IOException("invalid digest parent");
        String optional=parent.getOptional().hasValue()?"Some "+SourceCatalogueCodec.integer(parent.getOptional().getValue()):"None";
        String preimage="(("+SourceCatalogueCodec.integer(f.get("number"))+","+SourceCatalogueCodec.constructor(f.get("kind"))+","
                +text(SourceCatalogueCodec.text(f.get("sourceUrl")))+","+text(SourceCatalogueCodec.text(f.get("title")))+","
                +text(SourceCatalogueCodec.text(f.get("body")))+"),("+SourceCatalogueCodec.constructor(f.get("state"))+","
                +SourceCatalogueCodec.timestamp(f.get("updatedAt"))+","+SourceCatalogueCodec.constructor(f.get("verification"))+","
                +optional+","+text(SourceCatalogueCodec.text(f.get("linkEvidence")))+"))";
        return SourceObservation.hash(preimage);
    }
}
