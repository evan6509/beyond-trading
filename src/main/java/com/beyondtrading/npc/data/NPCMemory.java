package com.beyondtrading.npc.data;

import com.beyondtrading.BeyondTradingMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NPCMemory {
    private static final int MAX_MEMORIES = 20;
    private static final Pattern MEMORY_PATTERN = Pattern.compile(
        "### Day (\\d+) — (.+)\\nImportance: (\\d+)/100",
        Pattern.MULTILINE
    );

    private final String npcName;
    private final List<MemoryEntry> memories;
    private final Path memoryFile;

    public NPCMemory(String npcName, Path worldDir) {
        this.npcName = npcName;
        this.memories = new ArrayList<>();
        this.memoryFile = worldDir.resolve("beyondtrading/memories/" + npcName.toLowerCase() + "_memory.md");
        load();
    }

    private void load() {
        if (!Files.exists(memoryFile)) {
            return;
        }
        try {
            String content = Files.readString(memoryFile);
            Matcher matcher = MEMORY_PATTERN.matcher(content);
            while (matcher.find()) {
                int day = Integer.parseInt(matcher.group(1));
                String event = matcher.group(2);
                int importance = Integer.parseInt(matcher.group(3));
                memories.add(new MemoryEntry(day, event, importance));
            }
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to load memories for {}", npcName, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(memoryFile.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(npcName).append("\n\n");
            sb.append("## Memories\n\n");
            for (MemoryEntry m : memories) {
                sb.append("### Day ").append(m.day).append(" — ").append(m.event).append("\n");
                sb.append("Importance: ").append(m.importance).append("/100\n\n");
            }
            Files.writeString(memoryFile, sb.toString());
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to save memories for {}", npcName, e);
        }
    }

    public void addMemory(int day, String event, int importance) {
        memories.add(new MemoryEntry(day, event, importance));
        memories.sort(Comparator.comparingInt(a -> -a.importance));
        if (memories.size() > MAX_MEMORIES) {
            memories.subList(MAX_MEMORIES, memories.size()).clear();
        }
        save();
    }

    public List<MemoryEntry> getMemories() {
        return List.copyOf(memories);
    }

    public String formatForPrompt() {
        if (memories.isEmpty()) {
            return "No significant memories yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry m : memories) {
            sb.append("- Day ").append(m.day).append(": ").append(m.event);
            sb.append(" (importance: ").append(m.importance).append(")\n");
        }
        return sb.toString();
    }
}
