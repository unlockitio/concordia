package org.concordia.babycdf;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed input contract and planning-only preview for a Development Fund coupon allocation.
 * This class has no ledger, HTTP or submission seam of any kind: it can only describe what an
 * authorized Development Fund manager would need, and why this demo is not that manager.
 */
public final class CouponAllocation {
    /** Candidate DAR inspected locally; not asserted to match any upstream Splice revision. */
    static final String DAR_PATH = "examples/lib/splice-amulet-0.1.22.dar";
    static final String DAR_PACKAGE_NAME = "splice-amulet-0.1.22";
    static final String DAR_MAIN_PACKAGE_ID = "fb10433a48c24f30076a7aee03a3e314b7ab02fe9a22e2069e3af92d2b6ac88a";
    static final String DAR_SDK_VERSION = "3.5.2";
    static final String CHOICE = "AmuletRules_AllocateDevelopmentFundCoupon";
    static final String ALLOCATED_TEMPLATE = "Splice.Amulet:DevelopmentFundCoupon";
    static final String SOURCE_TEMPLATE = "Splice.Amulet:UnclaimedDevelopmentFundCoupon";
    static final String CONFIG_FIELD = "optDevelopmentFundManager";

    /** Argument record observed in the DAR source, in declaration order. */
    static final List<String> CHOICE_ARGUMENT_FIELDS = List.of(
            "unclaimedDevelopmentFundCouponCids", "beneficiary", "amount", "expiresAt", "reason", "fundManager");

    /**
     * Choices observed on the allocated coupon in this DAR. There is no beneficiary mint or collect
     * choice here, so an allocation in this package version cannot be completed into Amulet by this
     * model and must not be presented as a mint.
     */
    static final List<String> ALLOCATED_COUPON_CHOICES = List.of(
            "DevelopmentFundCoupon_Withdraw", "DevelopmentFundCoupon_Reject", "DevelopmentFundCoupon_DsoExpire");

    /** The choice takes expiresAt only; no mintAfter field exists in this package version. */
    static final List<String> ABSENT_REQUESTED_FIELDS = List.of("mintAfter");

    static final int AMOUNT_SCALE = 10;
    static final String ISSUE_PREFIX = "https://github.com/" + Advisory.REPO + "/issues/";
    static final String REJECTED_ILLUSTRATIVE_PIN = "illustrative-immutable-milestone-revision-v1";

    private CouponAllocation() { }

    /**
     * Every field an authorized allocation would require. Values are caller-supplied claims; none of
     * them is treated as verified authority by this class.
     */
    public record AllocationInput(
            List<String> unclaimedDevelopmentFundCouponCids,
            String beneficiary,
            BigDecimal amount,
            Instant expiresAt,
            String reason,
            String amuletRulesContractId,
            String configuredFundManager,
            String submittingParty,
            String milestoneEvidencePin,
            String approvedMilestoneContractId) { }

    static boolean present(String value) {
        return value != null && !value.isBlank() && value.codePoints().noneMatch(Character::isWhitespace);
    }

    /** Fail closed and report every missing requirement, rather than the first one. */
    public static ObjectNode plan(AllocationInput input, boolean previewOptIn, Instant planningTime) {
        ObjectNode result = Advisory.JSON.createObjectNode().put("scope", Advisory.SCOPE)
                .put("intent", "allocation_input_contract_preview")
                .put("choice", CHOICE).put("dar_path", DAR_PATH)
                .put("dar_package_name", DAR_PACKAGE_NAME)
                .put("dar_main_package_id", DAR_MAIN_PACKAGE_ID)
                .put("dar_sdk_version", DAR_SDK_VERSION)
                .put("dar_upstream_revision", "unverified_no_upstream_sha_evidence")
                .put("submitted", false).put("transaction_attempted", false)
                .put("allocation_creates", ALLOCATED_TEMPLATE)
                .put("beneficiary_mint", "separate_step_not_performed_by_allocation");
        ArrayNode fields = result.putArray("choice_argument_fields");
        CHOICE_ARGUMENT_FIELDS.forEach(fields::add);
        ArrayNode absent = result.putArray("requested_fields_absent_in_dar");
        ABSENT_REQUESTED_FIELDS.forEach(absent::add);
        ArrayNode couponChoices = result.putArray("allocated_coupon_choices");
        ALLOCATED_COUPON_CHOICES.forEach(couponChoices::add);
        ArrayNode blockers = result.putArray("blockers");
        List<String> found = new ArrayList<>();

        if (!previewOptIn) found.add("preview_not_opted_in: planning output requires explicit opt-in");
        if (input == null) {
            found.add("missing_allocation_input: no typed allocation input supplied");
            found.forEach(blockers::add);
            return result.put("status", "BLOCKED");
        }

        List<String> cids = input.unclaimedDevelopmentFundCouponCids();
        if (cids == null || cids.isEmpty()) found.add("missing_source_coupons: at least one " + SOURCE_TEMPLATE + " contract id is required");
        else {
            Set<String> unique = new LinkedHashSet<>(cids);
            if (cids.stream().anyMatch(cid -> !present(cid))) found.add("invalid_source_coupons: blank source contract id");
            else if (unique.size() != cids.size()) found.add("invalid_source_coupons: duplicate source contract id would double-archive");
        }
        found.add("source_amounts_unverified: the choice rejects an allocation exceeding the archived source total, "
                + "and this planner cannot read those amounts without an authenticated ledger read");

        if (!present(input.beneficiary())) found.add("missing_beneficiary: a beneficiary party identifier is required");
        if (!present(input.amuletRulesContractId())) found.add("missing_amulet_rules_contract: the AmuletRules contract id is required");

        BigDecimal amount = input.amount();
        if (amount == null) found.add("missing_amount: a demo allocation amount is required");
        else if (amount.signum() <= 0) found.add("invalid_amount: the coupon template requires amount > 0.0");
        else if (amount.scale() > AMOUNT_SCALE) found.add("invalid_amount: more than " + AMOUNT_SCALE + " decimal places is not a Daml Decimal");

        if (planningTime == null) found.add("missing_planning_time: an explicit planning instant is required");
        if (input.expiresAt() == null) found.add("missing_expiry: expiresAt is required");
        else if (planningTime != null && !input.expiresAt().isAfter(planningTime))
            found.add("invalid_expiry: the choice asserts expiresAt is still in the future");

        String reason = input.reason();
        if (reason == null || reason.isBlank()) found.add("missing_issue_reference: reason must carry the milestone issue reference");
        else if (!reason.contains(ISSUE_PREFIX)) found.add("invalid_issue_reference: reason must reference " + ISSUE_PREFIX);

        String pin = input.milestoneEvidencePin();
        if (pin == null || pin.isBlank()) found.add("missing_milestone_evidence_pin: a full immutable milestone evidence pin is required");
        else if (REJECTED_ILLUSTRATIVE_PIN.equals(pin)) found.add("illustrative_milestone_pin: the demo pin is explicitly illustrative, not verified immutable evidence");
        else if (!Advisory.SHA.matcher(pin).matches() && !pin.matches("[0-9a-f]{64}"))
            found.add("unpinned_milestone_evidence: GitHub updated_at and body hashes are not an immutable revision; a full 40-hex or 64-hex digest is required");
        if (!present(input.approvedMilestoneContractId()))
            found.add("missing_approved_milestone: an ApprovedMilestone contract id from a resolved affirmative round is required");

        // Authority: the choice controller must be the manager configured in the live AmuletRules config.
        String manager = input.configuredFundManager();
        String submitter = input.submittingParty();
        if (!present(manager)) found.add("missing_fund_manager_config: the live AmuletRules " + CONFIG_FIELD
                + " must be observed; the choice aborts when no manager is configured");
        if (!present(submitter)) found.add("missing_submitting_party: the acting party is required");
        if (present(manager) && present(submitter) && !manager.equals(submitter))
            found.add("fund_manager_mismatch: the choice requires the controller to equal the configured " + CONFIG_FIELD);
        found.add("authority_boundary: BabyCDF holds no Development Fund manager authority, and an approved demo milestone "
                + "does not make its opener the configured " + CONFIG_FIELD);
        found.add("missing_ledger_credentials: no configured ledger endpoint, authenticated party or supported SDK adapter");

        found.forEach(blockers::add);
        result.put("status", "BLOCKED");
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("usage: CouponAllocation (planning preview only; cannot submit)");
        System.out.println(Advisory.JSON.writeValueAsString(plan(null, true, Instant.now())));
    }
}
