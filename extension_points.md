<!-- Copyright (c) 2026 Unlockit. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# CAP extension points (final)

## CAP Governance 

### Extensible-by-deploy action set
Open `GovAction` into the `Outcome` interface so a mechanism's action set grows
by deployment, not by an interface change. The anchor/`DsoRules` decoupling keeps
the library and the authority identity fixed while new actions ship as pure deploys.

### Efficient resolve-and-execute path
The default outcome execution creates a new contract and archives it in the same
transaction; every Splice use case executes in the same transaction as resolve.
Add a dedicated path for this common resolve-and-execute-atomically case.

### Secret ballots
Votes commit-reveal scheme.


## CAP Auctions 

### cap-auctions formats
Sealed-bid first-price, sealed-bid second-price, Dutch, and multi-unit auctions
all fill the same spine. Combinatorial auctions and continuous double auctions are
admitted as later formats without a core change.

### Token Standard composability
Settlement issues Token Standard V2 allocations, so an auction clears and the asset moves in one atomic DvP.

# Out of scope


## Unbuilt formats
Call/periodic markets, 

## No KYC / identity system
The participation-right pattern is the eligibility mechanism: an empty `Submittable`
is minted only to eligible parties. Core forces no identity system because
institutions bring their own, and it composes with it.

## No custody / off-ledger settlement
Custody and Settlement is the Token Standard's job; CAP composes with it and issues obligations
only. 
