package com.beyondtrading.llm;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LLMInterface {
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient;

    public LLMInterface() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public Optional<LLMResponse> chat(List<Map<String, String>> messages) {
        var config = ModConfig.get().llm;

        String baseUrl = ModelListFetcher.getWorkingBaseUrl();
        String apiKey = ModelListFetcher.getWorkingApiKey();
        if (baseUrl == null) {
            baseUrl = config.baseUrl();
            apiKey = config.apiKey();
        }

        try {
            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            URI uri = URI.create(url);

            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.add("messages", GSON.toJsonTree(messages));
            body.addProperty("temperature", 0.7);
            body.addProperty("max_tokens", 2048);
            body.addProperty("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                BeyondTradingMod.LOGGER.warn("LLM returned {} from {}", response.statusCode(), url);
                return Optional.empty();
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = root
                .getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

            return Optional.of(new LLMResponse(content));
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.error("LLM request to {} failed", baseUrl, e);
            return Optional.empty();
        }
    }
}
