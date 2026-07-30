package com.beyondtrading.npc;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.WorldContext;
import com.beyondtrading.npc.behavior.NPCBehaviorController;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationManager {
    private static ConversationManager instance;
    private final ConcurrentHashMap<SessionKey, NPCSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> activeNPCChat = new ConcurrentHashMap<>();
    private final LLMInterface llm = new LLMInterface();
    private final NPCBehaviorController behavior = new NPCBehaviorController();

    private static final long TIMEOUT_MS = 120_000;
    private static final double MAX_DISTANCE = 15.0;

    private ConversationManager() {}

    public static ConversationManager get() {
        if (instance == null) instance = new ConversationManager();
        return instance;
    }

    public record SessionKey(UUID playerId, UUID npcId) {}

    public void engage(ServerPlayer player, AINPCEntity npc) {
        UUID pid = player.getUUID();
        UUID nid = npc.getUUID();
        SessionKey key = new SessionKey(pid, nid);

        activeNPCChat.put(pid, nid);

        if (sessions.containsKey(key)) {
            greet(player, npc);
            return;
        }

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        NPCSession session = new NPCSession(npc, player);
        sessions.put(key, session);

        player.sendSystemMessage(Component.literal(
            "§e[You are now talking to " + npc.getNpcName() + " — type in chat]"));

        int day = (int) (server.overworld().getOverworldClockTime() / 24000);
        npc.remember(day, "Met " + player.getScoreboardName(), 80);
        npc.saveToDisk();

        greet(player, npc);
    }

    private void greet(ServerPlayer player, AINPCEntity npc) {
        UUID pid = player.getUUID();
        UUID nid = npc.getUUID();
        SessionKey key = new SessionKey(pid, nid);
        NPCSession session = sessions.get(key);
        if (session == null) return;

        ServerLevel level = (ServerLevel) player.level();
        var server = level.getServer();

        CompletableFuture.runAsync(() -> {
            llm.chatStructured(buildMessages(session, "Hello!", true)).ifPresent(
                response -> server.execute(() -> handleResponse(session, response)));
        });
    }

    public void disengage(ServerPlayer player) {
        UUID pid = player.getUUID();
        UUID nid = activeNPCChat.remove(pid);
        if (nid == null) return;
        SessionKey key = new SessionKey(pid, nid);
        NPCSession session = sessions.remove(key);
        if (session != null) {
            player.sendSystemMessage(Component.literal(
                "§7[" + session.getNpc().getNpcName() + " nods goodbye]"));
        }
    }

    public boolean isEngaged(ServerPlayer player) {
        return activeNPCChat.containsKey(player.getUUID());
    }

    public boolean handleChat(ServerPlayer player, String message) {
        UUID pid = player.getUUID();
        UUID nid = activeNPCChat.get(pid);
        if (nid == null) return false;

        SessionKey key = new SessionKey(pid, nid);
        NPCSession session = sessions.get(key);
        if (session == null) {
            activeNPCChat.remove(pid);
            return false;
        }

        AINPCEntity npc = session.getNpc();
        if (!npc.isAlive() || npc.distanceToSqr(player) > MAX_DISTANCE * MAX_DISTANCE) {
            sessions.remove(key);
            activeNPCChat.remove(pid);
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
        MinecraftServer server = ((ServerLevel) player.level()).getServer();

        player.sendSystemMessage(Component.literal(
            "§e[" + npc.getNpcName() + "] " + response.dialogue()));
        session.addTurn(null, response.dialogue());

        behavior.execute(npc, response.action());

        String action = response.action();
        if (response.hasAction()) {
            int day = (int) (server.overworld().getOverworldClockTime() / 24000);
            String summary = switch (action.toUpperCase()) {
                case "GO_HOME" -> "Went home";
                case "FOLLOW_PLAYER" -> "Started following " + player.getScoreboardName();
                default -> null;
            };
            if (summary != null) {
                npc.remember(day, summary, 60);
                BeyondTradingMod.LOGGER.info("{} {} (day {})", npc.getNpcName(), summary.toLowerCase(), day);
            }
        }
        npc.saveToDisk();
    }

    public void tick() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionKey key = entry.getKey();
            NPCSession session = entry.getValue();
            AINPCEntity npc = session.getNpc();
            ServerPlayer player = session.getPlayer();

            if (!npc.isAlive()) {
                activeNPCChat.remove(key.playerId(), key.npcId());
                return true;
            }
            if (now - session.getLastActivity() > TIMEOUT_MS) {
                player.sendSystemMessage(Component.literal(
                    "§7[" + npc.getNpcName() + " wandered off]"));
                activeNPCChat.remove(key.playerId(), key.npcId());
                return true;
            }
            if (player.distanceToSqr(npc) > MAX_DISTANCE * MAX_DISTANCE) {
                player.sendSystemMessage(Component.literal(
                    "§7[" + npc.getNpcName() + " lost interest as you walked away]"));
                activeNPCChat.remove(key.playerId(), key.npcId());
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
        Profession prof = npc.getProfession();

        String context = !isGreeting ? WorldContext.build(player) : "You just met this player.";
        String personality = data != null ? String.join(", ", data.traits()) : "friendly";
        String professionDesc = prof != Profession.NONE
            ? "a " + prof.displayName().toLowerCase()
            : "a villager";

        String systemPrompt = "You are " + npc.getNpcName()
            + ", a " + personality + " " + professionDesc + ".\n\n"
            + "Available actions: GO_HOME (walk to your home), FOLLOW_PLAYER (walk with the player), STAND_STILL (stay put)\n"
            + "Respond with JSON only: {\"action\": \"...\", \"dialogue\": \"...\"}\n\n"
            + "Current situation:\n" + context + "\n"
            + session.getMemory().formatForPrompt()
            + session.formatHistory();

        return List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", message)
        );
    }
}
