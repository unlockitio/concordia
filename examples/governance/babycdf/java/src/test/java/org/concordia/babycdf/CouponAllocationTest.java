package org.concordia.babycdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CouponAllocationTest {
    Instant now = Instant.parse("2026-10-22T00:00:00Z");
    String manager = "development-fund-manager::1220aaaa";

    List<String> blockers(JsonNode result) {
        List<String> found = new ArrayList<>();
        result.path("blockers").forEach(node -> found.add(node.asText()));
        return found;
    }
    boolean has(JsonNode result, String prefix) {
        return blockers(result).stream().anyMatch(blocker -> blocker.startsWith(prefix));
    }
    CouponAllocation.AllocationInput complete() {
        return new CouponAllocation.AllocationInput(
                List.of("#unclaimed-1"), "concordia-beneficiary::1220bbbb", new BigDecimal("1500.00"),
                now.plusSeconds(86400), "Concordia #184 Milestone 3: https://github.com/" + Advisory.REPO + "/issues/540",
                "#amulet-rules-1", manager, manager, "a".repeat(40), "#approved-milestone-1");
    }

    @Test void completeInputStillBlocksOnAuthorityAndUnreadableLedgerState() {
        ObjectNode result = CouponAllocation.plan(complete(), true, now);
        assertEquals("BLOCKED", result.path("status").asText());
        assertFalse(result.path("submitted").asBoolean());
        assertFalse(result.path("transaction_attempted").asBoolean());
        assertTrue(has(result, "authority_boundary:"));
        assertTrue(has(result, "missing_ledger_credentials:"));
        assertTrue(has(result, "source_amounts_unverified:"));
        // Nothing about a satisfied input contract may be read as approval to submit.
        assertFalse(has(result, "missing_beneficiary"));
        assertFalse(has(result, "missing_amount"));
        assertFalse(has(result, "missing_expiry"));
        assertFalse(has(result, "missing_issue_reference"));
        assertFalse(has(result, "missing_milestone_evidence_pin"));
        assertFalse(has(result, "missing_approved_milestone"));
        assertFalse(has(result, "missing_fund_manager_config"));
        assertFalse(has(result, "fund_manager_mismatch"));
    }

    @Test void previewIsOptInAndMissingInputIsReportedWithoutOtherClaims() {
        assertTrue(has(CouponAllocation.plan(complete(), false, now), "preview_not_opted_in:"));
        ObjectNode none = CouponAllocation.plan(null, true, now);
        assertEquals("BLOCKED", none.path("status").asText());
        assertTrue(has(none, "missing_allocation_input:"));
        assertFalse(has(none, "authority_boundary"));
    }

    @Test void eachRequiredFieldIsIndependentlyRequired() {
        CouponAllocation.AllocationInput base = complete();
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(List.of(), base.beneficiary(),
                base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(), base.configuredFundManager(),
                base.submittingParty(), base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now),
                "missing_source_coupons:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(List.of("#a", "#a"), base.beneficiary(),
                base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(), base.configuredFundManager(),
                base.submittingParty(), base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now),
                "invalid_source_coupons:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                " ", base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(), base.configuredFundManager(),
                base.submittingParty(), base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now),
                "missing_beneficiary:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), base.reason(), "", base.configuredFundManager(),
                base.submittingParty(), base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now),
                "missing_amulet_rules_contract:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(), "",
                base.submittingParty(), base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now),
                "missing_fund_manager_config:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), base.milestoneEvidencePin(), " "), true, now),
                "missing_approved_milestone:"));
    }

    @Test void amountAndExpiryFollowTheTemplateAndChoiceAssertions() {
        CouponAllocation.AllocationInput base = complete();
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), BigDecimal.ZERO, base.expiresAt(), base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), base.milestoneEvidencePin(),
                base.approvedMilestoneContractId()), true, now), "invalid_amount:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), new BigDecimal("0.00000000001"), base.expiresAt(), base.reason(),
                base.amuletRulesContractId(), base.configuredFundManager(), base.submittingParty(),
                base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now), "invalid_amount:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), now, base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), base.milestoneEvidencePin(),
                base.approvedMilestoneContractId()), true, now), "invalid_expiry:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), null, base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), base.milestoneEvidencePin(),
                base.approvedMilestoneContractId()), true, now), "missing_expiry:"));
    }

    @Test void milestoneEvidenceMustBeImmutableAndIssueReferenced() {
        CouponAllocation.AllocationInput base = complete();
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), "Milestone 3 approved", base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), base.milestoneEvidencePin(),
                base.approvedMilestoneContractId()), true, now), "invalid_issue_reference:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), "2026-10-14T12:34:17Z",
                base.approvedMilestoneContractId()), true, now), "unpinned_milestone_evidence:"));
        assertTrue(has(CouponAllocation.plan(new CouponAllocation.AllocationInput(base.unclaimedDevelopmentFundCouponCids(),
                base.beneficiary(), base.amount(), base.expiresAt(), base.reason(), base.amuletRulesContractId(),
                base.configuredFundManager(), base.submittingParty(), "illustrative-immutable-milestone-revision-v1",
                base.approvedMilestoneContractId()), true, now), "illustrative_milestone_pin:"));
    }

    @Test void controllerMustEqualTheConfiguredManagerAndCannotBeTheDemoOpener() {
        CouponAllocation.AllocationInput base = complete();
        ObjectNode mismatch = CouponAllocation.plan(new CouponAllocation.AllocationInput(
                base.unclaimedDevelopmentFundCouponCids(), base.beneficiary(), base.amount(), base.expiresAt(),
                base.reason(), base.amuletRulesContractId(), manager, "demo-opener::1220cccc",
                base.milestoneEvidencePin(), base.approvedMilestoneContractId()), true, now);
        assertTrue(has(mismatch, "fund_manager_mismatch:"));
        assertTrue(has(mismatch, "authority_boundary:"));
    }

    @Test void inspectedDarFactsAreReportedWithoutUpstreamClaimsOrAMintStep() {
        ObjectNode result = CouponAllocation.plan(complete(), true, now);
        assertEquals("splice-amulet-0.1.22", result.path("dar_package_name").asText());
        assertEquals("fb10433a48c24f30076a7aee03a3e314b7ab02fe9a22e2069e3af92d2b6ac88a", result.path("dar_main_package_id").asText());
        assertEquals("3.5.2", result.path("dar_sdk_version").asText());
        assertEquals("unverified_no_upstream_sha_evidence", result.path("dar_upstream_revision").asText());
        assertEquals("Splice.Amulet:DevelopmentFundCoupon", result.path("allocation_creates").asText());
        assertEquals("separate_step_not_performed_by_allocation", result.path("beneficiary_mint").asText());
        List<String> fields = new ArrayList<>();
        result.path("choice_argument_fields").forEach(node -> fields.add(node.asText()));
        assertEquals(List.of("unclaimedDevelopmentFundCouponCids", "beneficiary", "amount", "expiresAt", "reason", "fundManager"), fields);
        List<String> absent = new ArrayList<>();
        result.path("requested_fields_absent_in_dar").forEach(node -> absent.add(node.asText()));
        assertEquals(List.of("mintAfter"), absent);
        List<String> choices = new ArrayList<>();
        result.path("allocated_coupon_choices").forEach(node -> choices.add(node.asText()));
        assertEquals(List.of("DevelopmentFundCoupon_Withdraw", "DevelopmentFundCoupon_Reject", "DevelopmentFundCoupon_DsoExpire"), choices);
        assertFalse(choices.stream().anyMatch(choice -> choice.toLowerCase().contains("mint")));
    }
}
