package com.beyondtrading.npc.data;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Decides whether a new model goal is a new priority or a paraphrase. */
public final class GoalProgressPolicy {
    private GoalProgressPolicy() {}

    public static boolean meaningfullyDifferent(String current, String candidate) {
        if (current == null || current.isBlank() || isPlaceholder(current)) return true;
        Set<String> currentWords = words(current);
        Set<String> candidateWords = words(candidate);
        if (currentWords.isEmpty() || candidateWords.isEmpty()) return true;

        Set<String> shared = new HashSet<>(currentWords);
        shared.retainAll(candidateWords);
        double overlap = (double) shared.size()
            / Math.min(currentWords.size(), candidateWords.size());
        return overlap < 0.5;
    }

    private static Set<String> words(String goal) {
        Set<String> words = new HashSet<>();
        for (String word : goal.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= 3) words.add(word);
        }
        return words;
    }

    private static boolean isPlaceholder(String goal) {
        return goal.equalsIgnoreCase("none")
            || goal.equalsIgnoreCase("null")
            || goal.equalsIgnoreCase("no goal");
    }
}
