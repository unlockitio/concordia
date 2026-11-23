#!/usr/bin/env python3
import importlib.util,pathlib,json,unittest
spec=importlib.util.spec_from_file_location('pages',pathlib.Path(__file__).with_name('http-acs-pages.py'));p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class Pages(unittest.TestCase):
 def request(self):return {'activeAtOffset':'900','eventFormat':{'filtersByParty':{'opener':{'cumulative':[]}},'verbose':True}}
 def test816samefilteropaqueShortEmptyContinuation(self):
  seen=[];tokens=[None,'AA+/== opaque','token2','token3','token4','token5'];sizes=[200,1,0,200,200,215]
  def fetch(url,body,timeout):
   i=len(seen);self.assertTrue(url.endswith('/v2/state/active-contracts-page'));self.assertEqual('900',body['activeAtOffset']);self.assertEqual(self.request()['eventFormat'],body['eventFormat']);self.assertEqual(200,body['maxPageSize']);self.assertEqual(tokens[i],body.get('pageToken'));seen.append(body);row={'activeContracts':[{'contractEntry':{'JsActiveContract':{'createdEvent':{'contractId':str(sum(sizes[:i])+n)}}}} for n in range(sizes[i])],'activeAtOffset':'900'}
   if i+1<len(tokens):row['nextPageToken']=tokens[i+1]
   return json.dumps(row).encode()
  self.assertEqual(816,len(p.collect('http://same',self.request(),fetch)));self.assertEqual(6,len(seen))
 def testPageBoundAndSecondPageFailureNeverReturnsPartial(self):
  old=p.MAX_PAGES;p.MAX_PAGES=2
  try:
   i=[0]
   def endless(u,b,t):i[0]+=1;return json.dumps({'activeContracts':[],'activeAtOffset':'900','nextPageToken':str(i[0])}).encode()
   with self.assertRaises(ValueError):p.collect('http://same',self.request(),endless)
  finally:p.MAX_PAGES=old
  calls=[0]
  def fails(u,b,t):
   calls[0]+=1
   if calls[0]==2:raise ValueError('HTTP 413')
   return b'{"activeContracts":[],"activeAtOffset":"900","nextPageToken":"opaque"}'
  with self.assertRaises(ValueError):p.collect('http://same',self.request(),fails)
 def testTerminalAndContinuingResponseCrossDeadlineReject(self):
  for continuing in (False,True):
   now=[0.0];timeouts=[]
   def fetch(u,b,timeout):
    timeouts.append(timeout);now[0]=121
    page={'activeContracts':[],'activeAtOffset':'900'}
    if continuing:page['nextPageToken']='next'
    return json.dumps(page).encode()
   with self.assertRaises(ValueError):p.collect('http://same',self.request(),fetch,lambda:now[0])
   self.assertEqual([15],timeouts)
  ticks=iter([0,119,119,119,121])
  with self.assertRaises(ValueError):p.collect('http://same',self.request(),lambda u,b,t:b'{"activeContracts":[],"activeAtOffset":"900"}',lambda:next(ticks))
 def testDuplicateKeysRejected(self):
  with self.assertRaises(ValueError):p.collect('http://same',self.request(),lambda u,b,t:b'{"activeContracts":[],"activeAtOffset":"900","activeAtOffset":"900"}')
 def testErrorsOffsetsCycleBoundsNoPartialReturn(self):
  for page in [{'error':'413'},{'activeContracts':[],'activeAtOffset':'901'},{'activeContracts':{},'activeAtOffset':'900'},{'activeContracts':[],'activeAtOffset':'900','nextPageToken':''}]:
   with self.assertRaises(ValueError):p.collect('http://same',self.request(),lambda u,b,t:json.dumps(page).encode())
  with self.assertRaises(ValueError):p.collect('http://same',self.request(),lambda u,b,t:b'{"activeContracts":[],"activeAtOffset":"900","nextPageToken":"same"}')
  old=p.MAX_BYTES;p.MAX_BYTES=1
  try:
   with self.assertRaises(ValueError):p.collect('http://same',self.request(),lambda u,b,t:b'{}')
  finally:p.MAX_BYTES=old
if __name__=='__main__':unittest.main()
