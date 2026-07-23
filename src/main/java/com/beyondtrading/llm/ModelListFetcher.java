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

    private static List<String> cachedModels = List.of();

    public static List<String> getCachedModels() {
        return cachedModels;
    }

    public static void refresh() {
        var config = ModConfig.get().llm;
        try {
            String baseUrl = config.baseUrl().replaceAll("/+$", "");
            String modelsUrl = baseUrl.substring(0, baseUrl.lastIndexOf("/v1") + 3) + "/models";
            URI uri = URI.create(modelsUrl);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                BeyondTradingMod.LOGGER.warn("Models list returned {}", response.statusCode());
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            List<String> models = new ArrayList<>();
            for (var elem : data) {
                models.add(elem.getAsJsonObject().get("id").getAsString());
            }
            Collections.sort(models);
            cachedModels = List.copyOf(models);
            BeyondTradingMod.LOGGER.info("Cached {} model(s): {}", models.size(), models);
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.warn("Failed to fetch model list", e);
        }
    }
}
