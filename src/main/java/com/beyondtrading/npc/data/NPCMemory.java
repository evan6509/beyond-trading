package com.beyondtrading.npc.data;

import com.beyondtrading.BeyondTradingMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NPCMemory {
    private static final int MAX_MEMORIES = 20;
    private static final Pattern MEMORY_PATTERN = Pattern.compile(
        "### Day (\\d+) — (.+)\\nImportance: (\\d+)/100",
        Pattern.MULTILINE
    );

    private final List<MemoryEntry> entries;
    private final Path file;
    private boolean dirty;

    public NPCMemory(UUID npcUUID, Path worldDir) {
        this.entries = new ArrayList<>();
        this.file = worldDir.resolve("beyondtrading/memories/" + npcUUID + ".md");
        this.dirty = false;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file);
            Matcher m = MEMORY_PATTERN.matcher(content);
            while (m.find()) {
                entries.add(new MemoryEntry(
                    Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3))));
            }
            entries.sort(Comparator.comparingInt(MemoryEntry::day));
            int loadedCount = entries.size();
            Set<String> seen = new HashSet<>();
            entries.removeIf(entry -> !seen.add(eventKey(entry.day(), entry.event())));
            if (entries.size() > MAX_MEMORIES) {
                entries.sort(Comparator
                    .comparingInt(MemoryEntry::importance)
                    .thenComparingInt(MemoryEntry::day));
                while (entries.size() > MAX_MEMORIES) entries.remove(0);
                entries.sort(Comparator.comparingInt(MemoryEntry::day));
            }
            dirty = entries.size() != loadedCount;
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.warn("Failed to load memories from {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder("# Memories\n\n");
            for (MemoryEntry e : entries) {
                sb.append("### Day ").append(e.day()).append(" — ").append(e.event()).append("\n");
                sb.append("Importance: ").append(e.importance()).append("/100\n\n");
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, sb.toString());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to save memories to {}", file, e);
        }
    }

    public synchronized void remember(int day, String event, int importance) {
        if (event == null || event.isBlank()) return;
        String compact = event.replaceAll("\\s+", " ").trim();
        String normalized = compact.length() > 240
            ? compact.substring(0, 240) + "…" : compact;
        if (entries.stream().anyMatch(existing -> eventKey(existing.day(), existing.event())
            .equals(eventKey(day, normalized)))) {
            return;
        }
        entries.add(new MemoryEntry(day, normalized, Math.max(0, Math.min(100, importance))));
        if (entries.size() > MAX_MEMORIES) {
            entries.sort(Comparator
                .comparingInt(MemoryEntry::importance)
                .thenComparingInt(MemoryEntry::day));
            entries.remove(0);
            entries.sort(Comparator.comparingInt(MemoryEntry::day));
        }
        dirty = true;
    }

    public synchronized void flush() {
        if (dirty) save();
    }

    public synchronized String formatForPrompt() {
        int currentDay = entries.stream().mapToInt(MemoryEntry::day).max().orElse(0);
        return formatForPrompt(currentDay);
    }

    /** Select memories by salience and recency instead of importance alone. */
    public synchronized String formatForPrompt(int currentDay) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nMemories:\n");
        var relevant = entries.stream()
            .sorted(Comparator.comparingInt((MemoryEntry e) -> relevance(e, currentDay)).reversed()
                .thenComparing(Comparator.comparingInt(MemoryEntry::day).reversed()))
            .limit(10)
            .toList();
        for (MemoryEntry e : relevant) {
            sb.append("- Day ").append(e.day()).append(": ").append(e.event());
            sb.append(" (importance: ").append(e.importance()).append(")\n");
        }
        return sb.toString();
    }

    private int relevance(MemoryEntry entry, int currentDay) {
        int age = Math.max(0, currentDay - entry.day());
        int recency = Math.max(0, 30 - age) * 5;
        return entry.importance() + recency;
    }

    private static String eventKey(int day, String event) {
        return day + "\u0000" + event;
    }

    public synchronized List<MemoryEntry> getEntries() {
        return List.copyOf(entries);
    }
}
