# To add to the auction docs

Points on cap-auctions that the docs do not cover yet.

## Settle contexts are fetched before the legs exist

`clearAndSettleSingleLotFromEmptyLegsAllocations` computes the legs on ledger and settles in the same transaction, so the executor fetches each registry's `SettlementFactory_SettleBatch` context before the legs are known. Registries that build the context from the accounts on the legs, such as TestTokenV2 for accounts with a provider, then leave out what settlement needs and the settlement aborts. The executor must request the context for every funding account, or resolve into a `Settlement` contract first and fetch the context with the real legs from its batches.
