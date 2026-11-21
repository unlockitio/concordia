package org.concordia.babycdf;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Package names are for selection, deployed IDs remain authoritative for events/commands. */
class LedgerFilterIdentityTest {
    @Test void intakeFiltersUseManifestPackageNames() {
        for(String entity:java.util.List.of("IntakeRequest","AgendaProposal","AgendaAction","AgendaGovernor")) {
            boolean demo=entity.equals("IntakeRequest");
            String module=demo ? "Cap.Examples.BabyCdf.Intake" : "Cap.Examples.BabyCdf.RuleVote";
            var request=IntakeLedger.activeContractsRequest("opener","a".repeat(64),module,entity,42L);
            assertEquals(42L,request.getActiveAtOffset());
            assertTrue(request.getEventFormat().getVerbose());
            var filters=request.getEventFormat().getFiltersByPartyOrThrow("opener");
            assertEquals(1,filters.getCumulativeCount());
            var id=filters.getCumulative(0).getTemplateFilter().getTemplateId();
            assertTrue(id.getPackageId().startsWith("#"));
            assertEquals(demo ? IntakeLedger.DEMO_PACKAGE_NAME : IntakeLedger.IMPL_PACKAGE_NAME,id.getPackageId());
            assertEquals(module,id.getModuleName());
            assertEquals(entity,id.getEntityName());
        }
    }
    @Test void unknownFilterModuleIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> IntakeLedger.filterId("unknown.module","IntakeRequest"));
    }
    @Test void intakeRejectsWrongDeployedPackageForEveryQueriedTemplate() throws Exception {
        for (String entity : java.util.List.of("IntakeRequest", "AgendaProposal", "AgendaAction")) {
            String module=entity.equals("IntakeRequest") ? "Cap.Examples.BabyCdf.Intake" : "Cap.Examples.BabyCdf.RuleVote";
            var expected=IntakeLedger.id("a".repeat(64),module,entity);
            var event=com.daml.ledger.api.v2.EventOuterClass.CreatedEvent.newBuilder()
                    .setTemplateId(expected).setCreateArguments(com.daml.ledger.api.v2.ValueOuterClass.Record.getDefaultInstance())
                    .addWitnessParties("opener").build();
            assertDoesNotThrow(() -> IntakeLedger.verifyActiveEvent(event,expected,"opener"));
            var wrong=event.toBuilder().setTemplateId(expected.toBuilder().setPackageId("b".repeat(64))).build();
            assertThrows(java.io.IOException.class,() -> IntakeLedger.verifyActiveEvent(wrong,expected,"opener"));
        }
    }
    @Test void intakeCommandUsesDeployedDemoPackage() throws Exception {
        String deployed="b".repeat(64);
        var r=IntakeTest.request();
        var plan=new IntakePlan(IntakeSources.MILESTONE,r.roundId());
        var id=IntakeLedger.request(r,plan,deployed,"gov","readback-intake").getCommands()
                .getCommands(0).getExercise().getTemplateId();
        assertEquals(deployed,id.getPackageId());
        assertFalse(id.getPackageId().startsWith("#"));
        assertEquals("Cap.Examples.BabyCdf.Intake",id.getModuleName());
        assertEquals("IntakeRequest",id.getEntityName());
    }
}
