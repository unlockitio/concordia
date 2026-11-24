#!/usr/bin/env python3
"""Offline setup display contract; no ledger."""
import json,os,pathlib,subprocess,tempfile,unittest
HERE=pathlib.Path(__file__).resolve().parent
class SetupSummary(unittest.TestCase):
 def invoke(self,lines,verbose=False):
  with tempfile.TemporaryDirectory() as tmp:
   raw=pathlib.Path(tmp)/'raw.log';args=['python3',str(HERE/'format-log.py'),'--raw',str(raw),'--hide-sdk-info']+(['--verbose'] if verbose else [])
   text='\n'.join(lines)+'\n';p=subprocess.run(args,input=text,text=True,capture_output=True,env=dict(os.environ,DEMO_SETUP_FILTER='1'));return p,text,raw.read_text()
 def test_seven_summary_lines_plus_raw_path_budget_and_complete_raw(self):
  summaries=['SANDBOX: synthetic; no Foundation authority.','COMMITTEE: opener organiser; Voting members [a,b,c,d,e]; Alternates [f,g]','CREDENTIALS: 7 invitations accepted; direct 0; registered 7; validity 12h; 1 vote each.','RULEBOOK: architecture-review v1 active; UNVOTED sandbox seed; step 2 first voted.','ACCESS: Reviewer CanReadAs opener; Intake CanActAs opener.','SETUP: governor 1; registry 1 ready.','INITIAL PENDING: topics 0; vote records 0.']
  lines=['[DA.Internal.Prelude:1]: '+json.dumps('[STEP 1][ACTION] secret technical CID 00abc')]+['[DA.Internal.Prelude:2]: '+json.dumps('[STEP 1][EVIDENCE] [SETUP-SUMMARY] '+s) for s in summaries]
  p,text,raw=self.invoke(lines);self.assertEqual(0,p.returncode);self.assertEqual([s.partition(': ')[2] for s in summaries],[line for line in p.stdout.splitlines() if not line.startswith("##########")]);self.assertEqual(text,raw);self.assertNotIn('00abc',p.stdout);self.assertEqual(14,len(p.stdout.splitlines()))
 def test_failure_visible_verbose_and_postsetup_unchanged(self):
  lines=['[DA.Internal.Prelude:1]: '+json.dumps('[STEP 1][ACTION] create CID 00abc'),'ERROR: permission denied','[DA.Internal.Prelude:2]: '+json.dumps('Assertion failed: rights mismatch')]
  p,text,raw=self.invoke(lines);self.assertIn('ERROR: permission denied',p.stdout);self.assertIn('Assertion failed',p.stdout);self.assertEqual(text,raw)
  p,_,_=self.invoke(lines,True);self.assertIn('00abc',p.stdout)
 def test_unknown_decoded_payloads_are_never_silently_dropped(self):
  messages=['[STEP 1][RESULT] permission denied','[STEP 1][EVIDENCE] warning: incomplete visibility','[STEP 1][BUSINESS] assertion failure: expected rights','[STEP 1][BUSINESS] All 7 credentialed, 1 vote each.\npermission denied','[STEP 1][EVIDENCE] Parties known: 8; ledger users: 2\nwarning: incomplete visibility','permission denied','assertion failure: expected rights','warning: incomplete visibility','[UNKNOWN] provider status','[STEP 1][UNKNOWN] permission denied','[STEP 2][BUSINESS] postsetup unchanged']
  lines=['[DA.Internal.Prelude:2]: '+json.dumps(m) for m in messages]+['[DA.Internal.Prelude:x]: malformed wrapper','[DA.Internal.Prelude:2]: not-a-string']
  p,text,raw=self.invoke(lines)
  for expected in ['permission denied','assertion failure','warning: incomplete','[UNKNOWN] provider status','postsetup unchanged','malformed wrapper','not-a-string']:self.assertIn(expected,p.stdout)
  self.assertEqual(text,raw)
 def test_sections_have_content_fixed_width_and_no_escaped_names(self):
  summaries=['SANDBOX: synthetic only','COMMITTEE: opener organiser; core ["a","b","c","d","e"]; alternates ["f","g"]','CREDENTIALS: 7 registered; 0 direct; validity 12h','RULEBOOK: v1 UNVOTED; step2 first voted','ACCESS: Reviewer user readonly; Intake user opener-only','SETUP: governor ready; registry ready','INITIAL PENDING: topics 0; records 0']
  lines=['[DA.Internal.Prelude:1]: '+json.dumps('[STEP 1][PHASE] \\n########## INITIAL STATE ##########')]+['[DA.Internal.Prelude:2]: '+json.dumps('[STEP 1][EVIDENCE] [SETUP-SUMMARY] '+s) for s in summaries]
  p,text,raw=self.invoke(lines);out=p.stdout.splitlines();self.assertEqual(14,len(out));self.assertEqual(text,raw)
  for i in range(0,len(out),2):self.assertEqual(70,len(out[i]));self.assertTrue(out[i+1].strip());self.assertFalse(out[i+1].startswith('#'))
  self.assertNotIn('\\\\n',p.stdout);self.assertNotIn('\\\\"',p.stdout);self.assertIn('["a"',p.stdout)
 def test_runtime_counts_not_fixed_summary_claims(self):
  live=(HERE/'daml/Cap/Examples/BabyCdf/Live.daml').read_text();self.assertIn('show (length credentials)',live);self.assertIn('(_,DirectRight _) <- credentials',live);self.assertIn('(_,RegisteredRight _) <- credentials',live);self.assertIn('show e.records',live)
if __name__=='__main__':unittest.main()
