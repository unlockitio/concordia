#!/usr/bin/env python3
"""Offline packaging contract: stub Java, synthetic IDs, never starts containers."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

def clean_env(**overrides):
    env = os.environ.copy()
    for key in ('INTAKE_GITHUB_TOKEN_FILE', 'LLM_API_KEY_FILE', 'BABYCDF_LLM_ENABLED'):
        env.pop(key, None)
    env.update(overrides)
    return env

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('publish', HERE / 'publish-readback.py')
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)

class IntakePackaging(unittest.TestCase):
    def test_publication_and_actual_entrypoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root/'bootstrap.json').write_text(json.dumps(dict(opener='opener',governor='gov',userName='babycdf-agent',verifiedRights=['read'],intakeUserName='babycdf-agent-intake',intakeVerifiedRights=['act'])))
            (root/'impl.json').write_text(json.dumps(dict(main_package_id='a'*64)))
            (root/'demo.json').write_text(json.dumps(dict(main_package_id='b'*64)))
            publish.publish(root,'41000',root/'impl.json',root/'demo.json')
            self.assertEqual(5,len((root/'readback').read_text().split()))
            self.assertEqual('b'*64+'\n',(root/'intake-demo-package').read_text())
            (root/'journal').mkdir()
            (root/'java').write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            (root/'java').chmod(0o755)
            env=clean_env(PATH=str(root)+os.pathsep+os.environ['PATH'],READBACK_FILE=str(root/'readback-intake'),INTAKE_PACKAGE_FILE=str(root/'intake-demo-package'),INTAKE_JOURNAL=str(root/'journal'))
            result=subprocess.run(['sh',str(HERE/'compose/agents/entrypoint.sh'),'intake'],env=env,text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr)
            args=result.stdout.splitlines()
            self.assertEqual('-Djava.util.logging.config.file=/app/classes/logging.properties',args[0])
            self.assertEqual(['org.concordia.babycdf.Intake','--integrated-demo','6865','opener','a'*64,'b'*64,'gov','babycdf-agent-intake',str(root/'journal'),'86400','1000'],args[3:])
            self.assertNotIn('--retry-rejected',args)
            (root/'intake-demo-package').write_text('BAD\n')
            bad=subprocess.run(['sh',str(HERE/'compose/agents/entrypoint.sh'),'intake'],env=env,text=True,capture_output=True)
            self.assertEqual(2,bad.returncode); self.assertNotIn('org.concordia',bad.stdout)
    def test_entrypoint_negative_table_never_starts_java(self):
        cases=['missing-meta','symlink-meta','uppercase','short','two-lines','bad-shape','wrong-user','missing-journal','symlink-journal','unwritable-journal']
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp); journal=root/'journal'; journal.mkdir()
                meta=root/'meta'; meta.write_text('b'*64+'\n')
                readback=root/'readback'; readback.write_text('41000 opener '+'a'*64+' gov babycdf-agent-intake\n')
                (root/'java').write_text('#!/bin/sh\necho JAVA_STARTED\n'); (root/'java').chmod(0o755)
                if case=='missing-meta': meta=root/'missing'
                elif case=='symlink-meta':
                    link=root/'link'; link.symlink_to(meta); meta=link
                elif case=='uppercase': meta.write_text('B'*64+'\n')
                elif case=='short': meta.write_text('b'*63+'\n')
                elif case=='two-lines': meta.write_text(('b'*64+'\n')*2)
                elif case=='bad-shape': meta.write_text('b'*64+' extra\n')
                elif case=='wrong-user': readback.write_text('41000 opener '+'a'*64+' gov reader\n')
                elif case=='missing-journal': journal=root/'missing'
                elif case=='symlink-journal':
                    link=root/'jlink'; link.symlink_to(journal); journal=link
                elif case=='unwritable-journal':
                    journal.chmod(0o500)
                    self.assertFalse(os.access(journal,os.W_OK),'test requires non-root permission probe')
                env=clean_env(PATH=str(root)+os.pathsep+os.environ['PATH'],READBACK_FILE=str(readback),INTAKE_PACKAGE_FILE=str(meta),INTAKE_JOURNAL=str(journal))
                result=subprocess.run(['sh',str(HERE/'compose/agents/entrypoint.sh'),'intake'],env=env,text=True,capture_output=True)
                self.assertEqual(2,result.returncode,result.stderr); self.assertEqual('',result.stdout)
                if case=='unwritable-journal': journal.chmod(0o700)
    def test_invalid_demo_id_prevents_any_publication(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/'bootstrap.json').write_text(json.dumps(dict(opener='o',governor='g',userName='reader',verifiedRights=[1],intakeUserName='reader-intake',intakeVerifiedRights=[1])))
            (root/'impl.json').write_text(json.dumps(dict(main_package_id='a'*64)))
            (root/'demo.json').write_text(json.dumps(dict(main_package_id='BAD')))
            with self.assertRaises(ValueError): publish.publish(root,41000,root/'impl.json',root/'demo.json')
            self.assertFalse((root/'readback').exists())
    def test_missing_intake_refuses_before_attempt_and_project_is_fixed(self):
        runner=(HERE/'run-demo.sh').read_text()
        def function(name):
            start=runner.index(name+'() {'); end=runner.index('\n}',start)+2
            return runner[start:end]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'state').mkdir(); (root/'state/bootstrap.json').write_text('{}')
            harness=root/'harness.sh'
            harness.write_text('set -eu\n'+function('run_step')+'\nRAW_DIR=x\nSTATE="$1/state"\nHERE="$2"\ncompose() { return 0; }\nagent_log_summary() { :; }\npython3() { echo package-gate-reached; return 2; }\nrun_step 3\n')
            result=subprocess.run(['sh',str(harness),str(root),str(HERE)],text=True,capture_output=True)
            self.assertEqual(2,result.returncode); self.assertIn('package-gate-reached',result.stdout)
            self.assertFalse((root/'state/step-3.attempted').exists())
            # Invoke real compose helper against fake pinned path and stub docker.
            pinned=root/'compose/.cips/8e36c20e5106014efb8348f749d9535afbc48921/cn-credentials-standard/demo'
            pinned.mkdir(parents=True); (pinned/'compose.yaml').write_text('')
            (root/'docker').write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n'); (root/'docker').chmod(0o755)
            harness.write_text('set -eu\n'+function('compose')+'\nHERE="$1"\nCOMPOSE=synthetic\ncompose down --volumes\n')
            env=clean_env(PATH=str(root)+os.pathsep+os.environ['PATH'],COMPOSE_PROJECT_NAME='DO_NOT_TOUCH')
            result=subprocess.run(['sh',str(harness),str(root)],env=env,text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual(['compose','-p','babycdf-live','--profile','agents','-f','synthetic','down','--volumes'],result.stdout.splitlines())
    def test_discovery_failure_continues_to_package_gate(self):
        runner=(HERE/'run-demo.sh').read_text(); start=runner.index('run_step() {'); end=runner.index('\n}',start)+2
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'bootstrap.json').write_text('{}')
            harness=root/'h.sh'
            summary_start=runner.index('agent_log_summary() {'); summary_end=runner.index('\n}',summary_start)+2
            harness.write_text('set -eu\n'+runner[summary_start:summary_end]+'\n'+runner[start:end]+'\nRAW_DIR=x\nSTATE="$1"\nHERE="$2"\ncompose() { case "$*" in "ps --status running -q intake") echo running;; "logs --no-color intake") return 2;; *) exit 99;; esac; }\npython3() { echo package-gate-reached; return 2; }\nrun_step 3\n')
            result=subprocess.run(['sh',str(harness),str(root),str(HERE)],text=True,capture_output=True)
            self.assertEqual(2,result.returncode)
            self.assertIn('package-gate-reached',result.stdout)
            self.assertNotIn('scripted source steps are retired',result.stderr)
            self.assertFalse((root/'step-3.attempted').exists())
    def test_live_mode_environment_is_inherited_by_java(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'journal').mkdir()
            (root/'meta').write_text('b'*64+'\n')
            (root/'readback').write_text('41000 opener '+'a'*64+' gov babycdf-agent-intake\n')
            (root/'java').write_text('#!/bin/sh\nprintf "%s" "$INTAKE_DISCOVERY_MODE"\n'); (root/'java').chmod(0o755)
            env=clean_env(PATH=str(root)+os.pathsep+os.environ['PATH'],INTAKE_DISCOVERY_MODE='live',READBACK_FILE=str(root/'readback'),INTAKE_PACKAGE_FILE=str(root/'meta'),INTAKE_JOURNAL=str(root/'journal'))
            result=subprocess.run(['sh',str(HERE/'compose/agents/entrypoint.sh'),'intake'],env=env,text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr); self.assertEqual('live',result.stdout)
    def test_lifecycle_order_and_hardening_source(self):
        runner=(HERE/'run-demo.sh').read_text()
        self.assertLess(runner.index('up_stage impl-inspection'),runner.index('up_stage readback-publication'))
        self.assertLess(runner.index('up_stage readback-publication'),runner.index('up_stage agents'))
        self.assertIn('60 intake reviewer',runner)
        compose=(HERE/'compose/compose.yaml').read_text().split('  intake:\n')[1].split('  reviewer:\n')[0]
        for required in ['intake-journal:/journal','read_only: true','cap_drop: [ALL]','no-new-privileges:true','restart: "no"','network_mode: "service:canton"']:
            self.assertIn(required,compose)
        self.assertNotIn('LLM_API_KEY',compose)
        self.assertIn('compose down --volumes',runner)
        self.assertLess(runner.index('docker volume inspect babycdf-live_intake-journal'),runner.index('up_stage canton'))
        self.assertIn('"$STATE/readback-intake" "$STATE/intake-demo-package"',runner)
        self.assertNotIn('rm -rf',runner)
        dockerfile=(HERE/'../java/Dockerfile').read_text()
        self.assertIn('chown 65532:65532 /journal',dockerfile)
        self.assertIn('chmod 700 /journal',dockerfile)

if __name__=='__main__': unittest.main()
