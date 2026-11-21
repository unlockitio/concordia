package org.concordia.babycdf;

import java.net.URI;
import java.util.Map;

/** Provider configuration; credentials deliberately have no public accessor or diagnostic form. */
public final class LlmSettings {
    public static final String DEFAULT_BASE = "https://ai-gateway.unlockit.io/v1";
    public static final String DEFAULT_MODEL = "claude/claude-sonnet-4-6";
    private final URI endpoint;
    private final String model;
    private final String key;
    private final String disabled;

    private LlmSettings(URI endpoint, String model, String key, String disabled) {
        this.endpoint = endpoint; this.model = model; this.key = key; this.disabled = disabled;
    }

    public static LlmSettings fromEnvironment(Map<String, String> env) {
        String base = first(env, "LLM_BASE_URL", "OPENAI_BASE_URL");
        String model = first(env, "LLM_MODEL", "OPENAI_MODEL");
        String key = first(env, "LLM_API_KEY");
        if (key == null) key = base == null ? first(env, "OMNIROUTE_API_KEY", "OPENAI_API_KEY")
                : first(env, "OPENAI_API_KEY", "OMNIROUTE_API_KEY");
        try {
            if (env.containsKey("LLM_API_KEY_FILE")) {
                var path = java.nio.file.Path.of(env.get("LLM_API_KEY_FILE"));
                if (!path.isAbsolute() || java.nio.file.Files.isSymbolicLink(path)
                        || !java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException();
                try (var input = java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes = input.readNBytes(4098);
                    if (bytes.length == 0 || bytes.length > 4097) throw new IllegalArgumentException();
                    for(byte b:bytes) if(b!=10 && (b<33 || b>126)) throw new IllegalArgumentException();
                    key = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
                    if (key.endsWith("\n")) key = key.substring(0, key.length()-1);
                    if (key.isEmpty() || key.length() > 4096 || key.chars().anyMatch(c -> c < 33 || c > 126))
                        throw new IllegalArgumentException();
                } catch (java.io.IOException failure) { throw new IllegalArgumentException(); }
            }
            String raw = base == null ? DEFAULT_BASE : base;
            URI uri = URI.create(raw);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || raw.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException();
            // Never send credentials over cleartext, including loopback, in production.
            if (key != null && !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            if (key != null && key.chars().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException();
            String resolvedModel = model == null ? DEFAULT_MODEL : model;
            if (resolvedModel.length() > 200 || resolvedModel.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException();
            return new LlmSettings(URI.create(raw.replaceAll("/+$", "") + "/chat/completions"),
                    resolvedModel, key, key == null ? "missing_api_key" : null);
        } catch (RuntimeException ignored) {
            return new LlmSettings(null, DEFAULT_MODEL, null, "invalid_configuration");
        }
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
    public URI endpoint() { return endpoint; }
    public String model() { return model; }
    public String disabledReason() { return disabled; }
    String authorization() { return "Bearer " + key; }
    @Override public String toString() { return "LlmSettings[credentials=REDACTED]"; }
}
