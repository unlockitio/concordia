# HTTP active-contract pagination

The HTTP party/template ACS paths use Canton 3.5.11's query-only
POST `/v2/state/active-contracts-page`, with `maxPageSize: 200`. Every page retains
the captured ledger end and identical event filter on the same participant.
Opaque page tokens are transported verbatim as JSON strings and never decoded.
Only an absent next token ends the scan; short and empty pages may continue.

The operation buffers a complete result before normalizing/publishing it. Invalid
shape, offset mismatch, token repetition, incomplete reassignment, conflicting full
CID duplicate, HTTP error, or bounds breach fails closed with no partial inventory.
Bounds: 1000 pages, 10000 contracts, 20 MB aggregate response bytes, 15 seconds per
HTTP call and 120 seconds for the complete scan. Each fetch receives the remaining
deadline and both curl and its subprocess are bounded by it. Receipt, parsing,
normalization and pre-publication checks reject late terminal pages too. Parsing
and output encoding are cooperative checks, not preemptible CPU work; publication
is checked before writing but a blocked output sink is outside the scan deadline.
Curl's first option is `-q` to ignore curlrc; proxy/environment policy is unchanged.
Duplicate JSON keys are rejected. Identical full CID repeats are deduplicated. Existing gRPC streaming and
history pagination are unchanged. No limit override, transport fallback or runtime
configuration repair is performed.
