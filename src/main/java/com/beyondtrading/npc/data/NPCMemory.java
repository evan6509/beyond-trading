package com.beyondtrading.npc.data;

import com.beyondtrading.BeyondTradingMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        } catch (IOException e) {
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
            Files.writeString(file, sb.toString());
            dirty = false;
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to save memories to {}", file, e);
        }
    }

    public void remember(int day, String event, int importance) {
        entries.add(new MemoryEntry(day, event, importance));
        entries.sort(Comparator.comparingInt(e -> -e.importance()));
        if (entries.size() > MAX_MEMORIES) {
            entries.subList(MAX_MEMORIES, entries.size()).clear();
        }
        dirty = true;
        save();
    }

    public void flush() {
        if (dirty) save();
    }

    public String formatForPrompt() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nMemories:\n");
        int count = 0;
        for (MemoryEntry e : entries) {
            if (count >= 10) break;
            sb.append("- Day ").append(e.day()).append(": ").append(e.event());
            sb.append(" (importance: ").append(e.importance()).append(")\n");
            count++;
        }
        return sb.toString();
    }

    public List<MemoryEntry> getEntries() {
        return List.copyOf(entries);
    }
}
