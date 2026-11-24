#!/usr/bin/env python3
import importlib.util,pathlib,unittest,json,subprocess,tempfile,os
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('fmt',HERE/'format-log.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
class Labels(unittest.TestCase):
 def testAllSevenExactCaseAndCollisionFallback(self):
  mapping={'member-a':'jonathan-ieu','member-b':'heslin-zenith','member-c':'shaul-da','member-d':'ali-abrar','member-e':'EduardoFromTheFuture','alternate-f':'Jatinp26','alternate-g':'alexatdrw'}
  for role,handle in mapping.items():self.assertEqual(handle,fmt.display_roles(role))
  for unknown in ['member-aa','xmember-a','member-a-123','member-a_suffix','alternate-g2','Member-a','unknown-person']:self.assertEqual(unknown,fmt.display_roles(unknown))
 def testNarrationOnlyRawAndTechnicalRemainOriginal(self):
  business='[DA.Internal.Prelude:557]: '+json.dumps('[STEP 2][VOTE] member-a: YES (core)')
  action='[DA.Internal.Prelude:557]: '+json.dumps('[STEP 2][ACTION] member-a exercises CastAgendaVote')
  self.assertIn('jonathan-ieu',fmt.render(business,False));self.assertIn('member-a',fmt.render(action,True))
  text=business+'\n'+action+'\n'
  with tempfile.TemporaryDirectory() as tmp:
   raw=pathlib.Path(tmp)/'raw';p=subprocess.run(['python3',str(HERE/'format-log.py'),'--raw',str(raw)],input=text,text=True,capture_output=True);self.assertEqual(text,raw.read_text());self.assertIn('jonathan-ieu',p.stdout)
 def testUnknownDiagnosticsAndPartyIdsAreUnchanged(self):
  for message in ["[UNKNOWN] UNIQUE_A member-a::1220 failure", "UNIQUE_B alternate-f::1220 permission denied", "[STEP 2][VOTE] member-a: YES (core)\nUNIQUE_C member-a::1220 assertion failure"]:
   line="[DA.Internal.Prelude:557]: "+json.dumps(message);out=fmt.render(line,False);self.assertIn("member-a::1220" if "member-a::" in message else "alternate-f::1220",out);self.assertNotIn("jonathan-ieu",out);self.assertNotIn("Jatinp26",out)
  for role,handle in fmt.DISPLAY_HANDLES.items():self.assertIn(handle,fmt.render("[DA.Internal.Prelude:557]: "+json.dumps("[STEP 2][VOTE] "+role+": ABSENT (no cast; not an abstention, not a NO)"),False))
 def testRealSetupCommitteeSubprocessAllSeven(self):
  payload='[STEP 1][EVIDENCE] [SETUP-SUMMARY] COMMITTEE: opener demo-opener; core ["member-a","member-b","member-c","member-d","member-e"]; alternates ["alternate-f","alternate-g"]'
  text='[DA.Internal.Prelude:557]: '+json.dumps(payload)+'\n'
  with tempfile.TemporaryDirectory() as tmp:
   raw=pathlib.Path(tmp)/'raw';p=subprocess.run(['python3',str(HERE/'format-log.py'),'--raw',str(raw)],input=text,text=True,capture_output=True,env=dict(os.environ,DEMO_SETUP_FILTER='1'))
   self.assertEqual(0,p.returncode,p.stderr);self.assertEqual(text,raw.read_text())
   for role,handle in fmt.DISPLAY_HANDLES.items():self.assertIn(handle,p.stdout);self.assertNotIn(role,p.stdout)
   self.assertIn('Voting members',p.stdout);self.assertIn('Alternates',p.stdout)
 def testRoleTerms(self):self.assertEqual('; Voting members jonathan-ieu; Alternates Jatinp26',fmt.display_roles('; core member-a; alternates alternate-f'))
if __name__=='__main__':unittest.main()
