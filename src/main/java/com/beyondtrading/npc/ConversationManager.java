package com.beyondtrading.npc;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.WorldContext;
import com.beyondtrading.npc.behavior.NPCBehaviorController;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCMemory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationManager {
    private static final Gson GSON = new Gson();
    private static ConversationManager instance;
    private final ConcurrentHashMap<UUID, NPCSession> sessions = new ConcurrentHashMap<>();
    private final LLMInterface llm = new LLMInterface();
    private final NPCBehaviorController behavior = new NPCBehaviorController();

    private static final long TIMEOUT_MS = 120_000;
    private static final double MAX_DISTANCE = 15.0;

    private ConversationManager() {}

    public static ConversationManager get() {
        if (instance == null) instance = new ConversationManager();
        return instance;
    }

    public void engage(ServerPlayer player, AINPCEntity npc) {
        UUID pid = player.getUUID();
        NPCSession old = sessions.get(pid);
        if (old != null) {
            player.sendSystemMessage(Component.literal(
                "§7[You ended conversation with " + old.getNpc().getNpcName() + "]"));
        }

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        NPCMemory memory = new NPCMemory(npc.getNpcName(),
            server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));

        sessions.put(pid, new NPCSession(npc, player, memory));
        player.sendSystemMessage(Component.literal(
            "§e[You are now talking to " + npc.getNpcName() + " — type in chat]"));
        greet(player, npc);
    }

    private void greet(ServerPlayer player, AINPCEntity npc) {
        NPCSession session = sessions.get(player.getUUID());
        if (session == null) return;
        ServerLevel level = (ServerLevel) player.level();
        var server = level.getServer();

        CompletableFuture.runAsync(() -> {
            llm.chatStructured(buildMessages(session, "Hello!", true)).ifPresent(
                response -> server.execute(() -> handleResponse(session, response)));
        });
    }

    public void disengage(ServerPlayer player) {
        NPCSession session = sessions.remove(player.getUUID());
        if (session != null) {
            player.sendSystemMessage(Component.literal(
                "§7[" + session.getNpc().getNpcName() + " nods goodbye]"));
        }
    }

    public boolean isEngaged(ServerPlayer player) {
        return sessions.containsKey(player.getUUID());
    }

    public boolean handleChat(ServerPlayer player, String message) {
        NPCSession session = sessions.get(player.getUUID());
        if (session == null) return false;

        AINPCEntity npc = session.getNpc();
        if (!npc.isAlive() || npc.distanceToSqr(player) > MAX_DISTANCE * MAX_DISTANCE) {
            disengage(player);
            return false;
        }

        session.touch();
        ServerLevel level = (ServerLevel) player.level();
        var server = level.getServer();

        player.sendSystemMessage(Component.literal("§7[You to " + npc.getNpcName() + "] " + message));

        CompletableFuture.runAsync(() -> {
            llm.chatStructured(buildMessages(session, message, false)).ifPresentOrElse(
                response -> server.execute(() -> handleResponse(session, response)),
                () -> server.execute(() ->
                    player.sendSystemMessage(Component.literal(
                        "§c[" + npc.getNpcName() + " seems confused...]"))));
        });

        return true;
    }

    private void handleResponse(NPCSession session, LLMResponse response) {
        ServerPlayer player = session.getPlayer();
        AINPCEntity npc = session.getNpc();

        player.sendSystemMessage(Component.literal(
            "§e[" + npc.getNpcName() + "] " + response.dialogue()));
        session.addTurn(null, response.dialogue());

        behavior.execute(npc, response.action());

        if (response.hasAction()) {
            BeyondTradingMod.LOGGER.info("{} executes action: {}", npc.getNpcName(), response.action());
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> {
            AINPCEntity npc = session.getNpc();
            ServerPlayer player = session.getPlayer();

            if (!npc.isAlive()) return true;
            if (now - session.getLastActivity() > TIMEOUT_MS) {
                player.sendSystemMessage(Component.literal(
                    "§7[" + npc.getNpcName() + " wandered off]"));
                return true;
            }
            if (player.distanceToSqr(npc) > MAX_DISTANCE * MAX_DISTANCE) {
                player.sendSystemMessage(Component.literal(
                    "§7[" + npc.getNpcName() + " lost interest as you walked away]"));
                return true;
            }
            return false;
        });
    }

    private List<Map<String, String>> buildMessages(NPCSession session,
                                                     String message, boolean isGreeting) {
        AINPCEntity npc = session.getNpc();
        ServerPlayer player = session.getPlayer();
        NPCData data = npc.getNpcData();

        String context = isGreeting ? "You just met this player." : WorldContext.build(player);
        String personality = data != null ? String.join(", ", data.personality()) : "friendly";
        String role = data != null ? data.role() : "a villager";

        String systemPrompt = "You are " + npc.getNpcName()
            + ", a " + personality + " Minecraft villager.\n"
            + "Your role: " + role + "\n\n"
            + "Available actions: GO_HOME (walk to your home), FOLLOW_PLAYER (walk with the player), STAND_STILL (stay put)\n"
            + "Respond with JSON only: {\"action\": \"...\", \"dialogue\": \"...\"}\n\n"
            + "Current situation:\n" + context
            + session.getMemory().formatForPrompt()
            + session.formatHistory();

        return List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", message)
        );
    }
}
