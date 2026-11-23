package org.concordia.babycdf;
import java.io.IOException;
/** Fixed categories only: provider/ledger content never becomes diagnostic text. */
final class IntegrationStageFailure extends IOException {
 enum Stage { credential_decode,rule_selection,evidence_lookup }
 final Stage stage;
 IntegrationStageFailure(Stage stage){super(stage.name());this.stage=stage;}
}
