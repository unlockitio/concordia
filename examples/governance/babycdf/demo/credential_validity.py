#!/usr/bin/env python3
"""Parse the fresh-bootstrap credential lifetime without ledger access."""
import re
import sys


def validity_seconds(value="30d"):
    match = re.fullmatch(r"([1-9][0-9]*)([hd])", value)
    if not match:
        raise ValueError("credential validity must be whole hours or days, such as 30d or 48h")
    seconds = int(match[1]) * (3600 if match[2] == "h" else 86400)
    if seconds > 365 * 86400:
        raise ValueError("credential validity must be between 1h and 365d")
    return seconds


if __name__ == "__main__":
    try:
        if len(sys.argv) > 2:
            raise ValueError("expected at most one credential validity")
        print(validity_seconds(sys.argv[1] if len(sys.argv) == 2 else "30d"))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
