"""Regressions against verbatim offline IDE-ledger traces captured for both rounds."""
import argparse
import pathlib
import subprocess
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
PARSER = argparse.ArgumentParser()
PARSER.add_argument('--traces', type=pathlib.Path, default=HERE / 'fixtures/manual-vote-narration')
PARSER.add_argument('--output', type=pathlib.Path)
ARGS, REST = PARSER.parse_known_args()


def round_trace(trace, step):
    # Keep the complete input separately; select only SDK-tagged round messages.
    prefix = f'[STEP {step}]'
    started = False
    selected = []
    for line in trace.splitlines(keepends=True):
        if prefix in line and 'EXISTING ' in line and 'MANUAL VOTE' in line:
            if started:
                break
            started = True
        if started and (prefix in line or '[STEP ' not in line):
            selected.append(line)
    return ''.join(selected)


class ActualSdkNarration(unittest.TestCase):
    def render(self, text, *options):
        result = subprocess.run([sys.executable, str(HERE / 'format-log.py'), *options], input=text, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_actual_rounds(self):
        for step, subject, approval in [(3, 'pull/184', 'Grant proposal approval'), (4, 'issues/540', 'Milestone approval')]:
            with self.subTest(step=step):
                full = (ARGS.traces / f'STEP{step}.sdk.log').read_text()
                self.assertIn(' SUCCESS', full)
                selected = round_trace(full, step)
                normal = self.render(selected)
                verbose = self.render(selected, '--verbose')
                for value in [subject, 'Subject #', 'source pin:', 'Voting window closes at', 'credentials have passed validation', 'invite the primary electorate', '3 YES, 2 ABSENT', '3-of-5', approval, 'not a cash payment or proof of delivery', 'Exactly one approval was created']:
                    self.assertIn(value, normal)
                for name in ['EduardoFromTheFuture', 'ali-abrar', 'heslin-zenith']:
                    self.assertIn(name + ': YES confirmed after successful cast', normal)
                for name in ['jonathan-ieu', 'shaul-da']:
                    self.assertIn(name + ': ABSENT', normal)
                headings = ['EXISTING PINNED TOPIC', 'VALIDATED ELECTORATE AND BALLOTS', 'CAST THE SELECTED PRIMARY VOTES', 'RESOLVE THE ACTUAL TOPIC', 'EXECUTE THE RETURNED APPROVAL']
                positions = [normal.index(h) for h in headings]
                self.assertEqual(positions, sorted(positions))
                self.assertNotIn('\\n', normal)
                self.assertNotIn('CastAgendaVote', normal)
                self.assertIn('CastAgendaVote', verbose)
                self.assertIn('Executable', verbose)
                self.assertNotIn(f'[STEP {7-step}]', normal)
                if ARGS.output:
                    ARGS.output.mkdir(parents=True, exist_ok=True)
                    (ARGS.output / f'STEP{step}.txt').write_text(normal)
                    (ARGS.output / f'STEP{step}.verbose.txt').write_text(verbose)
                    raw = ARGS.output / f'STEP{step}.raw.log'
                    raw.write_text('')
                    self.render(full, '--raw', str(raw))
                    self.assertEqual(raw.read_text(), full)

    def test_diagnostics_are_preserved(self):
        text = '[UNKNOWN] preserve unknown\nWARNING: retain warning\nERROR: retain error\n  diagnostic continuation\nrefused: supply an unambiguous existing round\n'
        for line in text.splitlines():
            self.assertIn(line.strip(), self.render(text))


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *REST], verbosity=2)
