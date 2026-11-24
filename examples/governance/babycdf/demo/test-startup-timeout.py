#!/usr/bin/env python3
import pathlib,subprocess,tempfile,os,unittest
HERE=pathlib.Path(__file__).resolve().parent
class Startup(unittest.TestCase):
 def testInvalidTimeoutRefusesBeforeAnyDockerOrBootstrap(self):
  for value in ('abc','0','0179','179','1801','999999999999999999999999'):
   with tempfile.TemporaryDirectory() as tmp:
    p=pathlib.Path(tmp);(p/'docker').write_text('#!/bin/sh\necho DOCKER_CALLED\nexit 99\n');(p/'docker').chmod(0o755)
    result=subprocess.run(['sh',str(HERE/'run-demo.sh'),'up'],capture_output=True,text=True,env=dict(os.environ,PATH=str(p)+os.pathsep+os.environ['PATH'],BABYCDF_STARTUP_TIMEOUT=value))
    self.assertNotEqual(0,result.returncode);self.assertNotIn('DOCKER_CALLED',result.stdout);self.assertIn('BABYCDF_STARTUP_TIMEOUT',result.stderr)
 def testCustomTimeoutIsForwardedAndFailedReadinessNeverBootstraps(self):
  runner=(HERE/'run-demo.sh').read_text();line=next(x.strip() for x in runner.splitlines() if "up_stage canton" in x)
  for timeout in ('180','900'):
   script='set -eu\nup_stage(){ shift 2; "$@"; }\ncompose(){ printf "args=%s\\n" "$*"; return 7; }\n'+line+'\necho BOOTSTRAP_MUST_NOT_RUN\n'
   p=subprocess.run(['sh','-c',script],capture_output=True,text=True,env=dict(os.environ,BABYCDF_STARTUP_TIMEOUT=timeout));self.assertEqual(7,p.returncode);self.assertIn('--wait-timeout '+timeout,p.stdout);self.assertNotIn('BOOTSTRAP_MUST_NOT_RUN',p.stdout)
 def testStartupWaitAndHealthGraceShareBoundAndBootstrapIsLater(self):
  runner=(HERE/'run-demo.sh').read_text();compose=(HERE/'compose/compose.yaml').read_text();self.assertIn('--wait-timeout "${BABYCDF_STARTUP_TIMEOUT:-600}" canton',runner);self.assertIn('start_period: ${BABYCDF_STARTUP_TIMEOUT:-600}s',compose)
  self.assertLess(runner.index("up_stage canton"),runner.index('render_step step-1'));self.assertIn('set -eu',runner);self.assertNotIn('resume-bootstrap',runner)
if __name__=='__main__':unittest.main()
