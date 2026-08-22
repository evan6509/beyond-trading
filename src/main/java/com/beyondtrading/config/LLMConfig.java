package com.beyondtrading.config;

public record LLMConfig(
    String baseUrl,
    String apiKey,
    String model,
    int timeoutMs,
    String reasoningEffort
) {
    /** Backward-compatible constructor for existing configs and callers. */
    public LLMConfig(String baseUrl, String apiKey, String model, int timeoutMs) {
        this(baseUrl, apiKey, model, timeoutMs, "none");
    }

    public LLMConfig {
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
            ? "none" : reasoningEffort.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public static LLMConfig defaults() {
        return new LLMConfig(
            "http://localhost:8000/v1",
            "token-abc123",
            "Qwen/Qwen2.5-9B-Instruct",
            120000,
            "none"
        );
    }
}
