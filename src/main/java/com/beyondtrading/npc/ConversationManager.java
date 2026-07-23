package com.beyondtrading.npc;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.WorldContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationManager {
    private static ConversationManager instance;
    private final ConcurrentHashMap<UUID, NPCSession> sessions = new ConcurrentHashMap<>();
    private final LLMInterface llm = new LLMInterface();

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
        sessions.put(pid, new NPCSession(npc, player));
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
            var messages = buildMessages(session, "Hello!", true);
            llm.chat(messages).ifPresent(response ->
                server.execute(() -> reply(player, npc, response)));
        });
    }

    public void disengage(ServerPlayer player) {
        NPCSession session = sessions.remove(player.getUUID());
        if (session != null) {
            player.sendSystemMessage(Component.literal(
                "§7[" + session.getNpc().getNpcName() + " nods goodbye]"));
        }
    }

    public Optional<NPCSession> getSession(ServerPlayer player) {
        return Optional.ofNullable(sessions.get(player.getUUID()));
    }

    public boolean isEngaged(ServerPlayer player) {
        return sessions.containsKey(player.getUUID());
    }

    public boolean isEngagedWith(ServerPlayer player, AINPCEntity npc) {
        NPCSession s = sessions.get(player.getUUID());
        return s != null && s.getNpc().getUUID().equals(npc.getUUID());
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
            var messages = buildMessages(session, message, false);
            llm.chat(messages).ifPresentOrElse(
                response -> server.execute(() -> {
                    reply(player, npc, response);
                    session.addTurn(message, response.content());
                }),
                () -> server.execute(() ->
                    player.sendSystemMessage(Component.literal(
                        "§c[" + npc.getNpcName() + " seems confused...]"))));
        });

        return true;
    }

    private void reply(ServerPlayer player, AINPCEntity npc, LLMResponse response) {
        player.sendSystemMessage(Component.literal(
            "§e[" + npc.getNpcName() + "] " + response.content()));
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
        String context = isGreeting
            ? "You just met this player."
            : WorldContext.build(player);

        String systemPrompt = "You are " + npc.getNpcName()
            + ", a Minecraft villager. You live in the world, have your own daily life, "
            + "and speak conversationally in 1-3 sentences. "
            + "You are helpful but have your own personality and priorities.\n\n"
            + "Current situation:\n" + context
            + session.formatHistory();

        return List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", message)
        );
    }
}
