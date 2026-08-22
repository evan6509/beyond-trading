package com.beyondtrading.llm;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class LLMInterface {
    private static final Gson GSON = new Gson();
    private static final Semaphore REQUEST_SLOTS = new Semaphore(2);
    private static final Set<CompletableFuture<?>> ACTIVE_HTTP_REQUESTS =
        ConcurrentHashMap.newKeySet();
    private static volatile boolean shuttingDown;
    // Thinking models may spend most of the completion budget before emitting
    // the short JSON answer. Qwen's reasoning mode demonstrably exhausted
    // 1,024 tokens with an empty final content field, so the action budget
    // must include room for hidden reasoning as well as the answer.
    private static final int STRUCTURED_MAX_TOKENS = 4_096;
    private static final int THINKING_MAX_TOKENS = 8_192;
    private final HttpClient httpClient;

    public LLMInterface() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** Cancel blocking model work before a Minecraft server instance stops. */
    public static void beginShutdown() {
        shuttingDown = true;
        ACTIVE_HTTP_REQUESTS.forEach(request -> request.cancel(true));
        ACTIVE_HTTP_REQUESTS.clear();
    }

    /** Allow a later server instance in the same JVM to use the local model. */
    public static void resetShutdownState() {
        shuttingDown = false;
    }

    private record Endpoint(String baseUrl, String apiKey) {}

    private Endpoint resolve() {
        String baseUrl = ModelListFetcher.getWorkingBaseUrl();
        String apiKey = ModelListFetcher.getWorkingApiKey();
        if (baseUrl == null) {
            var config = ModConfig.get().llm;
            baseUrl = config.baseUrl();
            apiKey = config.apiKey();
        }
        return new Endpoint(baseUrl, apiKey);
    }

    public Optional<String> ask(List<Map<String, String>> messages) {
        if (!acquireRequestSlot()) return Optional.empty();
        try {
            var ep = resolve();
            JsonObject body = buildBody(ep, messages, false);
            JsonObject root = sendWithReasoningFallback(ep, body);
            if (root == null) return Optional.empty();

            Optional<String> content = extractMessageContent(root);
            if (content.isEmpty() && reasoningIsEnabled(body)) {
                // Some local thinking servers return HTTP 200 with no final
                // content after exhausting the reasoning budget. Preserve a
                // reliable player-facing answer by retrying deterministically.
                body.addProperty("reasoning_effort", "none");
                content = extractMessageContent(send(ep, body));
            }
            return content;
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.error("LLM request failed", e);
            return Optional.empty();
        } finally {
            REQUEST_SLOTS.release();
        }
    }

    public Optional<LLMResponse> chatStructured(List<Map<String, String>> messages) {
        if (!acquireRequestSlot()) return Optional.empty();
        try {
            var ep = resolve();
            JsonObject body = buildBody(ep, messages, true);
            JsonObject root = sendWithReasoningFallback(ep, body);
            // Some OpenAI-compatible local servers accept chat completions
            // but reject the optional response_format field. The prompt and
            // parser still enforce the bounded JSON contract, so retry once
            // without that optional transport feature before falling back.
            Optional<String> raw = root == null
                ? Optional.empty() : extractMessageContent(root);
            if (raw.isEmpty() && body.has("response_format")) {
                body.remove("response_format");
                root = sendWithReasoningFallback(ep, body);
                raw = root == null
                    ? Optional.empty() : extractMessageContent(root);
            }
            if (raw.isEmpty() && reasoningIsEnabled(body)) {
                body.remove("response_format");
                body.addProperty("reasoning_effort", "none");
                root = send(ep, body);
                raw = root == null
                    ? Optional.empty() : extractMessageContent(root);
            }
            if (raw.isEmpty()) {
                // A model may advertise a large context while its currently
                // loaded quantization or server settings allow less. Retry
                // with the high-value beginning of the prompt and a smaller
                // response budget before declaring the agent offline.
                JsonObject compactBody = buildBody(ep,
                    PromptContext.compactMessages(messages, 8_000), true);
                compactBody.remove("response_format");
                compactBody.addProperty("max_tokens", maxTokens(true));
                root = sendWithReasoningFallback(ep, compactBody);
                raw = root == null
                    ? Optional.empty() : extractMessageContent(root);
            }
            return raw.map(LLMResponseParser::parse);
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.error("LLM request failed", e);
            return Optional.empty();
        } finally {
            REQUEST_SLOTS.release();
        }
    }

    private boolean acquireRequestSlot() {
        try {
            if (REQUEST_SLOTS.tryAcquire(100, TimeUnit.MILLISECONDS)) return true;
            BeyondTradingMod.LOGGER.debug("LLM request budget is full; using agent fallback");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private JsonObject buildBody(Endpoint ep, List<Map<String, String>> messages, boolean structured) {
        var config = ModConfig.get().llm;
        JsonObject body = new JsonObject();
        String model = config.model();
        var availableModels = ModelListFetcher.getCachedModels();
        if (!availableModels.isEmpty() && !availableModels.contains(model)) {
            String fallbackModel = availableModels.get(0);
            BeyondTradingMod.LOGGER.debug("Configured model {} is unavailable; using {}",
                model, fallbackModel);
            model = fallbackModel;
        }
        body.addProperty("model", model);
        body.add("messages", GSON.toJsonTree(messages));
        body.addProperty("temperature", 0.7);
        // Villager decisions and replies are short. Keeping this bounded is
        // especially important for thinking models, where the same budget
        // covers hidden reasoning and the final JSON response.
        body.addProperty("max_tokens", maxTokens(structured));
        body.addProperty("stream", false);
        String reasoningEffort = config.reasoningEffort();
        if (reasoningEffort != null && !reasoningEffort.isBlank()
            && !reasoningEffort.equalsIgnoreCase("auto")) {
            body.addProperty("reasoning_effort", transportReasoningEffort(reasoningEffort));
        }
        if (structured) {
            // Leave reasoning controls to the model/server. Thinking-capable
            // models may expose different settings (for example Qwen's
            // native on/off mode), while max_tokens still bounds reasoning
            // plus the final action.
            JsonObject fmt = new JsonObject();
            // LM Studio accepts the OpenAI-compatible text mode here. The
            // exact JSON contract is still carried by the system prompt and
            // enforced again by parseStructured/NPCAction.parse.
            fmt.addProperty("type", "text");
            body.add("response_format", fmt);
        }
        return body;
    }

    /**
     * Negotiate reasoning without making the model-specific setting a hard
     * dependency. LM Studio can expose a native on/off setting for Qwen even
     * though its OpenAI-compatible endpoint also understands effort names;
     * try the native value first, then the common compatible value, then turn
     * reasoning off so the villager still has a bounded recovery path.
     */
    private JsonObject sendWithReasoningFallback(Endpoint ep, JsonObject body) {
        JsonObject root = send(ep, body);
        if (root != null || !body.has("reasoning_effort")) return root;

        String effort = body.get("reasoning_effort").getAsString();
        List<String> alternatives = switch (effort.toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> List.of("low", "none");
            case "off" -> List.of("none");
            default -> List.of("none");
        };
        for (String alternative : alternatives) {
            if (alternative.equals("none")) body.remove("reasoning_effort");
            else body.addProperty("reasoning_effort", alternative);
            root = send(ep, body);
            if (root != null) return root;
        }
        return null;
    }

    private int maxTokens(boolean structured) {
        String effort = ModConfig.get().llm.reasoningEffort();
        boolean thinking = effort != null
            && !effort.isBlank()
            && !effort.equalsIgnoreCase("none")
            && !effort.equalsIgnoreCase("off")
            && !effort.equalsIgnoreCase("auto");
        if (thinking) return THINKING_MAX_TOKENS;
        return structured ? STRUCTURED_MAX_TOKENS : 2_048;
    }

    private boolean reasoningIsEnabled(JsonObject body) {
        if (!body.has("reasoning_effort")) return false;
        String effort = body.get("reasoning_effort").getAsString();
        return !effort.equalsIgnoreCase("none")
            && !effort.equalsIgnoreCase("off")
            && !effort.equalsIgnoreCase("auto");
    }

    private String transportReasoningEffort(String configured) {
        // Preserve native on/off settings for models such as Qwen. If an
        // endpoint rejects one, sendWithReasoningFallback tries a compatible
        // effort value and finally retries without reasoning.
        if (configured.equalsIgnoreCase("off")) return "none";
        return configured;
    }

    private JsonObject send(Endpoint ep, JsonObject body) {
        if (shuttingDown) return null;
        try {
            String url = ep.baseUrl.replaceAll("/+$", "") + "/chat/completions";
            URI uri = URI.create(url);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ep.apiKey)
                .timeout(Duration.ofMillis(ModConfig.get().llm.timeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

            CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
            ACTIVE_HTTP_REQUESTS.add(pending);
            HttpResponse<String> response;
            try {
                response = pending.join();
            } finally {
                ACTIVE_HTTP_REQUESTS.remove(pending);
            }

            if (response.statusCode() != 200) {
                String detail = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
                if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
                BeyondTradingMod.LOGGER.warn("LLM returned {} from {}: {}",
                    response.statusCode(), url, detail);
                if (!shuttingDown) ModelListFetcher.refreshInBackground();
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            if (!shuttingDown) {
                BeyondTradingMod.LOGGER.error("LLM request to {} failed", ep.baseUrl, e);
            }
            if (!shuttingDown) ModelListFetcher.refreshInBackground();
            return null;
        }
    }

    private Optional<String> extractMessageContent(JsonObject root) {
        try {
            JsonElement choices = root.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
                return Optional.empty();
            }
            JsonObject message = choices.getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonObject("message");
            if (message == null) return Optional.empty();

            JsonElement content = message.get("content");
            if (content == null || content.isJsonNull()) return Optional.empty();
            if (content.isJsonPrimitive()) {
                String text = content.getAsString();
                return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
            }
            if (!content.isJsonArray()) return Optional.empty();

            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonPrimitive()) {
                    text.append(part.getAsString());
                } else if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    JsonElement value = part.getAsJsonObject().get("text");
                    if (value.isJsonPrimitive()) text.append(value.getAsString());
                }
            }
            return text.isEmpty() || text.toString().isBlank()
                ? Optional.empty() : Optional.of(text.toString());
        } catch (RuntimeException e) {
            BeyondTradingMod.LOGGER.debug("LLM response did not contain readable message content", e);
            return Optional.empty();
        }
    }
}
