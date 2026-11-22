#!/usr/bin/env python3
"""Streaming informational summary; never interprets logs as ledger authority."""
import re,sys,tempfile,os,unicodedata
sqlite3=None

def clean(text):
    text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text)
    text=''.join(' ' if unicodedata.category(c) in ('Cc','Cf','Zl','Zp') else c for c in text)
    text=re.sub(r'(?i)(bearer\s+|(?:token|secret|api[_-]?key)\s*[:=]\s*)\S+',r'\1[REDACTED]',text)
    text=re.sub(r'(?:github_pat_|ghp_|sk-)[A-Za-z0-9_-]+','[REDACTED]',text)
    return text.strip()[:240]

def main():
    agent=sys.argv[1]
    with tempfile.TemporaryDirectory(prefix='agent-summary-') as tmp:
        db=sqlite3.connect(os.path.join(tmp,'summary.sqlite'))
        db.execute('pragma cache_size=-1024')
        db.execute('create table events(kind text, number text, action text, primary key(kind,number,action))')
        db.execute('create table errors(message text primary key)')
        db.execute('create table verdicts(subject text, version text, verdict text, budget integer, primary key(subject,version))')
        oversized=0
        if hasattr(sys.stdin,'reconfigure'):sys.stdin.reconfigure(errors='replace')
        while True:
            line=sys.stdin.readline(65537)
            if not line:break
            if len(line)>65536:
                if not line.endswith('\n'):
                    while True:
                        rest=sys.stdin.readline(65537)
                        if not rest or rest.endswith('\n'):break
                oversized+=1
                continue
            payload=re.sub(r'^.*?\|\s*','',line,count=1)
            event=re.search(r'\b(Proposal|Milestone) #(\d+) (added|updated)\b',payload)
            if event:db.execute('insert or ignore into events values(?,?,?)',event.groups())
            verdict=re.search(r'LLM rule check (.+?) rule v(\d+): (PASS|FAIL|INCONCLUSIVE|NOT_APPLICABLE)\b',payload)
            if verdict:db.execute('insert or replace into verdicts values(?,?,?,?)',(*verdict.groups(),int('run_budget_exhausted' in payload)))
            if re.search(r'Source discovery paused:|\bBLOCKED\b|fetch_failed|\brejected\b|verified=false',payload,re.I):
                db.execute('insert or ignore into errors values(?)',(clean(payload),))
        db.commit()
        errors=db.execute('select count(*) from errors').fetchone()[0]
        if agent=='intake':
            def count(kind,action):return db.execute('select count(*) from events where kind=? and action=?',(kind,action)).fetchone()[0]
            updated=db.execute("select count(*) from events where action='updated'").fetchone()[0]
            print(f'[DISCOVERY] Intake catalogue so far: {count("Proposal","added")} proposals added, {count("Milestone","added")} milestones added, {updated} distinct subjects updated, {errors} errors (retained logs of current container; catalogue only, not ledger evidence or voting authority).')
        else:
            counts=dict(db.execute('select verdict,count(*) from verdicts group by verdict'))
            budget=db.execute('select coalesce(sum(budget),0) from verdicts').fetchone()[0]
            print('[DISCOVERY] Reviewer checks so far: '+', '.join(f'{counts.get(v,0)} {v}' for v in ('PASS','FAIL','INCONCLUSIVE','NOT_APPLICABLE'))+f', {budget} budget-exhausted, {errors} errors (latest per subject/rule version from full reviewer log; informational only).')
        for (message,) in db.execute('select message from errors order by message limit 5'):print('[DISCOVERY] '+agent+' error: '+message)
        if errors>5:print(f'[DISCOVERY] {errors-5} additional distinct {agent} errors omitted.')
        if oversized:print(f'[DISCOVERY] incomplete: {oversized} oversized lines skipped (oversized_skipped={oversized}).')
        db.close()

def boundary():
    global sqlite3
    try:
        try:import sqlite3 as module
        except ImportError:
            print('[DISCOVERY] summary_failed:sqlite_unavailable');return 1
        except OSError:
            print('[DISCOVERY] summary_failed:storage');return 1
        sqlite3=module
        main();return 0
    except UnicodeError:
        print('[DISCOVERY] summary_failed:decode');return 1
    except (OSError,sqlite3.Error):
        print('[DISCOVERY] summary_failed:storage');return 1
    except Exception:
        print('[DISCOVERY] summary_failed:internal');return 1
if __name__=='__main__':sys.exit(boundary())
