# Rule documents

These versioned JSON documents preserve the current v1/v2 clause content. No S5
text change is included. The authoritative ledger reference is intended to carry
an opaque location, optional commit and SHA-256. Hash the exact file bytes,
including whitespace and terminal newline; do not hash reserialized JSON.

Schema `babycdf.rule/1` has version and unique clauses. Kinds and indicators live
here, not in the ledger schema. PRESENCE_REQUIRED requires nonempty indicators;
CONTRADICTION_OR_SUPPORT rejects nonempty indicators. Unknown fields/kinds fail
closed. Relative paths resolve beneath an explicitly trusted local rule root;
remote locations must use approved HTTPS GitHub immutable commit URLs.
