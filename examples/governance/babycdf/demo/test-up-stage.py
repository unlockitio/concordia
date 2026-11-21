#!/usr/bin/env python3
"""Offline tests: extract real capture/renderer functions; never run up/Docker."""
import os
import pathlib
import re
import subprocess
import tempfile

here = pathlib.Path(__file__).resolve().parent
base = os.environ.get('BABYCDF_BUILD_ROOT')
if not base or not pathlib.Path(base).is_dir():
    raise SystemExit('Set BABYCDF_BUILD_ROOT to existing approved temporary directory')
work = pathlib.Path(tempfile.mkdtemp(prefix='quiet-up-', dir=base))
runner = (here / 'run-demo.sh').read_text()
functions = '\n'.join(re.search(r'^' + name + r'\(\) \{.*?^\}', runner, re.M | re.S).group(0)
                      for name in ('raw_log_path', 'render_step'))
harness = work / 'harness.sh'
harness.write_text('set -eu\nHERE="$1"; RAW_DIR="$2"; shift 2\n' + functions +
                   '\n. "$HERE/up-stage.sh"\n"$@"\n')
passed = 0

def run(args, raw='raw', verbose='0'):
    return subprocess.run(['sh', str(harness), str(here), str(work / raw)] + args,
                          env=dict(os.environ, VERBOSE=verbose), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

def check(condition, label):
    global passed
    assert condition, label
    passed += 1
    print('PASS:', label)

r = run(['up_stage', 'pass', 'Preflight checks', 'sh', '-c', 'echo hidden-one; echo hidden-two >&2'])
check(r.returncode == 0 and r.stdout.splitlines() == ['[up] Preflight checks ... ok'], 'quiet passing stage exactly one status line')
check((work / 'raw/up-pass.log').read_text() == 'hidden-one\nhidden-two\n', 'full stdout/stderr captured')
next_marker = work / 'next-marker'
sequence = work / 'sequence.sh'
sequence.write_text('set -eu\nHERE="$1"; RAW_DIR="$2"\n' + functions + '\n. "$HERE/up-stage.sh"\n' +
                    'up_stage fail "Failing stage" sh -c \'i=1; while [ "$i" -le 40 ]; do echo "detail-$i"; i=$((i+1)); done; exit 7\'\n' +
                    'touch "' + str(next_marker) + '"\n')
r = subprocess.run(['sh', str(sequence), str(here), str(work / 'raw')], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
check(r.returncode == 7, 'exit 7 propagated under POSIX sh')
check('detail-40' in r.stdout and 'detail-1\n' not in r.stdout and 'Full stage log:' in r.stdout, 'failure prints last 30 lines and path')
check('... ok' not in r.stdout and not next_marker.exists(), 'failure no ok and next stage never runs')
blocked = work / 'blocked'
blocked.write_text('not a directory')
marker = work / 'command-marker'
r = run(['up_stage', 'blocked', 'Capture', 'touch', str(marker)], raw='blocked')
check(r.returncode == 2 and not marker.exists(), 'capture refusal precedes command')
r = run(['up_stage', 'verbose', 'Verbose stage', 'sh', '-c', 'echo full-output'], verbose='1')
check(r.returncode == 0 and 'full-output' in r.stdout, 'VERBOSE=1 shows full output')
check('DEMO_VERBOSE=1 DEMO_SETUP_FILTER=1 render_step step-1' in runner and '--script-name Cap.Examples.BabyCdf.Live:bootstrap' in runner, 'bootstrap uses step-1 renderer with full actions')
script = work / 'setup.sh'
script.write_text('echo "2026-11-19 13:32:00.81 [INFO]  [multi-package build] SDK startup"\necho \'[STEP 1][PHASE] ########## INITIAL STATE ##########\'\necho \'[STEP 1][ACTION] allocate party\'\necho \'[STEP 1][EVIDENCE] scoped query\'\necho \'[STEP 1][PHASE] ########## FINAL STATE ##########\'\n')
env = dict(os.environ, DEMO_SETUP_FILTER='1', DEMO_VERBOSE='1', VERBOSE='0')
r = subprocess.run(['sh', str(harness), str(here), str(work/'render'), 'render_step', 'step-1', 'sh', str(script)], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
check(r.returncode == 0 and 'SDK startup' not in r.stdout and all(x in r.stdout for x in ('INITIAL STATE','FINAL STATE','allocate party','scoped query')), 'setup renders banners/actions/evidence while hiding SDK INFO')
check('SDK startup' in (work/'render/step-1.log').read_text(), 'setup raw log retains SDK INFO')
r = subprocess.run(['python3', str(here/'format-log.py'), '--hide-sdk-info'], input='ERROR real failure\nWARNING attention\nordinary output\n', text=True, stdout=subprocess.PIPE)
check(all(x in r.stdout for x in ('ERROR real failure','WARNING attention','ordinary output')), 'filter retains errors warnings and unknown output')
# Severity is determined only by a supported prefix, never message contents.
preserved = ('ERROR failure mentions [INFO] and INFO com.x\n'
             'WARNING warning mentions [INFO] and INFO com.x\n'
             '2026-11-19 13:32:00.81 [WARN] [build] mentions [INFO]\n'
             '13:32:00.810 [main] ERROR com.digitalasset.App - INFO com.x\n'
             'unknown [INFO] INFO com.x\n')
infos = ('2026-11-19 13:32:00.81 [INFO]  [multi-package build] SDK info\n'
         '13:32:00.810 [main] INFO com.digitalasset.App - JVM info\n'
         'INFO com.digitalasset.App - plain JVM info\n')
r = subprocess.run(['python3', str(here/'format-log.py'), '--hide-sdk-info'], input=preserved+infos, text=True, stdout=subprocess.PIPE)
check(r.returncode == 0 and r.stdout == preserved, 'anchored INFO filtering preserves ERROR/WARNING message tokens and unknown formats')
r = subprocess.run(['sh', str(harness), str(here), str(work/'verbose-render'), 'render_step', 'step-1', 'sh', str(script)], env=dict(env, VERBOSE='1'), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
check(r.returncode == 0 and 'SDK startup' in r.stdout, 'VERBOSE=1 setup shows supported INFO prefix')
# Force verbose replay to fail; tail and path must still be attempted.
bin_dir = work/'bin'
bin_dir.mkdir()
cat_stub = bin_dir/'cat'
cat_stub.write_text('#!/bin/sh\nexit 9\n')
cat_stub.chmod(0o755)
r = subprocess.run(['sh', str(harness), str(here), str(work/'replay-fail'), 'up_stage', 'failure', 'Failure with broken replay', 'sh', '-c', 'echo original-stage-detail; exit 7'], env=dict(os.environ, VERBOSE='1', PATH=str(bin_dir)+os.pathsep+os.environ['PATH']), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
check(r.returncode == 7 and '... ok' not in r.stdout, 'failed verbose replay cannot replace stage exit 7')
check('original-stage-detail' in r.stdout and 'Full stage log:' in r.stdout and 'FAILED (exit 7)' in r.stdout, 'tail and path still attempted after replay failure')
print(f'{passed}/{passed} passed; artifacts {work}')
print('SAMPLE QUIET UP — STUBS ONLY, NOT LIVE')
for key, title in [('checks','Preflight checks'),('build','Building BabyCDF DARs'),('canton','Starting Canton'),('readback','Publishing verified readback'),('agents','Starting read-only agents')]:
    r = run(['up_stage', 'sample-'+key, title, 'sh', '-c', 'echo full hidden stub stage output'])
    print(r.stdout, end='')
print('Step-1 renderer sample:')
r = subprocess.run(['sh', str(harness), str(here), str(work/'sample-render'), 'render_step', 'step-1', 'sh', str(script)], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(r.stdout, end='')
