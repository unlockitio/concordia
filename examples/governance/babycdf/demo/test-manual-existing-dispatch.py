#!/usr/bin/env python3
"""Offline one-shot host-dispatch assertions for existing-round manual votes."""
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class ManualDispatchTest(unittest.TestCase):
    def test_manual_steps_are_one_shot(self):
        runner = (HERE / 'run-demo.sh').read_text()
        start = runner.index('run_step() {')
        function = runner[start:runner.index('\n}', start) + 2]
        self.assertNotIn('INTAKE_RUNNING', function)
        for step, script in ((3, 'proposalVote'), (4, 'milestoneVote')):
            for scenario in ('attempted', 'result', 'success', 'uncertain', 'gate'):
                with self.subTest(step=step, scenario=scenario), tempfile.TemporaryDirectory() as temp:
                    root = Path(temp)
                    (root / 'bootstrap.json').write_text('{}')
                    (root / 'run-id.json').write_text('"synthetic"')
                    (root / 'package-gate.py').write_text('raise SystemExit(7)\n' if scenario == 'gate' else 'pass\n')
                    attempted = root / f'step-{step}.attempted'
                    result_file = root / f'step-{step}.json'
                    if scenario == 'attempted':
                        attempted.write_text('uncertain retained marker\n')
                    if scenario == 'result':
                        result_file.write_text('existing result\n')
                    harness = f'''set -eu
STATE='{root}'
HERE='{root}'
RAW_DIR='{root}'
LEDGER_PORT=0
agent_log_summary() {{ :; }}
render_step() {{
  printf '%s\\n' "$@" >> "$STATE/dispatch"
  if [ '{scenario}' = uncertain ]; then return 9; fi
  previous=''
  for argument in "$@"; do
    if [ "$previous" = --output-file ]; then printf '"complete"' > "$argument"; fi
    previous="$argument"
  done
}}
{function}
run_step {step}
'''
                    first = subprocess.run(['bash', '-c', harness], capture_output=True, text=True)
                    expected = {'attempted': 2, 'result': 2, 'success': 0, 'uncertain': 9, 'gate': 7}[scenario]
                    self.assertEqual(first.returncode, expected, first.stderr)
                    if scenario == 'gate':
                        self.assertFalse(attempted.exists())
                        self.assertFalse((root / 'dispatch').exists())
                        continue
                    second = subprocess.run(['bash', '-c', harness], capture_output=True, text=True)
                    self.assertEqual(second.returncode, 2, second.stderr)
                    self.assertNotIn('run down/up', second.stderr)
                    dispatch = (root / 'dispatch').read_text() if (root / 'dispatch').exists() else ''
                    self.assertEqual(dispatch.count('Cap.Examples.BabyCdf.Live:' + script), int(scenario in ('success', 'uncertain')))
                    self.assertNotIn('requestIntake', dispatch)
                    if scenario == 'attempted':
                        self.assertEqual(attempted.read_text(), 'uncertain retained marker\n')
                    if scenario == 'result':
                        self.assertEqual(result_file.read_text(), 'existing result\n')
                    if scenario == 'uncertain':
                        self.assertTrue(attempted.exists())
                        self.assertFalse(result_file.exists())


if __name__ == '__main__':
    unittest.main()
