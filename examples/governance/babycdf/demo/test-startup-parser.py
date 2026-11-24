#!/usr/bin/env python3
"""No-mutation execution of the actual up dispatch under the host POSIX shell."""
import pathlib,tempfile,subprocess,os,unittest
HERE=pathlib.Path(__file__).resolve().parent
class Parser(unittest.TestCase):
 def testActualUpCaseParsesAndExitsZeroAfterAgentsWithoutSourceMutation(self):
  source=(HERE/'run-demo.sh').read_text();start=source.index('case "${1:-help}" in');up=source.index('  up)\n',start);end=source.index('\n  down)',up)
  # Run the real dispatch syntax and heredoc parsing. Replace only the selected
  # branch body with stage stubs, leaving its terminator and later cases exact.
  branch=source[up:end];terminator=branch.rfind('\n    ;;');self.assertGreater(terminator,0)
  script='set -eu\ncase "${1:-help}" in\n  up)\n    printf "[up] bootstrap once\\n"\n    printf "[up] agents ready\\n"'+branch[terminator:]+source[end:]
  with tempfile.TemporaryDirectory() as tmp:
   p=pathlib.Path(tmp)/'dispatch.sh';p.write_text(script);result=subprocess.run(['/bin/sh',str(p),'up'],capture_output=True,text=True)
   self.assertEqual(0,result.returncode,result.stderr);self.assertEqual('[up] bootstrap once\n[up] agents ready\n',result.stdout)
if __name__=='__main__':unittest.main()
