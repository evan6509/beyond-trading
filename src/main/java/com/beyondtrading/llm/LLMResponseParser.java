package com.beyondtrading.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Parses the final assistant message without treating hidden reasoning as an action. */
public final class LLMResponseParser {
    private static final String SAFE_FAILURE_DIALOGUE = "I need a moment to think.";
    private static final int MAX_ACTION_CHARS = 40;
    private static final int MAX_DIALOGUE_CHARS = 320;
    private static final int MAX_GOAL_CHARS = 180;

    private LLMResponseParser() {}

    /**
     * Accepts a plain JSON object, a fenced JSON object, or a final JSON object
     * following a visible thinking/analysis block. Invalid output remains a
     * safe conversational response with no executable action.
     */
    public static LLMResponse parse(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        normalized = removeThinkingBlocks(normalized);

        JsonObject object = findObject(normalized);
        // Never expose hidden reasoning, partial JSON, or an arbitrary model
        // dump as villager dialogue. The brain will treat the missing action
        // as invalid and use its bounded fallback routine.
        if (object == null) return new LLMResponse(null, SAFE_FAILURE_DIALOGUE);

        String action = bounded(stringValue(object, "action"), MAX_ACTION_CHARS);
        String dialogue = bounded(stringValue(object, "dialogue"), MAX_DIALOGUE_CHARS);
        String goal = bounded(stringValue(object, "goal"), MAX_GOAL_CHARS);
        return new LLMResponse(action,
            dialogue == null || dialogue.isBlank() ? "Hello!" : dialogue, goal);
    }

    private static String removeThinkingBlocks(String text) {
        String result = text;
        result = result.replaceAll("(?is)<think>.*?</think>", "");
        result = result.replaceAll("(?is)<analysis>.*?</analysis>", "");
        result = result.trim();
        if (result.startsWith("```") && result.endsWith("```")) {
            int firstLineEnd = result.indexOf('\n');
            result = firstLineEnd >= 0
                ? result.substring(firstLineEnd + 1, result.length() - 3).trim()
                : result.substring(3, result.length() - 3).trim();
        }
        return result;
    }

    private static JsonObject findObject(String text) {
        if (text == null || text.isBlank()) return null;
        JsonObject direct = tryObject(text);
        if (direct != null) return direct;

        // Models sometimes add one sentence before or after the JSON. Try
        // candidate starts from the end so a final answer wins over an object
        // mentioned in visible reasoning.
        int end = text.lastIndexOf('}');
        if (end < 0) return null;
        for (int start = text.lastIndexOf('{', end); start >= 0; start = text.lastIndexOf('{', start - 1)) {
            JsonObject candidate = tryObject(text.substring(start, end + 1));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static JsonObject tryObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text.trim());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) return null;
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) return compact;
        return compact.substring(0, maxChars - 1).trim() + "…";
    }
}
