#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parent
command='docker compose -p babycdf-live --profile agents -f compose/compose.yaml -f compose/intake-auth.yaml -f compose/agents-github-auth.yaml -f compose/llm-auth.yaml up -d --no-deps --build --force-recreate reviewer'
for name in ('DEMO.md','GITHUB-TOKEN.md','LLM.md'):
    text=(r/name).read_text()
    assert command in text,name
    assert 'Canton must already be running.' in text,name
snippets=[]
for name in ('DEMO.md','GITHUB-TOKEN.md','LLM.md'):
    text=(r/name).read_text().split('### Bounded migration from the old Advisory container')[1]
    snippet=text.split('```sh\n')[1].split('```')[0]
    snippets.append(snippet)
    assert 'com.docker.compose.project=babycdf-live' in snippet and 'com.docker.compose.service=advisory' in snippet
    assert '[ "$#" -gt 1 ]' in snippet and 'docker stop "$1"' in snippet and 'docker rm "$1"' in snippet
    for forbidden in ('--remove-orphans','prune','down'):
        assert forbidden not in snippet
assert len(set(snippets)) == 1
# Execute only against a private stub: never invoke the host Docker binary.
import os, subprocess, tempfile
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp)
    docker=root/'docker'
    docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$1" in
 ps) case "$MODE" in discovery_fail) exit 7;; zero) exit 0;; multiple) printf 'id-one\\nid-two\\n';; *) printf 'id-one\\n';; esac;;
 stop) [ "$MODE" != stop_fail ];;
 rm) [ "$MODE" != rm_fail ];;
 compose) exit 0;;
 *) exit 99;;
esac
''')
    docker.chmod(0o755)
    for mode in ('discovery_fail','zero','one','multiple','stop_fail','rm_fail'):
        log=root/(mode+'.log')
        env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],CALLS=str(log),MODE=mode)
        result=subprocess.run(['sh','-c',snippets[0]],env=env,text=True,capture_output=True)
        calls=log.read_text().splitlines()
        assert calls[0] == 'ps -aq --filter label=com.docker.compose.project=babycdf-live --filter label=com.docker.compose.service=advisory'
        mutations=calls[1:]
        if mode in ('discovery_fail','multiple'):
            assert result.returncode != 0 and not mutations,(mode,calls)
        elif mode=='zero':
            assert result.returncode == 0 and len(mutations)==1 and mutations[0].startswith('compose '),(mode,calls)
        elif mode=='one':
            assert result.returncode == 0 and mutations[:2]==['stop id-one','rm id-one'] and len(mutations)==3 and mutations[2].startswith('compose '),(mode,calls)
        elif mode=='stop_fail':
            assert result.returncode != 0 and mutations==['stop id-one'],calls
        else:
            assert result.returncode != 0 and mutations==['stop id-one','rm id-one'],calls
assert '--no-deps' in command and '-p babycdf-live' in command
print('Consistent agent-only recreate command and running-Canton prerequisite passed')
