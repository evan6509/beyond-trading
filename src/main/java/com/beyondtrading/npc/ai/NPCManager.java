package com.beyondtrading.npc.ai;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.MemoryEntry;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCMemory;
import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCManager {
    private static final Gson GSON = new Gson();
    private static final Map<MinecraftServer, NPCManager> INSTANCES = new HashMap<>();

    private final MinecraftServer server;
    private final LLMInterface llm;
    private final LLMPromptBuilder promptBuilder;
    private final ActionValidator actionValidator;
    private final Map<UUID, NPCData> npcDataMap;
    private final Map<UUID, NPCMemory> npcMemoryMap;
    private final Map<String, NPCData> templates;

    private NPCManager(MinecraftServer server) {
        this.server = server;
        this.llm = new LLMInterface(ModConfig.get().llm);
        this.promptBuilder = new LLMPromptBuilder();
        this.actionValidator = new ActionValidator();
        this.npcDataMap = new ConcurrentHashMap<>();
        this.npcMemoryMap = new ConcurrentHashMap<>();
        this.templates = new HashMap<>();
        loadTemplates();
    }

    public static NPCManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, NPCManager::new);
    }

    private void loadTemplates() {
        try {
            InputStream is = getClass().getClassLoader()
                .getResourceAsStream("data/beyondtrading/npcs/thomas.json");
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                NPCData thomas = GSON.fromJson(json, NPCData.class);
                templates.put("thomas", thomas);
                BeyondTradingMod.LOGGER.info("Loaded NPC template: Thomas");
            }
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to load NPC templates", e);
        }
    }

    public void initializeNPC(UUID uuid, String templateName, AINPCEntity entity) {
        NPCData template = templates.get(templateName.toLowerCase());
        if (template == null) {
            BeyondTradingMod.LOGGER.warn("Unknown NPC template: {}", templateName);
            return;
        }
        npcDataMap.put(uuid, template);
        ServerLevel level = (ServerLevel) entity.level();
        NPCMemory memory = new NPCMemory(template.name, server.getWorldPath(
            net.minecraft.world.level.storage.LevelResource.ROOT));
        npcMemoryMap.put(uuid, memory);
        entity.setCustomName(net.minecraft.network.chat.Component.literal(template.name));
    }

    public LLMResponse processChat(UUID npcUuid, Player player, String message) {
        NPCData npc = npcDataMap.get(npcUuid);
        NPCMemory memory = npcMemoryMap.get(npcUuid);
        if (npc == null || memory == null) {
            return new LLMResponse("STAND_STILL", "...", null);
        }

        String situation = buildSituation(player);
        var messages = promptBuilder.build(npc, memory, situation, message);
        LLMResponse response = llm.chat(messages);
        String validatedAction = actionValidator.validate(response.action());
        response = new LLMResponse(validatedAction, response.dialogue(), response.memoryUpdate());

        if (response.hasMemoryUpdate()) {
            int day = (int) (server.overworld().getOverworldClockTime() / 24000);
            memory.addMemory(day, response.memoryUpdate(), 70);
        }

        return response;
    }

    private String buildSituation(Player player) {
        long time = server.overworld().getOverworldClockTime() % 24000;
        String timeOfDay = time < 13000 ? "Daytime" : "Night is approaching or it is dark";
        return String.format("Time: %s. Player %s is nearby.", timeOfDay, player.getScoreboardName());
    }

    public NPCData getNPCData(UUID uuid) {
        return npcDataMap.get(uuid);
    }

    public NPCMemory getMemory(UUID uuid) {
        return npcMemoryMap.get(uuid);
    }

    public List<MemoryEntry> getMemories(UUID uuid) {
        NPCMemory memory = npcMemoryMap.get(uuid);
        return memory != null ? memory.getMemories() : List.of();
    }

    public String formatMemoriesForPrompt(UUID uuid) {
        NPCMemory memory = npcMemoryMap.get(uuid);
        return memory != null ? memory.formatForPrompt() : "No memories.";
    }
}
