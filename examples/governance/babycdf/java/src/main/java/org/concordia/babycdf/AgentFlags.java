// SPDX-License-Identifier: Apache-2.0
package org.concordia.babycdf;

/** Shared, side-effect-free reading of the agents' presentation switches. */
final class AgentFlags {
    private AgentFlags() { }

    static final String VERBOSE_ENV = "DEMO_AGENT_VERBOSE";

    /**
     * Whether the environment asks for the full machine-readable output.
     *
     * <p>Unset, empty and {@code 0} all mean "no", so the demo default stays quiet unless the
     * operator deliberately turns detail back on. Any other value is an explicit yes.
     */
    static boolean verboseFromEnvironment() {
        return verbose(System.getenv(VERBOSE_ENV));
    }

    static boolean verbose(String value) {
        return value != null && !value.isBlank() && !"0".equals(value.trim());
    }
}
