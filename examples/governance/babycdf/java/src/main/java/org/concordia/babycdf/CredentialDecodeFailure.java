package org.concordia.babycdf;
import java.io.IOException;
/** Fixed diagnostics only; no ledger values or original exception messages. */
final class CredentialDecodeFailure extends IntegrationStageFailure {
 enum Reason { labels,schema,observation,identity,lineage,fingerprint }
 final Reason reason;
 CredentialDecodeFailure(Reason reason){super(IntegrationStageFailure.Stage.credential_decode);this.reason=reason;}
}
