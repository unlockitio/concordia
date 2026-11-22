#!/usr/bin/env python3
"""Private stub Docker only; no runtime access."""
from pathlib import Path
import os,subprocess,tempfile,re
here=Path(__file__).resolve().parent
runner=(here/'run-demo.sh').read_text()
def function(name):return re.search(r'^'+name+r'\(\) \{.*?^\}',runner,re.M|re.S).group(0)
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);docker=root/'docker';docker.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\n[ "$FAIL_LOG" != 1 ] || exit 7\ncat "$LOG"\n');docker.chmod(0o755)
    log=root/'log';calls=root/'calls';script=root/'h.sh'
    script.write_text('set -eu\nHERE="$1"\ncompose() { docker compose -p babycdf-live "$@"; }\n'+function('agent_log_summary')+'\nagent_log_summary "${2:-intake}"\nprintf "step continues\\n"\n')
    base=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],CALLS=str(calls),LOG=str(log),FAIL_LOG='0',TMPDIR=str(root))
    def run(text='',agent='intake',failed=False):
        log.write_text(text);calls.write_text('');p=subprocess.run(['sh',str(script),str(here),agent],env=dict(base,FAIL_LOG='1' if failed else '0'),capture_output=True,text=True);assert p.returncode==0,p.stderr;assert 'step continues' in p.stdout;assert '--tail' not in calls.read_text() and '--since' not in calls.read_text();assert 'intake-1 |' not in p.stdout;return p.stdout
    for module,code,category in [('sqlite3','raise ImportError("private-path")','sqlite_unavailable'),('sqlite3','raise OSError("private-path")','storage')]:
        shadow=root/'shadow';shadow.mkdir(exist_ok=True);(shadow/(module+'.py')).write_text(code)
        log.write_text('');calls.write_text('');p=subprocess.run(['sh',str(script),str(here),'intake'],env=dict(base,PYTHONPATH=str(shadow)),capture_output=True,text=True)
        assert p.returncode==0 and 'step continues' in p.stdout and 'summary_failed:'+category in p.stdout
        assert 'Traceback' not in p.stdout+p.stderr and 'private-path' not in p.stdout+p.stderr
    log.write_bytes(b'bad encoding \xff\n');p=subprocess.run(['sh',str(script),str(here),'intake'],env=base,capture_output=True,text=True);assert p.returncode==0 and 'step continues' in p.stdout and 'Traceback' not in p.stderr
    out=run('Proposal #999 added '+'x'*70000+'\n'+'x'*70000+' BLOCKED late error\n');assert '0 proposals added' in out and '0 errors' in out and 'oversized_skipped=2' in out and 'incomplete:' in out
    out=run('BLOCKED Bearer private github_pat_hidden token=secret \x1b[31m red \u202e\\n');assert all(s not in out for s in ('private','github_pat_hidden','token=secret','\x1b','\u202e'))
    mixed='\n'.join(['intake-1 | Proposal #1 added']*2+['intake-1 | Proposal #2 added','intake-1 | Milestone #1 added','intake-1 | Proposal #1 updated','intake-1 | Proposal #1 updated','intake-1 | Milestone #1 updated'])
    out=run(mixed);assert '2 proposals added, 1 milestones added, 2 distinct subjects updated, 0 errors' in out,out
    out=run('\n'.join('intake-1 | Source discovery paused: failure '+str(i) for i in range(8)));assert out.count('intake error:')==5 and '3 additional distinct' in out
    assert '0 proposals added, 0 milestones added, 0 distinct subjects updated, 0 errors' in run()
    assert 'summary unavailable (log_read_failed)' in run(failed=True)
    # Every step dispatch uses the same helper only where a raw dump existed.
    step=function('run_step'); optional=function('run_opt_step')
    assert 'agent_log_summary intake' in step and 'logs --no-color --tail' not in runner
    assert 'compose logs' not in optional
    for n in range(2,6):
        assert f'{n}) NUMBER={n};' in step
    for n in range(1,6):
        assert f'{n}) OPT={n};' in optional
    # The shared helper is informational even when invoked by either step 3/4.
    for n in (3,4):
        assert 'summary unavailable (log_read_failed)' in run(failed=True)
    assert 'client report follows' not in runner
    out=run('reviewer-1 | LLM rule check proposal_pr #184 rule v2: FAIL\nreviewer-1 | LLM rule check proposal_pr #184 rule v2: PASS\nreviewer-1 | LLM rule check milestone_issue #540 rule v2: INCONCLUSIVE reason=run_budget_exhausted\n','reviewer');assert '1 PASS, 0 FAIL, 1 INCONCLUSIVE' in out and '1 budget-exhausted' in out
print('Full-log distinct summaries, errors, failure isolation and latest reviewer verdicts passed')
