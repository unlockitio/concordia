// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntakeRuntimeConfigTest {
    @TempDir Path journal;
    @org.junit.jupiter.api.BeforeEach void canonicalJournal() throws Exception { journal=journal.toRealPath(); }
    @Test void injectedFactoryActuallyUsesAdapter() throws Exception {
        var server=new SourceCatalogueLedgerTest.Server();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var ledger=Intake.openSources(Intake.discoveryMode(Map.of()),6865,"opener","a".repeat(64),"intake",journal,
                (port,party,pkg,user,path)->{
                    calls.incrementAndGet();
                    assertEquals(6865,port);assertEquals("opener",party);
                    assertEquals("a".repeat(64),pkg);assertEquals("intake",user);
                    assertEquals(journal.resolve("sources"),path);
                    return new SourceCatalogueLedger(party,pkg,user,path,server);
                })) {
            var logs=new java.util.ArrayList<String>();
            new SourceSweepScheduler(new JavaSourceIngestion(ledger,
                    cp->new JavaSourceIngestion.Page(java.util.List.of(SourceCatalogueCodecTest.sample()),"",true),logs::add),
                    System::nanoTime,30000).tick();
            assertEquals(java.util.List.of("Milestone #7 added"),logs);
            assertEquals(1,ledger.items(java.util.Set.of(7L)).size());
        }
        assertEquals(1,calls.get());assertEquals(2,server.sends);
    }
    @Test void explicitFixtureDoesNotConstructAdapterOrSourceJournal() throws Exception {
        assertNull(Intake.openSources(Intake.discoveryMode(Map.of("INTAKE_DISCOVERY_MODE","fixture")),
                6865,"opener","a".repeat(64),"intake",journal,
                (port,party,pkg,user,path)->{fail("offline fixture must not construct ledger adapter");return null;}));
        assertFalse(Files.exists(journal.resolve("sources")));
    }
    @Test void discoveryIsLiveUnlessFixtureExplicitlySelected() {
        assertEquals("live",Intake.discoveryMode(Map.of()));
        assertEquals("live",Intake.discoveryMode(Map.of("INTAKE_DISCOVERY_MODE","live")));
        assertEquals("fixture",Intake.discoveryMode(Map.of("INTAKE_DISCOVERY_MODE","fixture")));
        assertThrows(IllegalArgumentException.class,()->Intake.discoveryMode(Map.of("INTAKE_DISCOVERY_MODE","")));
    }
    @Test void sourceIntervalIsBoundedIndependentlyOfAgendaPolling() {
        assertEquals(30000,Intake.sourceInterval(Map.of()));
        assertEquals(3600000,Intake.sourceInterval(Map.of("INTAKE_SOURCE_INTERVAL_MILLIS","3600000")));
        for(String invalid:new String[]{"0","29999","3600001","invalid"})
            assertThrows(IllegalArgumentException.class,()->Intake.sourceInterval(Map.of("INTAKE_SOURCE_INTERVAL_MILLIS",invalid)));
    }
    @Test void sourceJournalIsPrivateSeparateAndReusable() throws Exception {
        Path source=Intake.sourceJournal(journal);
        assertEquals(journal.resolve("sources"),source);
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(source));
        assertEquals(source,Intake.sourceJournal(journal));
        assertEquals(Files.getOwner(journal),Files.getOwner(source));
    }
    @Test void journalSymlinksAndInsecureExistingDirectoriesFailClosed() throws Exception {
        Path target=Files.createDirectory(journal.resolve("target"));
        Files.createSymbolicLink(journal.resolve("sources"),target);
        assertThrows(IllegalArgumentException.class,()->Intake.sourceJournal(journal));
    }
    @Test void insecureExistingSourceDirectoryIsNotRepaired() throws Exception {
        Path source=Files.createDirectory(journal.resolve("sources"));
        Files.setPosixFilePermissions(source,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(java.io.IOException.class,()->Intake.sourceJournal(journal));
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(source));
    }
}
