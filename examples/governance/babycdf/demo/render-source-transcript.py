#!/usr/bin/env python3
"""Render source narration in lexical order; never invent runtime values."""
import hashlib
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
text = source.read_text()
out.mkdir(parents=True, exist_ok=True)
functions = [('1', 'bootstrap'), ('2', 'ruleVote'), ('3', 'proposalVote'),
             ('4', 'milestoneVote'), ('5', 'couponAllocate'), ('6', 'blockedPaths'),
             ('opt-1', 'staleRuleChange')]
header = ('RENDERED OFFLINE SOURCE — NOT A LIVE RUN OR LIVE COUNTS\n'
          'Dynamic expressions remain source expressions; branches are not executed.\n'
          'Shared state blocks query scoped active contracts/interfaces at runtime.\n'
          'Source SHA256: ' + hashlib.sha256(source.read_bytes()).hexdigest() + '\n\n')
all_steps = []
for number, name in functions:
    match = re.search(r'^' + name + r' .*?= do\n(.*?)(?=^\w[^\n]* :|\Z)', text, re.M | re.S)
    if not match:
        raise SystemExit('missing ' + name)
    lines = match.group(1).splitlines()
    rendered = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if re.match(r'(stepHeading|phase|business|decision|outcomeLine|expectedRefusal|evidenceLine|initialState|finalState|voteNarration)\b', stripped):
            parts = [stripped]
            indent = len(line) - len(line.lstrip())
            i += 1
            while i < len(lines) and lines[i].strip() and len(lines[i]) - len(lines[i].lstrip()) > indent:
                parts.append(lines[i].strip())
                i += 1
            expression = ' '.join(parts)
            if stripped.startswith('initialState '):
                expression += ' => INITIAL STATE [runtime scoped queries; no offline counts]'
            elif stripped.startswith('finalState '):
                expression += ' => FINAL STATE [runtime scoped queries; no offline counts]'
            rendered.append(expression)
            continue
        i += 1
    result = header + 'STEP ' + number + ' (' + name + ')\n\n' + '\n'.join(rendered) + '\n'
    (out / ('step-' + number + '.txt')).write_text(result)
    if number != 'opt-1':
        all_steps.append(result)
(out / 'all-six-steps-rendered.txt').write_text('\n'.join(all_steps))
print('Rendered 6 main steps and opt-step 1 from source; no ledger contacted')
