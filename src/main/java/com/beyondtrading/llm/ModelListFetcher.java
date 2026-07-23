package com.beyondtrading.llm;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ModelListFetcher {
    private static final Gson GSON = new Gson();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final String[][] FALLBACKS = {
        {"http://localhost:1234/v1", "lm-studio"},
        {"http://localhost:8000/v1", "token-abc123"},
    };

    private static List<String> cachedModels = List.of();
    private static String workingBaseUrl;
    private static String workingApiKey;

    public static List<String> getCachedModels() {
        return cachedModels;
    }

    public static String getWorkingBaseUrl() {
        return workingBaseUrl;
    }

    public static String getWorkingApiKey() {
        return workingApiKey;
    }

    private static String buildModelsUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/models";
    }

    public static void refresh() {
        var config = ModConfig.get().llm;

        List<String[]> candidates = new ArrayList<>();
        candidates.add(new String[]{config.baseUrl(), config.apiKey()});
        for (var fb : FALLBACKS) {
            boolean dup = false;
            for (var c : candidates) {
                if (c[0].equals(fb[0])) { dup = true; break; }
            }
            if (!dup) candidates.add(fb);
        }

        for (var endpoint : candidates) {
            String baseUrl = endpoint[0].replaceAll("/+$", "");
            String apiKey = endpoint[1];
            String modelsUrl = buildModelsUrl(baseUrl);

            try {
                URI uri = URI.create(modelsUrl);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) continue;

                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray data = root.getAsJsonArray("data");
                List<String> models = new ArrayList<>();
                for (var elem : data) {
                    models.add(elem.getAsJsonObject().get("id").getAsString());
                }
                Collections.sort(models);
                cachedModels = List.copyOf(models);
                workingBaseUrl = baseUrl;
                workingApiKey = apiKey;
                BeyondTradingMod.LOGGER.info("Connected to {} — {} model(s) available", baseUrl, models.size());
                return;
            } catch (Exception e) {
                BeyondTradingMod.LOGGER.debug("No LLM at {}: {}", modelsUrl, e.getMessage());
            }
        }

        BeyondTradingMod.LOGGER.warn("No LLM endpoint found — tried {} and ports 1234, 8000",
            config.baseUrl());
    }
}
