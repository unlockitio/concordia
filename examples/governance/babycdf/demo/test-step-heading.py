#!/usr/bin/env python3
import importlib.util,pathlib,json,unittest,subprocess,tempfile,os
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('fmt',HERE/'format-log.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
class Heading(unittest.TestCase):
 def testExactPlainAndQuotedStepTitles(self):
  for title in ['STEP 1: SETUP AND CREDENTIALS','STEP 2: RULE GOVERNANCE','STEP 3: PROPOSAL','STEP 4: MILESTONE','STEP 5: COUPON ALLOCATION','INITIAL STATE','VOTING','RESOLUTION']:
   raw='\\n################ '+title+' ################';wrapped='[DA.Internal.Prelude:557]: '+json.dumps('[STEP 1][PHASE] '+raw)
   for source in [raw,wrapped]:
    out=fmt.render(source,False);self.assertEqual(fmt.heading(title),out.strip('\n'));self.assertNotIn('\\n',out);self.assertEqual(70,len(out.strip('\n')))
 def testMalformedAndMultilineDiagnosticNotUnescaped(self):
  for source in ['\\n#### bad # heading ####','\\n#### TITLE ####\\npermission denied','not heading \\n permission denied','[DA.Bad:x]: "\\n#### TITLE ####"']:
   self.assertEqual(source,fmt.render(source,False))
 def testConsoleOnlyRawAndVerboseKeepDiagnostic(self):
  text='\\n################ STEP 1: SETUP AND CREDENTIALS ################\nwarning: incomplete visibility\n'
  with tempfile.TemporaryDirectory() as tmp:
   raw=pathlib.Path(tmp)/'raw';p=subprocess.run(['python3',str(HERE/'format-log.py'),'--raw',str(raw),'--verbose'],input=text,text=True,capture_output=True)
   self.assertEqual(text,raw.read_text());self.assertNotIn('\\n',p.stdout);self.assertIn('warning: incomplete visibility',p.stdout)
if __name__=='__main__':unittest.main()
