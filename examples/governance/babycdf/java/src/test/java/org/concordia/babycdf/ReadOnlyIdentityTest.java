package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyIdentityTest {
    @Test void queryGatewayUsesOnlyStateServices() {
        for (var field : Reviewer.LocalSandboxGateway.class.getDeclaredFields())
            assertFalse(field.getType().getName().contains("CommandService"));
        assertDoesNotThrow(() -> new Reviewer.LocalSandboxGateway(6865, "opener", "a".repeat(64)));
    }

    @Test void advisoryRejectsDuplicateReadOnlyConfigurationBeforePolling() {
        var error = assertThrows(IllegalArgumentException.class, () -> Advisory.main(new String[] {
            "--read-only-sandbox", "6865", "opener", "a".repeat(64),
            "--read-only-sandbox", "6865", "opener", "a".repeat(64)
        }));
        assertNotNull(error.getMessage());
    }
}
