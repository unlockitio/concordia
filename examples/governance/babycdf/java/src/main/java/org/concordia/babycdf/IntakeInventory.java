package org.concordia.babycdf;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.List;

/** Advisory client observation only; never a source of agenda authority. */
public final class IntakeInventory {
    private IntakeInventory() { }
    static GitHubDiscovery fixture() {
        // Synthetic multi-page example, not a cached observation of GitHub.
        return new GitHubDiscovery((uri,timeout)-> {
            boolean pulls=uri.getPath().endsWith("/pulls");
            boolean page2=uri.getQuery().contains("page=2");
            String body=pulls ? (page2 ? "[{\"number\":184,\"title\":\"Synthetic proposal\"},{\"number\":185,\"title\":\"Synthetic other proposal\"}]" : "[{\"number\":184,\"title\":\"Synthetic proposal\"}]")
                : "[{\"number\":184,\"title\":\"Synthetic PR mirror\",\"pull_request\":{}},{\"number\":540,\"title\":\"Concordia #184 - Milestone 1: Synthetic evidence\"},{\"number\":541,\"title\":\"Synthetic ordinary issue\"}]";
            return new GitHubDiscovery.Response(200,pulls&&!page2 ? List.of("<https://api.github.com/repos/canton-foundation/canton-dev-fund/pulls?state=all&per_page=100&page=2>; rel=\"next\"") : List.of(),body);
        },GitHubDiscovery.Limits.defaults(),Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC),System::nanoTime);
    }
    static String render(String mode,DiscoveryReport report) {
        String source=mode.equals("live") ? "LIVE_AUTHENTICATED_GITHUB" : "SYNTHETIC_OFFLINE_FIXTURE_NOT_GITHUB";
        return "[DISCOVERY] source="+source+" observedAt="+report.observedAt()+" complete="+report.complete()
            +" observedUniquePRs="+report.pulls().observedUniqueCount()+" pullPagesAttempted="+report.pulls().requests()+" pullStop="+report.pulls().stopReason()
            +" observedUniqueIssuesExcludingPRs="+report.issues().observedUniqueCount()+" milestoneTitleMatches="+report.issues().milestoneIssueNumbers().size()
            +" issuePagesAttempted="+report.issues().requests()+" issueStop="+report.issues().stopReason()
            +"; partial counts are not repository totals; not an atomic snapshot.\n"
            +"[DISCOVERY] Milestone convention: title '<project> #<proposal> - Milestone <number>: <text>'; ordinary issues are not milestones.\n"
            +"[DISCOVERY] Exactly 2 curated demonstration subjects selected: proposal-184 (pinned bytes are verified by Intake when the request is processed) and milestone-540 (illustrative, unverified). Inventory does not expand catalogue or create topics; one request per step.\n";
    }
    static GitHubDiscovery.Limits startupLimits() {
        return new GitHubDiscovery.Limits(20,40,Duration.ofSeconds(30),Duration.ofSeconds(5));
    }
    static String observe(String mode) throws IOException {
        if(!List.of("fixture","live").contains(mode)) throw new IllegalArgumentException("discovery mode must be fixture or live");
        return render(mode,(mode.equals("live")?live(IntakeGitHub.configured(System.getenv()).transport()):fixture()).discover());
    }
    static GitHubDiscovery live(GitHubDiscovery.Transport transport) {
        return new GitHubDiscovery(transport,startupLimits(),Clock.systemUTC(),System::nanoTime);
    }
    static void startup(String mode,Path journal) throws IOException {
        Path directory=IntakeLedger.validateJournal(journal);
        String report=observe(mode);
        Path next=Files.createTempFile(directory,"inventory-",".next");
        Files.writeString(next,report);
        Files.move(next,directory.resolve("discovery-inventory.txt"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        System.out.print(report);
    }
}
