#!/usr/bin/env python3
"""Offline host-dispatch assertions for existing-round manual votes."""
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent


class ManualDispatchTest(unittest.TestCase):
    def test_dispatch_and_resume_preserve_markers(self):
        runner = (HERE / 'run-demo.sh').read_text()
        start = runner.index('run_step() {')
        end = runner.index('\n}', start) + 2
        function = runner[start:end]
        self.assertNotIn('INTAKE_RUNNING', function)
        for step, script in ((3, 'proposalVote'), (4, 'milestoneVote')):
            with self.subTest(step=step), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                (root / 'bootstrap.json').write_text('{}')
                (root / 'run-id.json').write_text('"synthetic"')
                (root / 'package-gate.py').write_text('pass\n')
                attempted = root / f'step-{step}.attempted'
                attempted.write_text('uncertain retained marker\n')
                harness = f'''set -eu
STATE='{root}'
HERE='{root}'
RAW_DIR='{root}'
LEDGER_PORT=0
python3() {{ command python3 "$@"; }}
agent_log_summary() {{ :; }}
render_step() {{
  printf '%s\\n' "$@" >> "$STATE/dispatch"
  previous=''
  for argument in "$@"; do
    if [ "$previous" = --output-file ]; then printf '"PENDING"' > "$argument"; fi
    previous="$argument"
  done
}}
{function}
run_step {step}
run_step {step}
'''
                result = subprocess.run(['bash', '-c', harness], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                dispatch = (root / 'dispatch').read_text()
                self.assertEqual(dispatch.count('Cap.Examples.BabyCdf.Live:' + script), 2)
                self.assertNotIn('requestIntake', dispatch)
                self.assertTrue(attempted.exists())
                self.assertIn('ledger proof', result.stderr)
                self.assertNotIn('run down/up', result.stderr)
                self.assertEqual((root / f'step-{step}.json').read_text(), '"PENDING"')


if __name__ == '__main__':
    unittest.main()
