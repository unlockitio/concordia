#!/usr/bin/env python3
"""Validate demo-owned Docker names using config only; never contact the ledger.

Requires the pinned CIPS export to have been prepared already. Does not prepare
exports, create containers/networks, write state, or clean up directories.
"""
import json
import os
from pathlib import Path
import subprocess

compose = Path(__file__).resolve().parent / "compose" / "compose.yaml"
environment = os.environ.copy()
for key in ("INTAKE_DISCOVERY_MODE", "INTAKE_SOURCE_INTERVAL_MILLIS", "INTAKE_GITHUB_TOKEN_FILE", "LLM_API_KEY_FILE", "BABYCDF_LLM_ENABLED"):
    environment.pop(key, None)
def config(env, auth=False, llm=False):
    command = ["docker", "compose", "--profile", "agents", "-f", str(compose)]
    if auth:
        command += ['-f', str(compose.parent / 'intake-auth.yaml'), '-f', str(compose.parent / 'agents-github-auth.yaml')]
    if llm:
        command += ['-f', str(compose.parent / 'llm-auth.yaml')]
    return json.loads(subprocess.check_output(
        command + ["config", "--format", "json"], text=True, env=env))
model = config(environment)

assert model["name"] == "babycdf-live", model["name"]
assert set(model["services"]) == {"canton", "advisory", "reviewer", "intake"}
assert set(model["networks"]) == {"canton"}, model["networks"]
assert model["networks"]["canton"]["name"] == "babycdf-live_canton"
assert set(model["services"]["canton"]["networks"]) == {"canton"}
for name, service in model["services"].items():
    assert "credential" not in name
    assert "container_name" not in service, service.get("container_name")
    assert "credential" not in service.get("hostname", "")
    for network, attachment in service.get("networks", {}).items():
        assert "credential" not in network
        for alias in (attachment or {}).get("aliases", []):
            assert "credential" not in alias, alias
for agent in ("advisory", "reviewer", "intake"):
    assert model["services"][agent]["network_mode"] == "service:canton"
assert model['services']['intake']['environment']['INTAKE_DISCOVERY_MODE'] == 'live'
assert model['services']['intake']['environment']['INTAKE_SOURCE_INTERVAL_MILLIS'] == '30000'
fixture_environment = dict(environment, INTAKE_DISCOVERY_MODE='fixture', INTAKE_SOURCE_INTERVAL_MILLIS='60000')
fixture = config(fixture_environment)
assert fixture['services']['intake']['environment']['INTAKE_DISCOVERY_MODE'] == 'fixture'
assert fixture['services']['intake']['environment']['INTAKE_SOURCE_INTERVAL_MILLIS'] == '60000'
for agent in ('advisory', 'reviewer', 'intake'):
    service = model['services'][agent]
    assert service['read_only'] is True
    assert service['restart'] == 'no'
    assert 'ALL' in service['cap_drop']
    assert 'no-new-privileges:true' in service['security_opt']
assert not any(key.startswith(('LLM_', 'BABYCDF_LLM')) for key in model['services']['intake']['environment'])
journal = [v for v in model['services']['intake']['volumes'] if v['target'] == '/journal']
assert len(journal) == 1 and journal[0]['type'] == 'volume'
assert model['volumes'][journal[0]['source']]['name'] == 'babycdf-live_intake-journal'
# Synthetic path only: config validation neither reads a credential nor starts services.
import tempfile
with tempfile.TemporaryDirectory() as tmp:
    synthetic = Path(tmp) / 'synthetic-secret'
    synthetic.write_text('synthetic-config-only-not-a-credential\n')
    authenticated = config(dict(environment, INTAKE_GITHUB_TOKEN_FILE=str(synthetic)), auth=True)
    intake = authenticated['services']['intake']
    assert intake['environment']['INTAKE_GITHUB_TOKEN_FILE'] == '/run/secrets/intake_github_token'
    assert len(intake['secrets']) == 1
    assert intake['secrets'][0]['source'] == 'intake_github_token'
    assert intake['secrets'][0]['target'] == '/run/secrets/intake_github_token'
    assert authenticated['secrets']['intake_github_token']['file'] == str(synthetic)
    for agent in ('advisory', 'reviewer'):
        assert authenticated['services'][agent]['secrets'][0]['source'] == 'intake_github_token'
        assert authenticated['services'][agent]['environment']['INTAKE_GITHUB_TOKEN_FILE'] == '/run/secrets/intake_github_token'
    assert not authenticated['services']['canton'].get('secrets')
    llm_model = config(dict(environment, BABYCDF_LLM_ENABLED='1',
                            LLM_API_KEY_FILE=str(synthetic)), llm=True)
    for agent in ('advisory', 'reviewer'):
        service = llm_model['services'][agent]
        assert service['environment']['LLM_API_KEY_FILE'] == '/run/secrets/llm_api_key'
        assert service['secrets'] == [{'source': 'llm_api_key', 'target': '/run/secrets/llm_api_key'}]
    for agent in ('intake', 'canton'):
        assert not llm_model['services'][agent].get('secrets')
        assert 'LLM_API_KEY_FILE' not in llm_model['services'][agent].get('environment', {})
    assert llm_model['secrets']['llm_api_key']['file'] == str(synthetic)
print("Compose config names, Intake-only and LLM agent-only file secrets passed")
