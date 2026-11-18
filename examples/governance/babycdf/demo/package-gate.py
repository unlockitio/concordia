#!/usr/bin/env python3
"""Local DAR bytes unchanged since bootstrap; no participant inventory claim."""
import hashlib
import json
import sys
from pathlib import Path

state, dar = map(Path, sys.argv[1:])
expected = json.loads((state / 'demo-dar.json').read_text())
if hashlib.sha256(dar.read_bytes()).hexdigest() != expected['sha256']:
    sys.exit('rebuild detected: run down/up')
print('local DAR bytes unchanged since bootstrap; main=' + expected['main_package_id'])
