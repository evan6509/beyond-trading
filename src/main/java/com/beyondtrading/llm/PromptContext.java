package com.beyondtrading.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Keeps prompts useful as a villager's life grows.
 *
 * Minecraft does not expose a tokenizer for the configured model, so the
 * budget is expressed in approximate tokens and converted using a
 * conservative four-characters-per-token estimate. Identity, danger, needs,
 * and goals are admitted before optional historical detail.
 */
public final class PromptContext {
    public static final int DECISION_TOKEN_BUDGET = 6_000;
    public static final int CONVERSATION_TOKEN_BUDGET = 4_500;
    private static final int APPROX_CHARS_PER_TOKEN = 4;

    private PromptContext() {}

    public record Section(String name, String content, int priority) {}

    /** Compose labeled sections within a deterministic approximate token budget. */
    public static String compose(int tokenBudget, List<Section> sections) {
        if (tokenBudget <= 0 || sections == null || sections.isEmpty()) return "";

        int charBudget = Math.multiplyExact(tokenBudget, APPROX_CHARS_PER_TOKEN);
        List<IndexedSection> ordered = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (section != null && section.content() != null && !section.content().isBlank()) {
                ordered.add(new IndexedSection(i, section));
            }
        }
        ordered.sort(Comparator
            .comparingInt((IndexedSection section) -> section.value().priority()).reversed()
            .thenComparingInt(IndexedSection::index));

        StringBuilder result = new StringBuilder(Math.min(charBudget, 16_000));
        for (IndexedSection indexed : ordered) {
            Section section = indexed.value();
            String label = section.name() == null || section.name().isBlank()
                ? "Context" : section.name().trim();
            String prefix = label + ":\n";
            int separatorLength = result.isEmpty() ? 0 : 1;
            int remaining = charBudget - result.length() - separatorLength;
            if (remaining <= prefix.length()) break;

            String original = section.content().trim();
            int contentBudget = remaining - prefix.length();
            String content = original.length() > contentBudget
                ? fit(original, contentBudget) : original;
            if (content.isBlank()) continue;

            if (!result.isEmpty()) result.append("\n");
            result.append(prefix).append(content);
            if (content.length() < original.length()) break;
        }
        return result.toString();
    }

    /** Trim at a line boundary where possible, without exceeding maxChars. */
    public static String fit(String text, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0) return "";
        String normalized = text.trim();
        if (normalized.length() <= maxChars) return normalized;

        String marker = "\n[context truncated]";
        if (maxChars <= marker.length()) return normalized.substring(0, maxChars);
        int contentLimit = maxChars - marker.length();
        int cut = normalized.lastIndexOf('\n', Math.max(0, contentLimit - 1));
        if (cut < contentLimit / 2) cut = contentLimit;
        String result = normalized.substring(0, Math.min(cut, normalized.length())).trim();
        return result + marker;
    }

    /**
     * Make a last-resort request payload for servers/models with a smaller
     * effective context than the configured target. The system prompt is
     * trimmed from the end, where lower-priority memories live.
     */
    public static List<Map<String, String>> compactMessages(
        List<Map<String, String>> messages, int systemCharacterBudget) {
        if (messages == null) return List.of();
        List<Map<String, String>> compact = new ArrayList<>();
        for (Map<String, String> message : messages) {
            if (message == null) continue;
            String role = message.getOrDefault("role", "user");
            String content = message.getOrDefault("content", "");
            int budget = role.equals("system") ? systemCharacterBudget : 2_000;
            compact.add(Map.of("role", role, "content", fit(content, budget)));
        }
        return List.copyOf(compact);
    }

    private record IndexedSection(int index, Section value) {}
}
