# Shared read-only GitHub token (operator provisioning; live acceptance pending)

Intake, Advisory and Reviewer reuse the same validated file loader. The runner
selects both `intake-auth.yaml` and `agents-github-auth.yaml` for an explicit
`INTAKE_GITHUB_TOKEN_FILE`. Without the agents overlay, Reviewer/Advisory remain
anonymous; ambient `GITHUB_TOKEN` is ignored. Canton never receives the secret.
This grants no GitHub publication capability or ledger write authority.

To recreate ONLY the two read-only agents after provisioning (operator action;
not performed by offline validation), from the repository root:

```sh
docker compose -p babycdf-live --profile agents \
  -f examples/governance/babycdf/demo/compose/compose.yaml \
  -f examples/governance/babycdf/demo/compose/agents-github-auth.yaml \
  up -d --no-deps --force-recreate advisory reviewer
```

Keep the existing ledger and Intake running; do not use down or reset.

Create a **fine-grained personal access token** at
<https://github.com/settings/personal-access-tokens/new> while signed into your
own GitHub account. Choose your own account as resource owner, a descriptive
name such as `BabyCDF public read`, and a short expiry (for example 30 days).
Fine-grained tokens already have read access to public repositories: for the
public `canton-foundation/canton-dev-fund` reads here, request **no extra repository
permissions**, no write access, no organization membership or organization-owned
repository selection. Do not request broad `repo` scope. If fine-grained PATs are
unavailable, a classic PAT with **no scopes** can read public information, but
fine-grained is preferred. See GitHub's official
[managing PATs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).
Token creation, expiry, revocation and account decisions belong to the operator.
Never paste the token into chat, source, `.env`, a ticket, a shell argument or a log.

## Store outside the checkout, without echo or command-line token literals

The following is an **operator-only** interactive Python command, run in a terminal
with shell tracing disabled. It prompts without echo; the value is never in shell
history, process arguments or an environment variable. It refuses to overwrite an
existing file. Do not run it through a recorded assistant tool session.

```sh
python3 - <<'PY'
import getpass, os
from pathlib import Path
folder = Path.home() / '.config' / 'babycdf'
folder.mkdir(mode=0o700, parents=True, exist_ok=True)
if folder.is_symlink() or folder.stat().st_mode & 0o077:
    raise SystemExit('Use a private non-symlink 0700 credential directory')
value = getpass.getpass('GitHub PAT (hidden): ')
if not value or len(value) > 4096 or any(ord(c) < 33 or ord(c) > 126 for c in value):
    raise SystemExit('Expected one nonempty printable ASCII token')
# Compose file secrets are bind mounts: UID 65532 needs read access. 0444 is
# confined by the private 0700 host directory, not by a misleading Compose mode.
fd = os.open(folder / 'intake-github-token', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
with os.fdopen(fd, 'w', encoding='ascii') as f:
    f.write(value + '\n')
del value
PY
```

Keep the containing directory private; never place this file in any checkout or
Docker build context. On Linux, a host-owned `0600` bind-mounted file will not be
readable by container UID 65532. Compose's `uid`, `gid` and `mode` are **not
implemented for file-backed secrets**; see the
[Compose runtime secrets specification](https://compose-spec.github.io/compose-spec/container_spec.html#secrets).
The example uses a read-only `0444` file protected from other host users by its
`0700` directory. Verify your platform's ownership/mount access using operator
judgment; do not weaken directory protection or run the agent as root. Existing
host file permissions are never automatically changed by this implementation.

## Select the secret for Compose

From `examples/governance/babycdf/demo`, for a **new disposable demo only**, after
normal prerequisites and after explicitly deciding to start a new ledger:

```sh
INTAKE_GITHUB_TOKEN_FILE="$HOME/.config/babycdf/intake-github-token" ./run-demo.sh up
```

`run-demo.sh` adds `compose/intake-auth.yaml` only when that explicit absolute host
path is supplied. Compose grants the secret **only to Intake**, read-only at
`/run/secrets/intake_github_token`. In Java the variable names the **container
path**, not the host path. For direct local Java invocation use the absolute host
path instead. Neither `GITHUB_TOKEN`, `INTAKE_GITHUB_TOKEN` nor any LLM key is used.
The base Compose model still parses without a secret, so explicit fixture mode
can run without credential provisioning:

```sh
INTAKE_DISCOVERY_MODE=fixture ./run-demo.sh up
```

These `up` examples are not instructions to reset an existing live/demo ledger.
For an existing ledger, **do not run down/up**: after provisioning and deliberate
operator approval, rebuild/recreate only Intake with the same project, base and
auth overlay, preserving the journal volume and checkpoint:

```sh
INTAKE_GITHUB_TOKEN_FILE="$HOME/.config/babycdf/intake-github-token" \
  docker compose -p babycdf-live --profile agents -f compose/compose.yaml \
  -f compose/intake-auth.yaml up -d --no-deps --build intake
```

No runtime change or live authentication was performed as part of this implementation.
Do not print resolved Compose configuration when other service credentials are
present. Never enable Java HTTP header/wire tracing (for example
`jdk.httpclient.HttpClient.log=headers`), a debugging proxy, heap dump or request
capture on the credential-bearing process.

## Rotate without exposing the value

Operator action only: generate a replacement PAT with the same minimal permissions.
Use the hidden interactive provisioning snippet above, changing its output basename
from `intake-github-token` to a fresh name such as `intake-github-token.next` in
**the same private directory**. Keep `O_EXCL` and read-only file permissions; never
print, pass in arguments or export the token value. After deliberately approving
replacement, atomically publish the prepared file:

```sh
python3 - <<'PY'
import os
from pathlib import Path
folder = Path.home() / '.config' / 'babycdf'
os.replace(folder / 'intake-github-token.next', folder / 'intake-github-token')
PY
```

This replaces the directory entry, not a token-bearing command argument. A running
Compose bind mount can retain the old inode: deliberately recreate **only Intake**
with the command above, preserving ledger/journal/checkpoint, then revoke the old
PAT in GitHub. No agent reads, rotates or deletes operator credential files.

## Fail closed and rate limits

Live Intake validates its configured file before ledger/journal construction.
Missing, unreadable, blank, oversized (>4096 token bytes), CR/multiline/non-ASCII
or final-component symlink files block startup, without anonymous fallback. A
single trailing LF is accepted. Secret source parent directories must be trusted
and non-symlinked; the final file is opened with `NOFOLLOW_LINKS`. Fixture mode
does not read or require the file. `--offline-fixture` alone only pins grant bytes;
it does **not** disable live discovery.

The shared credential-bearing transport sends Bearer authorization only to fixed
HTTPS `api.github.com` pull/issue endpoints for this repository, rejects unexpected
targets before constructing the request, and never follows redirects. Tokens are
not serialized to journals, logs, source items or diagnostics. HTTP 401 suspends
source attempts without advancing the checkpoint; replace/revoke the credential
securely and deliberately restart only Intake. Existing 403/429 reset/retry rules
remain in force: a token is not a bypass. Authenticated PAT reads usually share a
5000 requests/hour user budget with other applications; secondary limits and
account-specific policies still apply. This is not a quota guarantee.
