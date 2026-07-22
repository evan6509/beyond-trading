package com.beyondtrading.npc.ai;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.LLMConfig;
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

public class LLMInterface {
    private static final Gson GSON = new Gson();
    private final HttpClient httpClient;
    private final LLMConfig config;

    public LLMInterface(LLMConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public LLMResponse chat(List<Map<String, String>> messages) {
        try {
            String baseUrl = config.baseUrl().replaceAll("/+$", "");
            URI uri = URI.create(baseUrl + "/chat/completions");

            JsonObject body = new JsonObject();
            body.addProperty("model", config.model());
            body.add("messages", GSON.toJsonTree(messages));
            body.addProperty("temperature", 0.7);
            body.addProperty("max_tokens", 256);
            body.addProperty("stream", false);

            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);

            String jsonBody = GSON.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                BeyondTradingMod.LOGGER.warn("LLM API returned {}: {}",
                    response.statusCode(), response.body());
                return fallbackResponse();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.error("LLM request failed", e);
            return fallbackResponse();
        }
    }

    private LLMResponse parseResponse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String content = root
                .getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

            JsonObject result = JsonParser.parseString(content).getAsJsonObject();
            String action = result.get("action").getAsString();
            String dialogue = result.get("dialogue").getAsString();
            String memoryUpdate = result.has("memory_update")
                && !result.get("memory_update").isJsonNull()
                ? result.get("memory_update").getAsString() : null;

            return new LLMResponse(action, dialogue, memoryUpdate);
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.error("Failed to parse LLM response: {}", json, e);
            return fallbackResponse();
        }
    }

    private LLMResponse fallbackResponse() {
        return new LLMResponse("STAND_STILL", "...", null);
    }
}
