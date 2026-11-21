#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline tests for format-log.py.

Fixtures are the real SDK output shapes captured from recorded runs of this
demo, including the escaped `\\"..."` literal form that made the terminal
unreadable. No ledger, container or network is involved.

    python3 examples/governance/babycdf/demo/test-format-log.py
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "format-log.py"

# Loading by path writes a __pycache__ beside the script unless bytecode is
# disabled. The repository has no Python build artefacts and should not gain
# one from running tests, so caching is turned off for this import. Callers
# are also expected to use `python3 -B`; this makes it unconditional.
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("format_log", SCRIPT)
fmt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fmt)


def render(line, verbose=False):
    return fmt.render(line, verbose)


class DebugWrapper(unittest.TestCase):
    """The exact shapes observed in recorded dpm script output."""

    def test_escaped_literal_is_decoded_to_payload_text(self):
        # Captured verbatim from a recorded step-2 run.
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2] proposing rule v2; host submits as synthetic opener/voters, not agent user\"'
        self.assertEqual(
            render(line),
            "proposing rule v2; host submits as synthetic opener/voters, not agent user",
        )

    def test_plain_quoted_literal_is_also_accepted(self):
        line = '[DA.Internal.Prelude:557]: "[STEP 3][BUSINESS] Subject: the proposal."'
        self.assertEqual(render(line), "Subject: the proposal.")

    def test_phase_heading_keeps_its_hashes_and_blank_line(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][PHASE] \n########## PROPOSAL ##########\"'
        self.assertEqual(render(line), "########## PROPOSAL ##########")

    def test_step_banner_is_rendered(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][PHASE] \n################ STEP 2: RULE GOVERNANCE ################\"'
        self.assertEqual(render(line), "################ STEP 2: RULE GOVERNANCE ################")

    def test_other_source_locations_are_handled(self):
        line = r'[Cap.Examples.BabyCdf.Live:123]: \"[STEP 1][BUSINESS] Electorate: 7 members.\"'
        self.assertEqual(render(line), "Electorate: 7 members.")

    def test_payload_containing_the_prefix_delimiter_is_not_truncated(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 6][EXPECTED-REFUSAL] Actual: REJECTED]: not a member\"'
        self.assertEqual(render(line), "Actual: REJECTED]: not a member")


class ZeroWording(unittest.TestCase):
    """A zero count must not be explained by inference.

    Before issuance and after resolution both read 0. Only the post-resolution
    snapshot may attribute that to consumption, and only because a resolve was
    just observed. These assert the wording the Daml source emits, as rendered.
    """

    def test_pre_issue_zero_is_neutral(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][EVIDENCE] VoteRecord active: 0 (no active records visible in this scope)\"'
        out = render(line)
        self.assertEqual(out, "VoteRecord active: 0 (no active records visible in this scope)")
        self.assertNotIn("consumed", out)

    def test_post_resolution_explanation_is_a_separate_line(self):
        line = (r'[DA.Internal.Prelude:557]: \"[STEP 2][EVIDENCE] The records counted in the '
                r'snapshot above were consumed by the resolve just observed; compare the '
                r'before-casting and after-casting snapshots for what they held. '
                r'Archived contracts: ./ledger.sh archived\"')
        out = render(line)
        self.assertIn("consumed by the resolve just observed", out)
        self.assertIn("before-casting and after-casting snapshots", out)
        self.assertIn("./ledger.sh archived", out)

    def test_nonzero_count_carries_the_breakdown_not_an_explanation(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][EVIDENCE] VoteRecord active: 7 (0 YES, 0 NO, 7 ABSENT)\"'
        out = render(line)
        self.assertIn("7 ABSENT", out)
        self.assertNotIn("consumed", out)
        self.assertNotIn("no active records", out)


class Multiline(unittest.TestCase):
    def test_embedded_newline_becomes_separate_lines(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][PHASE] \n########## COMMITTEE ##########\nElectorate: 7\"'
        self.assertEqual(
            render(line), "########## COMMITTEE ##########\nElectorate: 7"
        )

    def test_blank_segments_are_dropped_not_printed_as_empty_noise(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] \n\n\nkept\"'
        self.assertEqual(render(line), "kept")


class TechnicalLines(unittest.TestCase):
    def test_action_is_hidden_by_default_and_shown_with_verbose(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 3][ACTION] opener exercises ResolveAgenda -> Decided Yes\"'
        self.assertIsNone(render(line))
        self.assertEqual(
            render(line, verbose=True),
            "opener exercises ResolveAgenda -> Decided Yes",
        )

    def test_timing_is_hidden_by_default(self):
        line = r'[DA.Internal.Prelude:557]: \"[TIMING] waiting tick 10 now 2026-11-19T00:00:00Z\"'
        self.assertIsNone(render(line))
        self.assertIsNotNone(render(line, verbose=True))

    def test_evidence_lines_are_always_presentation(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][EVIDENCE] VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)\"'
        self.assertEqual(render(line), "VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)")

    def test_nested_rejected_action_is_still_technical(self):
        # The demo's rejection lines are [ACTION][REJECTED]; the leading tag
        # decides, and the inner tag is preserved in verbose output.
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][ACTION][REJECTED] opener exercises X -> participant error\"'
        self.assertIsNone(render(line))
        self.assertEqual(
            render(line, verbose=True),
            "[REJECTED] opener exercises X -> participant error",
        )


class AnchoredTagParsing(unittest.TestCase):
    """Tags are recognised only at the start, never by substring search."""

    def test_presentation_text_mentioning_action_is_not_hidden(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][BUSINESS] Each [ACTION] line is in the raw log.\"'
        self.assertEqual(render(line), "Each [ACTION] line is in the raw log.")

    def test_presentation_text_mentioning_timing_is_not_hidden(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 6][RESULT] See the [TIMING] entries for waits.\"'
        self.assertEqual(render(line), "See the [TIMING] entries for waits.")

    def test_unknown_tag_is_preserved_with_its_text(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 3][SOMETHING-NEW] do not swallow me\"'
        self.assertEqual(render(line), "[SOMETHING-NEW] do not swallow me")

    def test_payload_starting_with_a_bracket_that_is_not_a_tag_is_kept(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] [see docs] for detail\"'
        self.assertEqual(render(line), "[see docs] for detail")

    def test_step_prefix_is_optional(self):
        line = r'[DA.Internal.Prelude:557]: \"[TIMING] resolve 2026-11-19T00:00:00Z\"'
        self.assertIsNone(render(line))
        line2 = r'[DA.Internal.Prelude:557]: \"[BUSINESS] no step prefix here\"'
        self.assertEqual(render(line2), "no step prefix here")

    def test_non_numeric_step_prefix_is_not_treated_as_one(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP two][BUSINESS] kept verbatim\"'
        self.assertIn("[STEP two]", render(line))

    def test_untagged_payload_is_shown_unchanged(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1] plain step debug line\"'
        self.assertEqual(render(line), "plain step debug line")


class NonDebugLines(unittest.TestCase):
    """Real errors and runner output must never be filtered away."""

    def test_ordinary_error_line_passes_through(self):
        for line in (
            "Exception in thread main java.lang.RuntimeException: boom",
            "ERROR: Creation of DAR file failed.",
            "INFO: Epoll available during static init of TcpMetrics:false",
            "local DAR bytes unchanged since bootstrap; main=83c528f6",
            "        at java.base/java.lang.Thread.run(Thread.java:840)",
        ):
            self.assertEqual(render(line), line)

    def test_daml_assertion_failure_text_passes_through(self):
        line = 'Script execution failed on commit at Cap.Examples.BabyCdf.Live:999: Aborted:  stale execution unexpectedly succeeded'
        self.assertEqual(render(line), line)

    def test_wrapper_with_unparsable_literal_is_shown_not_dropped(self):
        # Recognised prefix but the payload is not a literal: showing it is the
        # safe choice, because dropping it could hide something that matters.
        line = "[DA.Internal.Prelude:557]: not-a-literal"
        self.assertEqual(render(line), line)


class Escapes(unittest.TestCase):
    def test_known_escapes_are_decoded(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] a\tb \\ c \"quoted\" d\"'
        out = render(line)
        self.assertIn("\t", out)
        self.assertIn("\\", out)
        self.assertIn('"quoted"', out)

    def test_numeric_escape_for_printable_ascii_is_decoded(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] arrow\x2Dhere\"'
        self.assertEqual(render(line), "arrow-here")

    def test_non_ascii_numeric_escape_is_dropped_not_shown_as_garbage(self):
        # \x2192 is the right-arrow the user's terminal rendered as an escape.
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] a \x2192 b\"'
        out = render(line)
        self.assertNotIn("x2192", out)
        self.assertNotIn("\\", out)

    def test_unknown_escape_is_kept_verbatim(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] keep \q this\"'
        self.assertIn(r"\q", render(line))

    def test_output_is_printable_ascii_for_ascii_payloads(self):
        line = r'[DA.Internal.Prelude:557]: \"[STEP 2][PHASE] \n########## RESOLUTION ##########\"'
        out = render(line)
        self.assertTrue(all(ch == "\n" or 32 <= ord(ch) < 127 for ch in out), repr(out))

    def test_no_code_is_evaluated(self):
        # A payload that would be dangerous to eval must be treated as text.
        line = r'[DA.Internal.Prelude:557]: \"[STEP 1][BUSINESS] __import__(\x27os\x27).system(\x27echo pwned\x27)\"'
        out = render(line)
        self.assertIn("__import__", out)
        source = SCRIPT.read_text()
        for forbidden in ("eval(", "exec(", "literal_eval", "json.loads", "pickle"):
            self.assertNotIn(forbidden, source, f"{forbidden} must not appear")


class EndToEnd(unittest.TestCase):
    """The script as a process: streaming, exit code, raw capture."""

    def run_script(self, stdin, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            input=stdin, capture_output=True, text=True, check=False,
        )

    FIXTURE = "\n".join([
        "local DAR bytes unchanged since bootstrap; main=83c528f6",
        r'[DA.Internal.Prelude:557]: \"[STEP 2][PHASE] \n################ STEP 2: RULE GOVERNANCE ################\"',
        r'[DA.Internal.Prelude:557]: \"[STEP 2][PHASE] \n########## PROPOSAL ##########\"',
        r'[DA.Internal.Prelude:557]: \"[STEP 2][BUSINESS] Rule architecture-review: v1 -> v2.\"',
        r'[DA.Internal.Prelude:557]: \"[STEP 2][ACTION] member-a exercises CastAgendaVote\"',
        r'[DA.Internal.Prelude:557]: \"[TIMING] resolve 2026-11-19T00:00:00Z\"',
        r'[DA.Internal.Prelude:557]: \"[STEP 2][EVIDENCE] VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)\"',
        r'[DA.Internal.Prelude:557]: \"[STEP 2][DECISION] APPROVED - 3 YES, 0 NO, 4 ABSENT.\"',
        "INFO: some java noise",
    ]) + "\n"

    def test_default_output_is_concise_and_has_no_sdk_prefix(self):
        result = self.run_script(self.FIXTURE)
        self.assertEqual(result.returncode, 0, result.stderr)
        out = result.stdout
        self.assertNotIn("[DA.", out)
        self.assertNotIn('\\"', out)
        self.assertIn("################ STEP 2: RULE GOVERNANCE ################", out)
        self.assertIn("########## PROPOSAL ##########", out)
        self.assertIn("Rule architecture-review: v1 -> v2.", out)
        self.assertIn("VoteRecord active: 7 (3 YES, 0 NO, 4 ABSENT)", out)
        self.assertIn("APPROVED - 3 YES, 0 NO, 4 ABSENT.", out)
        # Technical lines withheld; non-debug lines kept.
        self.assertNotIn("CastAgendaVote", out)
        self.assertNotIn("resolve 2026", out)
        self.assertIn("INFO: some java noise", out)
        self.assertIn("local DAR bytes unchanged", out)

    def test_verbose_output_is_complete(self):
        result = self.run_script(self.FIXTURE, "--verbose")
        self.assertIn("CastAgendaVote", result.stdout)
        self.assertIn("resolve 2026", result.stdout)
        self.assertIn("APPROVED - 3 YES", result.stdout)

    def test_raw_capture_holds_every_original_line_unmodified(self):
        with tempfile.TemporaryDirectory() as directory:
            raw = pathlib.Path(directory) / "raw.log"
            self.run_script(self.FIXTURE, "--raw", str(raw))
            captured = raw.read_text()
            self.assertEqual(captured, self.FIXTURE)
            # The raw log keeps the SDK prefix the presentation removed.
            self.assertIn("[DA.Internal.Prelude:557]", captured)
            self.assertIn("[ACTION]", captured)
            self.assertIn("[TIMING]", captured)

    def test_phase_order_is_preserved(self):
        out = self.run_script(self.FIXTURE).stdout.splitlines()
        banner = next(i for i, l in enumerate(out) if "STEP 2: RULE GOVERNANCE" in l)
        proposal = next(i for i, l in enumerate(out) if "PROPOSAL" in l)
        decision = next(i for i, l in enumerate(out) if "APPROVED -" in l)
        self.assertLess(banner, proposal)
        self.assertLess(proposal, decision)


if __name__ == "__main__":
    unittest.main(verbosity=2)
