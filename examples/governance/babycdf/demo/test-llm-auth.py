#!/usr/bin/env python3
"""Offline runner/overlay tests: synthetic paths, shell stubs, no Docker."""
import os
import pathlib
import subprocess
import tempfile
import unittest
HERE=pathlib.Path(__file__).resolve().parent
class LlmAuth(unittest.TestCase):
    def test_selection(self):
        text=(HERE/'run-demo.sh').read_text()
        start=text.index('compose() {'); end=text.index('\n}',start)+2
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            pinned=root/'compose/.cips/8e36c20e5106014efb8348f749d9535afbc48921/cn-credentials-standard/demo'
            pinned.mkdir(parents=True); (pinned/'compose.yaml').touch()
            script='set -eu\nHERE="$1"\nCOMPOSE="$HERE/compose/compose.yaml"\ndocker() { printf "<%s>\\n" "$@"; }\n'+text[start:end]+'\ncompose config\n'
            env={k:v for k,v in os.environ.items() if k not in ('LLM_API_KEY_FILE','BABYCDF_LLM_ENABLED','INTAKE_GITHUB_TOKEN_FILE')}
            for enabled,path,code,selected in [('0','relative',0,False),('1','relative',2,False),('1',str(root/'synthetic'),0,True)]:
                result=subprocess.run(['sh','-c',script,'stub',tmp],env=dict(env,BABYCDF_LLM_ENABLED=enabled,LLM_API_KEY_FILE=path),capture_output=True,text=True)
                self.assertEqual(code,result.returncode,result.stderr)
                self.assertEqual(selected,'llm-auth.yaml' in result.stdout)
                self.assertNotIn(path,result.stderr)
    def test_overlay_boundary(self):
        overlay=(HERE/'compose/llm-auth.yaml').read_text()
        self.assertNotIn('  intake:',overlay); self.assertNotIn('  canton:',overlay)
        self.assertEqual(2,overlay.count('LLM_API_KEY_FILE: /run/secrets/llm_api_key'))
        self.assertNotIn('LLM_API_KEY:',overlay)
if __name__=='__main__': unittest.main()
