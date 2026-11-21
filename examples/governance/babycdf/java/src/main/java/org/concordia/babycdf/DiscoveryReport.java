package org.concordia.babycdf;

import java.time.Instant;
import java.util.Set;

/** Observed inventory, not an agenda, verification claim, or atomic GitHub snapshot. */
public record DiscoveryReport(Instant observedAt, Inventory pulls, Inventory issues) {
    public record Item(long number, String title) { }
    public enum StopReason {
        COMPLETE, PAGE_LIMIT, REQUEST_LIMIT, RESPONSE_LIMIT, TIME_LIMIT, CYCLE, UNSAFE_LINK,
        INVALID_RESPONSE, FORBIDDEN, RATE_LIMITED, HTTP_ERROR, TRANSPORT_ERROR, INTERRUPTED
    }
    public record Inventory(Set<Item> items, Set<Long> milestoneIssueNumbers,
                            int requests, StopReason stopReason) {
        public Inventory {
            items = Set.copyOf(items);
            milestoneIssueNumbers = Set.copyOf(milestoneIssueNumbers);
        }
        public int observedUniqueCount() { return items.size(); }
        public boolean complete() { return stopReason == StopReason.COMPLETE; }
    }
    public boolean complete() { return pulls.complete() && issues.complete(); }
}
