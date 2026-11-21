package org.concordia.babycdf;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LlmSettingsTest {
    @Test void defaultsAndMissingKey() {
        var s = LlmSettings.fromEnvironment(Map.of());
        assertEquals(LlmSettings.DEFAULT_BASE + "/chat/completions", s.endpoint().toString());
        assertEquals(LlmSettings.DEFAULT_MODEL, s.model());
        assertEquals("missing_api_key", s.disabledReason());
    }
    @Test void precedenceAndBlankFallback() {
        var s = LlmSettings.fromEnvironment(Map.of("LLM_BASE_URL", " ", "OPENAI_BASE_URL", "https://example.test/custom/",
                "LLM_MODEL", " ", "OPENAI_MODEL", "chosen", "OPENAI_API_KEY", "provider", "OMNIROUTE_API_KEY", "gateway"));
        assertEquals("https://example.test/custom/chat/completions", s.endpoint().toString());
        assertEquals("chosen", s.model()); assertEquals("Bearer provider", s.authorization());
        assertEquals("Bearer gateway", LlmSettings.fromEnvironment(Map.of("OPENAI_API_KEY", "provider", "OMNIROUTE_API_KEY", "gateway")).authorization());
        assertEquals("Bearer neutral", LlmSettings.fromEnvironment(Map.of("LLM_API_KEY", "neutral", "OPENAI_API_KEY", "provider")).authorization());
    }
    @Test void rejectsUnsafeEndpointsWithoutEchoingSecrets() {
        for (String base : new String[]{"relative", "ftp://host", "https:///path", "https://user:sentinel@host", "https://host?q=x",
                "https://host#frag", "https://ho st", "http://localhost"}) {
            var s = LlmSettings.fromEnvironment(Map.of("LLM_BASE_URL", base, "LLM_API_KEY", "sentinel"));
            assertEquals("invalid_configuration", s.disabledReason(), base);
            assertFalse(s.toString().contains("sentinel"));
        }
    }
    @Test void neutralVariablesWin() {
        var s = LlmSettings.fromEnvironment(Map.of("LLM_BASE_URL", "https://first.test/v2", "OPENAI_BASE_URL", "https://second.test",
                "LLM_MODEL", "first", "OPENAI_MODEL", "second", "LLM_API_KEY", "sentinel"));
        assertEquals("https://first.test/v2/chat/completions", s.endpoint().toString());
        assertEquals("first", s.model()); assertFalse(s.toString().contains("sentinel"));
    }
}
