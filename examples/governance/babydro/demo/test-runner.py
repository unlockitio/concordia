#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline CLI/Compose contract tests; no Docker daemon or network access."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent


class RunnerTests(unittest.TestCase):
    def invoke(self, command, containers='', version='5.6.1', pause='0', seed=None):
        with tempfile.TemporaryDirectory(dir=HERE.parent / '.build') as tmp:
            root = Path(tmp)
            log = root / 'calls'
            docker = root / 'docker'
            docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  'compose version --short') echo "$VERSION" ;;
  'ps -aq --filter label=com.docker.compose.project=babydro-live') printf '%s' "$CONTAINERS" ;;
esac
''')
            docker.chmod(0o755)
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}',
                       CALLS=str(log), CONTAINERS=containers, VERSION=version,
                       BABYDRO_STATE_DIR=str(root / 'state'))
            dar = root / 'fixture.dar'
            dar.touch()
            env['BABYDRO_DAR'] = str(dar)
            env['BABYDRO_PAUSE'] = pause
            if seed:
                story = root / 'state/story'
                story.mkdir(parents=True)
                (story / seed).write_text('{}')
            args = command if isinstance(command, list) else [command]
            result = subprocess.run(['sh', str(HERE / 'run-demo.sh'), *args],
                                    env=env, text=True, capture_output=True)
            return result, log.read_text() if log.exists() else ''

    def test_step_arguments(self):
        for args in [['step'], ['step', '0'], ['step', '7'], ['step', 'x'], ['step', '1', 'extra'], ['all', 'extra']]:
            result, calls = self.invoke(args)
            self.assertEqual(result.returncode, 2, args)
            self.assertEqual(calls, '')

    def test_state_output_and_input_roundtrip(self):
        with tempfile.TemporaryDirectory(dir=HERE.parent / '.build') as tmp:
            root = Path(tmp)
            docker = root / 'docker'
            docker.write_text("#!/bin/sh\ncase \"$*\" in 'compose version --short') echo 5.6.1;; 'ps -aq --filter label=com.docker.compose.project=babydro-live') echo running;; esac\n")
            docker.chmod(0o755)
            dpm = root / 'dpm'
            dpm.write_text('''#!/usr/bin/env python3
import json, sys
args = sys.argv
if '--input-file' in args:
    with open(args[args.index('--input-file') + 1]) as f:
        assert json.load(f) == {'ledgerReference': 'preserved'}
with open(args[args.index('--output-file') + 1], 'w') as f:
    json.dump({'ledgerReference': 'preserved'}, f)
''')
            dpm.chmod(0o755)
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}',
                       BABYDRO_STATE_DIR=str(root / 'state'))
            dar = root / 'fixture.dar'
            dar.touch()
            env['BABYDRO_DAR'] = str(dar)
            for n in [1, 2]:
                result = subprocess.run(['sh', str(HERE / 'run-demo.sh'), 'step', str(n)], env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual((root / f'state/story/step-{n}.json').read_text(), '{"ledgerReference": "preserved"}')
            result = subprocess.run(['sh', str(HERE / 'run-demo.sh'), 'step', '2'], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('already attempted', result.stderr)

    def test_missing_prerequisite(self):
        result, _ = self.invoke(['step', '3'], containers='existing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('run step 2 first', result.stderr)

    def test_reexecution_refused(self):
        result, _ = self.invoke(['step', '1'], containers='existing', seed='step-1.attempted')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already attempted', result.stderr)

    def test_all_refuses_partial_story(self):
        result, _ = self.invoke('all', containers='existing', seed='step-2.attempted')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('fresh story', result.stderr)

    def test_invalid_pause(self):
        for pause in ['-1', '0.1', 'no']:
            result, _ = self.invoke('all', pause=pause)
            self.assertEqual(result.returncode, 2)
            self.assertIn('Invalid BABYDRO_PAUSE', result.stderr)

    def test_unused_timing_options_do_not_block_operations(self):
        for action in ['down', 'status', 'logs']:
            with mock.patch.dict(os.environ, BABYDRO_READY_TIMEOUT='invalid'):
                result, _ = self.invoke(action, pause='invalid')
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_invalid_readiness_only_blocks_up(self):
        for timeout in ['0', '-1', 'no']:
            with mock.patch.dict(os.environ, BABYDRO_READY_TIMEOUT=timeout):
                result, _ = self.invoke('up')
            self.assertEqual(result.returncode, 2)
            self.assertIn('Invalid BABYDRO_READY_TIMEOUT', result.stderr)

    def test_defaults_compose(self):
        config = (HERE / 'compose/compose.yaml').read_text()
        self.assertIn('${BABYDRO_CANTON_MEM_LIMIT:-2g}', config)
        self.assertIn('${BABYDRO_JAVA_OPTS:--Xms256m -Xmx1g}', config)

    def test_story_preservation_and_all_failure(self):
        with tempfile.TemporaryDirectory(dir=HERE.parent / '.build') as tmp:
            root = Path(tmp)
            # Copy only runner inputs: no generated .daml tree is available.
            import shutil
            demo = root / 'demo'
            (demo / 'compose').mkdir(parents=True)
            shutil.copyfile(HERE / 'run-demo.sh', demo / 'run-demo.sh')
            for path in [demo / 'compose/prepare-cips.sh', root / 'prepare-dependencies.sh']:
                path.write_text('exit 0\n')
            (root / 'build-root.sh').write_text('babydro_build_root() { :; }\nbabydro_manifest_dir() { pwd; }\n')
            docker = root / 'docker'
            docker.write_text('#!/bin/sh\ncase "$*" in "compose version --short") echo 5.6.1;; "ps -aq"*) printf "%s" "$CONTAINERS";; esac\n')
            docker.chmod(0o755)
            dpm = root / 'dpm'
            dpm.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
if args[0] == 'build':
    sys.exit(0)
name = args[args.index('--script-name') + 1]
with open(os.environ['CALLS'], 'a') as f:
    f.write(name + '\\n')
if name.endswith('step2'):
    sys.exit(7)
Path(args[args.index('--output-file') + 1]).write_text('{}')
''')
            dpm.chmod(0o755)
            story = root / 'state/story'
            story.mkdir(parents=True)
            (story / 'old.json').write_text('old-ledger')
            dar = root / 'fixture.dar'
            dar.touch()
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}',
                       BABYDRO_STATE_DIR=str(root / 'state'), BABYDRO_DAR=str(dar),
                       BABYDRO_PAUSE='0', BABYDRO_READY_TIMEOUT='180', BABYDRO_BUILD_ROOT=str(root),
                       CALLS=str(root / 'calls'), CONTAINERS='')
            result = subprocess.run(['sh', str(demo / 'run-demo.sh'), 'up'], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            archives = list((root / 'state').glob('story-previous-*'))
            self.assertEqual(len(archives), 1)
            self.assertEqual((archives[0] / 'old.json').read_text(), 'old-ledger')
            self.assertFalse((story / 'old.json').exists())
            env['CONTAINERS'] = 'running'
            result = subprocess.run(['sh', str(demo / 'run-demo.sh'), 'all'], env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            calls = (root / 'calls').read_text()
            self.assertIn('Steps:step1', calls)
            self.assertIn('Steps:step2', calls)
            self.assertNotIn('Steps:step3', calls)
            self.assertTrue((story / 'step-2.attempted').exists())
            self.assertFalse((story / 'step-3.attempted').exists())

    def test_unknown_has_no_side_effects(self):
        result, calls = self.invoke('unknown')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, '')

    def test_help_offline(self):
        result, calls = self.invoke('help')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(calls, '')
        self.assertIn('step N', result.stdout)
        self.assertIn('6 Policy', result.stdout)

    def test_missing_step_is_offline(self):
        result, calls = self.invoke('step')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, '')

    def test_all_requires_running_project(self):
        result, _ = self.invoke('all')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('up first', result.stderr)

    def test_story_uses_separate_state_and_attempt_guard(self):
        runner = (HERE / 'run-demo.sh').read_text()
        self.assertIn('STATE="$STATE/story"', runner)
        self.assertIn('--input-file "$STATE/step-$previous.json"', runner)
        self.assertIn('already attempted', runner)
        self.assertIn('mv "$STATE/step-$n.next" "$STATE/step-$n.json"', runner)

    def test_duplicate_start_refused_before_preparation(self):
        result, calls = self.invoke('up', containers='existing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('already exist', result.stderr)
        self.assertNotIn('up -d', calls)

    def test_minimum_compose(self):
        for version in ['2.23.9', '2.24.3']:
            result, calls = self.invoke('status', version=version)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn(' ps\n', calls)
        result, _ = self.invoke('status', version='2.24.4')
        self.assertEqual(result.returncode, 0)

    def test_cleanup_scoped_without_volume_deletion(self):
        result, calls = self.invoke('down')
        self.assertEqual(result.returncode, 0)
        self.assertIn('compose -p babydro-live -f ', calls)
        self.assertIn('down --timeout 30', calls)
        self.assertNotIn('--volumes', calls)
        self.assertNotIn('babycdf', calls)

    def test_test_requires_running_project(self):
        result, _ = self.invoke('test')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('up first', result.stderr)

    def test_atomic_lock_refuses_overlapping_operation(self):
        with tempfile.TemporaryDirectory(dir=HERE.parent / '.build') as tmp:
            state = Path(tmp)
            (state / 'lifecycle.lock').mkdir()
            docker = state / 'docker'
            docker.write_text('#!/bin/sh\necho 5.6.1\n')
            docker.chmod(0o755)
            env = dict(os.environ, PATH=f'{state}:{os.environ["PATH"]}',
                       BABYDRO_STATE_DIR=str(state))
            result = subprocess.run(['sh', str(HERE / 'run-demo.sh'), 'status'],
                                    env=env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('already active', result.stderr)
            self.assertTrue((state / 'lifecycle.lock').is_dir())

    def test_invalid_port_refused_before_docker(self):
        with tempfile.TemporaryDirectory(dir=HERE.parent / '.build') as tmp:
            env = dict(os.environ, BABYDRO_LEDGER_PORT='not-a-port',
                       BABYDRO_STATE_DIR=tmp)
            result = subprocess.run(['sh', str(HERE / 'run-demo.sh'), 'up'],
                                    env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn('Invalid BABYDRO_LEDGER_PORT', result.stderr)

    def test_config_isolation(self):
        config = (HERE / 'compose/compose.yaml').read_text()
        self.assertIn('name: babydro-live', config)
        self.assertIn('127.0.0.1:${BABYDRO_LEDGER_PORT:-41100}:6865', config)
        self.assertEqual(config.count('!override'), 2)
        self.assertNotIn('babycdf', config)
        self.assertNotIn('agents:', config)

    def test_lifecycle_order_and_live_flags(self):
        runner = (HERE / 'run-demo.sh').read_text()
        positions = [runner.index(token) for token in
                     ['[ -z "$(containers)" ]', 'sh "$HERE/compose/prepare-cips.sh"',
                      'sh "$PACKAGE/prepare-dependencies.sh"', 'DAML_PACKAGE="$PACKAGE/impl"',
                      'DAML_PACKAGE="$HERE"', 'compose config --quiet',
                      'compose up -d --build --wait --wait-timeout']]
        self.assertEqual(positions, sorted(positions))
        self.assertIn('--wall-clock-time --upload-dar true', runner)
        self.assertNotIn('--all --ledger-host', runner)
        allowlist = runner.split('for script in ', 1)[1].split('; do', 1)[0].split()
        self.assertEqual(len(allowlist), 18)
        self.assertEqual(len(set(allowlist)), 18)
        self.assertIn('Cap.Examples.BabyDro.PrivacyProof:resolverProjection', runner)
        self.assertEqual(set(allowlist), set('journey keepSecond checkedCaseAuthority currentElectorate failedArchive conflicted motionIsolation withdrawalAndRecast foreignBallotRejected rightRoutes reAdmissionRetiresOldRight expiredRightRejected stateMismatchRejected nWayDispute nWayRollback ballotContentPrivacy resolverCannotForge genericInterfaces'.split()))
        self.assertNotIn('visibleSlots', allowlist)
        self.assertNotIn('step1', allowlist)


if __name__ == '__main__':
    (HERE.parent / '.build').mkdir(exist_ok=True)
    unittest.main()
