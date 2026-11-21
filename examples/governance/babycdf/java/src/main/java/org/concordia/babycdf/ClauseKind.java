package org.concordia.babycdf;

/** Names mirror the authoritative ledger enum. Unknown strings never acquire authority. */
enum ClauseKind {
    CONTRADICTION_OR_SUPPORT, PRESENCE_REQUIRED;
    static ClauseKind recognized(String name) {
        try { return valueOf(name); } catch(RuntimeException unknown) { return null; }
    }
}
