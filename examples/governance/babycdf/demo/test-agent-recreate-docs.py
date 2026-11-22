#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parent
command='docker compose -p babycdf-live --profile agents -f compose/compose.yaml -f compose/intake-auth.yaml -f compose/agents-github-auth.yaml -f compose/llm-auth.yaml up -d --no-deps --build --force-recreate advisory reviewer'
for name in ('DEMO.md','GITHUB-TOKEN.md','LLM.md'):
    text=(r/name).read_text()
    assert command in text,name
    assert 'Canton must already be running.' in text,name
assert '--no-deps' in command and '-p babycdf-live' in command
print('Consistent agent-only recreate command and running-Canton prerequisite passed')
