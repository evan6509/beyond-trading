package com.beyondtrading.config;

public record LLMConfig(
    String baseUrl,
    String apiKey,
    String model,
    int timeoutMs
) {
    public static LLMConfig defaults() {
        return new LLMConfig(
            "http://localhost:8000/v1",
            "token-abc123",
            "Qwen/Qwen2.5-9B-Instruct",
            30000
        );
    }
}
