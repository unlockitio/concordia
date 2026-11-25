import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[4]
runner = root / 'examples/governance/babycdf/demo/run-demo.sh'
source = runner.read_text()
producer_marker = '    python3 - "$HERE/../rules" "$STATE/bootstrap-input.json" "$credential_validity_seconds" <<\'PY\'\n'
consumer_marker = '    python3 - "$STATE" <<\'PY\'\n'
producer = source.split(producer_marker, 1)[1].split('\nPY', 1)[0]
consumer = source.split(consumer_marker, 1)[1].split('\nPY', 1)[0]
with tempfile.TemporaryDirectory() as directory:
    state = Path(directory)
    subprocess.run([sys.executable, '-', str(root / 'examples/governance/babycdf/rules'), str(state / 'bootstrap-input.json'), '3600'], input=producer, text=True, check=True)
    configured = json.loads((state / 'bootstrap-input.json').read_text())
    user = configured['input']['userName']
    output = dict(userName=user, intakeUserName=user + '-intake', opener='opener::synthetic', verifiedRights=[dict(tag='CanReadAs', value='opener::synthetic')], intakeVerifiedRights=[dict(tag='CanActAs', value='opener::synthetic')])
    cases = [('producer-output', configured, output, True)]
    for name, value in [('missing-input', {}), ('null-input', {'input': None}), ('old-flat', {'userName': user}), ('missing-user', {'input': {}}), ('blank-user', {'input': {'userName': ' '}}), ('nonstring-user', {'input': {'userName': 1}}), ('padded-user', {'input': {'userName': ' ' + user}})]:
        cases.append((name, value, output, False))
    for field, value in [('userName', 'wrong-user'), ('intakeUserName', user), ('intakeUserName', ''), ('verifiedRights', []), ('intakeVerifiedRights', None), ('verifiedRights', [{}])]:
        changed = copy.deepcopy(output)
        changed[field] = value
        cases.append((field + repr(value), configured, changed, False))
    for field, value in [('intakeUserName', user + '-wrong'), ('opener', ''), ('opener', 'bad party'), ('verifiedRights', [dict(tag='CanActAs', value=output['opener'])]), ('intakeVerifiedRights', [dict(tag='CanReadAs', value=output['opener'])]), ('verifiedRights', [dict(tag='CanReadAs', value='wrong-party')])]:
        changed = copy.deepcopy(output)
        changed[field] = value
        cases.append((field + repr(value), configured, changed, False))
    for name, request, response, valid in cases:
        (state / 'bootstrap-input.json').write_text(json.dumps(request))
        (state / 'bootstrap.json').write_text(json.dumps(response))
        (state / 'run-id.json').write_text('"unchanged"')
        result = subprocess.run([sys.executable, '-', str(state)], input=consumer, text=True, capture_output=True)
        assert (result.returncode == 0) == valid, (name, result.stderr)
        assert json.loads((state / 'run-id.json').read_text()) == (user if valid else 'unchanged'), name
        if not valid:
            assert 'Invalid bootstrap' in result.stderr, (name, result.stderr)
    import importlib.util
    spec = importlib.util.spec_from_file_location('readback', runner.parent / 'publish-readback.py')
    publisher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publisher)
    for index in range(20):
        response = dict(output, opener=f'opener{index}::synthetic', governor=f'governor{index}::synthetic', verifiedRights=[dict(tag='CanReadAs', value=f'opener{index}::synthetic')], intakeVerifiedRights=[dict(tag='CanActAs', value=f'opener{index}::synthetic')])
        (state / 'bootstrap-input.json').write_text(json.dumps(configured))
        (state / 'bootstrap.json').write_text(json.dumps(response))
        subprocess.run([sys.executable, '-', str(state)], input=consumer, text=True, check=True)
        (state / 'impl-package.json').write_text(json.dumps({'main_package_id': 'a' * 64}))
        (state / 'demo-inspect.json').write_text(json.dumps({'main_package_id': 'b' * 64}))
        publisher.publish(state, str(6000 + index), state / 'impl-package.json', state / 'demo-inspect.json')
        reviewer = (state / 'readback').read_text().split()
        intake = (state / 'readback-intake').read_text().split()
        assert reviewer == [str(6000 + index), response['opener'], 'a' * 64, response['governor'], user]
        assert intake == reviewer[:-1] + [response['intakeUserName']]
        assert (state / 'intake-demo-package').read_text().strip() == 'b' * 64
        assert json.loads((state / 'run-id.json').read_text()) == reviewer[-1]
        import os
        stub = state / 'java'
        stub.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$JAVA_ARGUMENTS"\n')
        stub.chmod(0o755)
        entrypoint = runner.parent / 'compose/agents/entrypoint.sh'
        for mode, readback in [('reviewer', 'readback'), ('intake', 'readback-intake')]:
            arguments = state / 'java-arguments'
            environment = dict(PATH=str(state) + ':/usr/bin:/bin', READBACK_FILE=str(state / readback), INTAKE_PACKAGE_FILE=str(state / 'intake-demo-package'), INTAKE_JOURNAL=str(state), JAVA_ARGUMENTS=str(arguments))
            checked = subprocess.run(['/bin/sh', str(entrypoint), 'validate'], env=environment, text=True, capture_output=True)
            assert checked.returncode == 0, (mode, checked.stderr)
            started = subprocess.run(['/bin/sh', str(entrypoint), mode], env=environment, text=True, capture_output=True)
            assert started.returncode == 0, (mode, started.stderr)
            argv = arguments.read_text().splitlines()
            assert response['opener'] in argv and 'a' * 64 in argv, (mode, argv)
            if mode == 'intake':
                assert response['intakeUserName'] in argv and 'b' * 64 in argv, argv
                assert user not in argv, argv
    print(f'PASS: {len(cases)} schema rejection cases and 20 offline producer-to-run-id-to-both-readbacks publication cases')
