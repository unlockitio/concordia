#!/usr/bin/env python3
"""Validate Daml init output and atomically publish local demo readback."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile


def publish(state_dir, port, package, demo_package=None):
    state_dir = Path(state_dir)
    state = json.loads((state_dir / "bootstrap.json").read_text())
    inspected = json.loads(Path(package).read_text())
    required = ("opener", "governor", "userName", "verifiedRights",
                "intakeUserName", "intakeVerifiedRights")
    if not isinstance(state, dict) or any(key not in state for key in required):
        raise ValueError("bootstrap state is missing required identity or rights fields")
    if not isinstance(inspected, dict) or "main_package_id" not in inspected:
        raise ValueError("inspection is missing main_package_id")
    package_id = inspected["main_package_id"]
    if not re.fullmatch(r"[0-9a-f]{64}", package_id):
        raise ValueError("invalid deployed package ID")
    if not re.fullmatch(r"[1-9][0-9]*", str(port)) or not 1 <= int(port) <= 65535:
        raise ValueError("invalid ledger port")
    fields = [str(port), state["opener"], package_id, state["governor"], state["userName"]]
    intake_fields = fields[:-1] + [state["intakeUserName"]]
    if any(not isinstance(x, str) or not x or any(c.isspace() for c in x) for x in fields + intake_fields):
        raise ValueError("invalid readback field")
    # Daml entry point asserts rights read back from the Ledger API.
    if not state.get("verifiedRights") or not state.get("intakeVerifiedRights"):
        raise ValueError("missing verified user rights")
    if state["intakeUserName"] == state["userName"]:
        raise ValueError("Intake requires a separate user")
    publications = [("readback", fields), ("readback-intake", intake_fields)]
    if demo_package is not None:
        demo_inspected = json.loads(Path(demo_package).read_text())
        demo_id = demo_inspected.get("main_package_id") if isinstance(demo_inspected, dict) else None
        if not isinstance(demo_id, str) or not re.fullmatch(r"[0-9a-f]{64}", demo_id):
            raise ValueError("invalid deployed Intake demo package ID")
        publications.append(("intake-demo-package", [demo_id]))
    for target, values in publications:
        fd, name = tempfile.mkstemp(prefix="readback-next-", dir=state_dir)
        with os.fdopen(fd, "w") as out:
            out.write(" ".join(values) + "\n")
        os.chmod(name, 0o644)  # synthetic IDs only; non-root agent bind-mount readability
        os.replace(name, state_dir / target)


if __name__ == "__main__":
    publish(*sys.argv[1:])
