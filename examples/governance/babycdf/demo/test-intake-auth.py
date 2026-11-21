#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline auth wiring guards. No Docker daemon, credentials, or external requests."""
import pathlib
import os

def clean_env():
    env = os.environ.copy()
    for key in ('INTAKE_GITHUB_TOKEN_FILE', 'LLM_API_KEY_FILE', 'BABYCDF_LLM_ENABLED', 'INTAKE_DISCOVERY_MODE'):
        env.pop(key, None)
    return env

import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent


class AuthWiring(unittest.TestCase):
    def test_runner_selects_overlay_only_for_explicit_absolute_path(self):
        text = (HERE / 'run-demo.sh').read_text()
        start = text.index('compose() {')
        end = text.index('\n}', start) + 2
        function = text[start:end]
        # Exercise the actual runner function with shell stubs, never Docker.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            pinned = path / 'compose/.cips/8e36c20e5106014efb8348f749d9535afbc48921/cn-credentials-standard/demo'
            pinned.mkdir(parents=True)
            (pinned / 'compose.yaml').touch()
            script = 'set -eu\nHERE="$1"\nCOMPOSE="$HERE/compose/compose.yaml"\ndocker() { printf "<%s>\\n" "$@"; }\n' + function + '\ncompose config\n'
            env = clean_env()
            result = subprocess.run(['sh', '-c', script, 'stub', tmp], env=env, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn('intake-auth.yaml', result.stdout)
            env['INTAKE_GITHUB_TOKEN_FILE'] = str(path / 'synthetic-not-read')
            result = subprocess.run(['sh', '-c', script, 'stub', tmp], env=env, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('intake-auth.yaml', result.stdout)
            self.assertNotIn('synthetic-not-read', result.stdout)
            env['INTAKE_GITHUB_TOKEN_FILE'] = 'relative'
            result = subprocess.run(['sh', '-c', script, 'stub', tmp], env=env, text=True, capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertNotIn('<compose>', result.stdout)

    def test_up_preflight_precedes_every_side_effect(self):
        text = (HERE / 'run-demo.sh').read_text()
        start = text.index('  up)\n') + len('  up)\n')
        stop = text.index('    # Shared contract', start)
        guard = text[start:stop]
        self.assertLess(stop, text.index('babycdf_build_root "$HERE/.."', start))
        for marker in ('up_stage cips', 'rm -f --', 'up_stage agents'):
            self.assertLess(stop, text.index(marker, start))
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            valid = root / 'synthetic'; valid.write_text('synthetic-not-a-credential\n')
            unreadable = root / 'unreadable'; unreadable.write_text('synthetic\n'); unreadable.chmod(0)
            link = root / 'link'; link.symlink_to(valid)
            state = root / 'state'; state.write_text('untouched')
            script = ('set -eu\nrm() { echo forbidden-rm; exit 99; }\n'
                      'docker() { echo forbidden-docker; exit 99; }\n'
                      'up_stage() { echo forbidden-stage; exit 99; }\n' + guard + '\nprintf passed')
            env = clean_env()
            cases = [None, 'relative', str(root / 'missing'), str(root), str(link)]
            if not os.access(unreadable, os.R_OK):
                cases.append(str(unreadable))
            else:
                print('NOTE: privileged runner can read mode-000; unreadable-path case not established')
            try:
                for value in cases:
                    current = dict(env)
                    if value is not None:
                        current['INTAKE_GITHUB_TOKEN_FILE'] = value
                    result = subprocess.run(['sh', '-c', script], env=current, text=True, capture_output=True)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertEqual('', result.stdout)
                    self.assertIn('GITHUB-TOKEN.md', result.stderr)
                    self.assertNotIn(tmp, result.stderr)
                    self.assertEqual('untouched', state.read_text())
                    self.assertFalse((root / 'step-3.attempted').exists())
                for current in (dict(env, INTAKE_DISCOVERY_MODE='fixture'),
                                dict(env, INTAKE_GITHUB_TOKEN_FILE=str(valid))):
                    result = subprocess.run(['sh', '-c', script], env=current, text=True, capture_output=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual('passed', result.stdout)
            finally:
                unreadable.chmod(0o600)

    def test_secret_is_intake_only_file_not_environment_value(self):
        overlay = (HERE / 'compose/intake-auth.yaml').read_text()
        self.assertIn('INTAKE_GITHUB_TOKEN_FILE: /run/secrets/intake_github_token', overlay)
        self.assertIn('file: ${INTAKE_GITHUB_TOKEN_FILE:?', overlay)
        self.assertNotIn('advisory:', overlay)
        self.assertNotIn('reviewer:', overlay)
        self.assertNotIn('GITHUB_TOKEN:', overlay)
        self.assertNotIn('LLM_API_KEY', overlay)
        self.assertNotIn('mode:', overlay)  # file-secret mode remapping is not supported


if __name__ == '__main__':
    unittest.main()
