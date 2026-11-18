#!/usr/bin/env python3
"""Validate Daml init output and atomically publish local demo readback."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile


def publish(state_dir, port, package):
    state_dir = Path(state_dir)
    state = json.loads((state_dir / "bootstrap.json").read_text())
    inspected = json.loads(Path(package).read_text())
    required = ("member", "governor", "userName", "verifiedRights")
    if not isinstance(state, dict) or any(key not in state for key in required):
        raise ValueError("bootstrap state is missing required fields: member, governor, userName, verifiedRights")
    if not isinstance(inspected, dict) or "main_package_id" not in inspected:
        raise ValueError("inspection is missing main_package_id")
    package_id = inspected["main_package_id"]
    if not re.fullmatch(r"[0-9a-f]{64}", package_id):
        raise ValueError("invalid deployed package ID")
    if not re.fullmatch(r"[1-9][0-9]*", str(port)) or not 1 <= int(port) <= 65535:
        raise ValueError("invalid ledger port")
    fields = [str(port), state["member"], package_id, state["governor"], state["userName"]]
    if any(not isinstance(x, str) or not x or any(c.isspace() for c in x) for x in fields):
        raise ValueError("invalid readback field")
    # Daml entry point asserts rights read back from the Ledger API.
    if not state.get("verifiedRights"):
        raise ValueError("missing verified user rights")
    fd, name = tempfile.mkstemp(prefix="readback-next-", dir=state_dir)
    with os.fdopen(fd, "w") as out:
        out.write(" ".join(fields) + "\n")
    os.chmod(name, 0o644)  # synthetic IDs only; non-root agent bind-mount readability
    os.replace(name, state_dir / "readback")


if __name__ == "__main__":
    publish(*sys.argv[1:])
