package com.beyondtrading.npc;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.PromptContext;
import com.beyondtrading.llm.WorldContext;
import com.beyondtrading.npc.behavior.NPCAction;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.schedule.VillageCoordinator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationManager {
    private static ConversationManager instance;
    private final ConcurrentHashMap<SessionKey, NPCSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> activeNPCChat = new ConcurrentHashMap<>();
    private final LLMInterface llm = new LLMInterface();

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

        // A player can only hold one conversation at a time. Close the old
        // session first so its villager is resumed instead of remaining
        // paused after the player walks over to someone else.
        UUID previousNpc = activeNPCChat.get(pid);
        if (previousNpc != null && !previousNpc.equals(nid)) {
            disengage(player);
        }

        boolean occupiedBySomeoneElse = activeNPCChat.entrySet().stream()
            .anyMatch(entry -> entry.getValue().equals(nid) && !entry.getKey().equals(pid));
        if (occupiedBySomeoneElse) {
            player.sendSystemMessage(Component.literal(
                "§7[" + npc.getNpcName() + " is already talking to someone else]"));
            return;
        }

        npc.setLastPlayerInteracted(player);
        npc.setInConversation(true);
        npc.getNPCBrain().interrupt();

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
        updatePlayerRelationship(npc, player, 5);
        npc.getNeeds().onSocialInteraction();
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

        if (!session.beginResponse()) return;
        CompletableFuture.runAsync(() -> {
            var response = llm.chatStructured(buildMessages(session, "Hello!", true));
            server.execute(() -> {
                session.endResponse();
                response.ifPresentOrElse(
                    value -> handleResponse(session, value),
                    () -> player.sendSystemMessage(Component.literal(
                        "§c[" + npc.getNpcName() + " seems distracted...]")));
            });
        }).exceptionally(error -> {
            server.execute(() -> {
                session.endResponse();
                player.sendSystemMessage(Component.literal(
                    "§c[" + npc.getNpcName() + " could not respond right now...]"));
            });
            return null;
        });
    }

    public void disengage(ServerPlayer player) {
        closeSession(player, true);
    }

    /** Release a session during network teardown without writing to a closing connection. */
    public void disconnect(ServerPlayer player) {
        closeSession(player, false);
    }

    private void closeSession(ServerPlayer player, boolean notify) {
        UUID pid = player.getUUID();
        UUID nid = activeNPCChat.remove(pid);
        if (nid == null) return;
        SessionKey key = new SessionKey(pid, nid);
        NPCSession session = sessions.remove(key);
        if (session != null) {
            session.getNpc().setInConversation(false);
            if (notify) {
                player.sendSystemMessage(Component.literal(
                    "§7[" + session.getNpc().getNpcName() + " nods goodbye]"));
            }
        }
    }

    public boolean isEngaged(ServerPlayer player) {
        return activeNPCChat.containsKey(player.getUUID());
    }

    /** Drop server-owned session references before a server instance is replaced. */
    public void clear() {
        sessions.values().forEach(session -> session.getNpc().setInConversation(false));
        sessions.clear();
        activeNPCChat.clear();
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
            // A chat packet can arrive between server ticks, after the player
            // has already left the interaction radius. Do not leave the
            // villager's autonomous brain paused in that race.
            npc.setInConversation(false);
            sessions.remove(key);
            activeNPCChat.remove(pid);
            return false;
        }

        session.touch();
        // Keep player-directed actions such as FOLLOW_PLAYER valid for the
        // duration of an active conversation, not only its opening greeting.
        npc.setLastPlayerInteracted(player);
        ServerLevel level = (ServerLevel) player.level();
        var server = level.getServer();

        String normalized = message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("bye") || normalized.equals("goodbye")
            || normalized.equals("exit") || normalized.equals("leave")) {
            disengage(player);
            return true;
        }

        if (!session.beginResponse()) {
            player.sendSystemMessage(Component.literal(
                "§7[" + npc.getNpcName() + " is still thinking...]"));
            return true;
        }

        player.sendSystemMessage(Component.literal("§7[You to " + npc.getNpcName() + "] " + message));

        CompletableFuture.runAsync(() -> {
            var response = llm.chatStructured(buildMessages(session, message, false));
            server.execute(() -> {
                session.endResponse();
                response.ifPresentOrElse(
                    value -> handleResponse(session, value, message),
                    () -> handleResponseFailure(session, message,
                        "§c[" + npc.getNpcName() + " seems confused...]") );
            });
        }).exceptionally(error -> {
            server.execute(() -> {
                session.endResponse();
                handleResponseFailure(session, message,
                    "§c[" + npc.getNpcName() + " could not respond right now...]");
            });
            return null;
        });

        return true;
    }

    private void handleResponse(NPCSession session, LLMResponse response) {
        handleResponse(session, response, null);
    }

    private void handleResponse(NPCSession session, LLMResponse response, String playerMessage) {
        ServerPlayer player = session.getPlayer();
        AINPCEntity npc = session.getNpc();
        if (sessions.get(new SessionKey(player.getUUID(), npc.getUUID())) != session
            || !npc.isAlive() || !player.isAlive()) {
            return;
        }
        MinecraftServer server = ((ServerLevel) player.level()).getServer();

        player.sendSystemMessage(Component.literal(
            "§e[" + npc.getNpcName() + "] " + response.dialogue()));
        session.addTurn(playerMessage, response.dialogue());

        if (playerMessage != null && !playerMessage.isBlank()) {
            int day = (int) (server.overworld().getOverworldClockTime() / 24000);
            npc.remember(day, "Conversation with " + player.getScoreboardName() + ": "
                + summarize(playerMessage) + " -> " + summarize(response.dialogue()), 45);
        }

        npc.getNPCBrain().applyExternalAction(response.action());
        updatePlayerRelationship(npc, player, 1);
        npc.getNeeds().onSocialInteraction();

        String action = response.action();
        if (response.hasAction()) {
            int day = (int) (server.overworld().getOverworldClockTime() / 24000);
            String summary = NPCAction.parse(action)
                .map(parsed -> "During conversation chose " + parsed.name())
                .orElse(null);
            if (summary != null) {
                npc.remember(day, summary, 60);
                BeyondTradingMod.LOGGER.info("{} {} (day {})", npc.getNpcName(), summary.toLowerCase(), day);
            }
        }
        npc.saveToDisk();
    }

    /** Preserve the interaction even when the local model is unavailable. */
    private void handleResponseFailure(NPCSession session, String playerMessage,
                                       String userFacingMessage) {
        ServerPlayer player = session.getPlayer();
        AINPCEntity npc = session.getNpc();
        if (sessions.get(new SessionKey(player.getUUID(), npc.getUUID())) != session
            || !npc.isAlive() || !player.isAlive()) {
            return;
        }

        player.sendSystemMessage(Component.literal(userFacingMessage));
        if (playerMessage == null || playerMessage.isBlank()) return;

        int day = (int) (((ServerLevel) npc.level()).getServer()
            .overworld().getOverworldClockTime() / 24000);
        npc.remember(day, "Conversation with " + player.getScoreboardName()
            + " (no answer): " + summarize(playerMessage), 25);
        session.addTurn(playerMessage, "[no response]");
        updatePlayerRelationship(npc, player, 1);
        npc.getNeeds().onSocialInteraction();
        npc.saveToDisk();
    }

    private String summarize(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "…" : compact;
    }

    private void updatePlayerRelationship(AINPCEntity npc, ServerPlayer player, int familiarityDelta) {
        String id = player.getUUID().toString();
        Relationship current = npc.getRelationship(id);
        if (current == null) {
            npc.addOrUpdateRelationship(Relationship.withPlayer(id, player.getScoreboardName())
                .withChange(familiarityDelta));
        } else {
            npc.addOrUpdateRelationship(current.withChange(familiarityDelta));
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionKey key = entry.getKey();
            NPCSession session = entry.getValue();
            AINPCEntity npc = session.getNpc();
            ServerPlayer player = session.getPlayer();

            if (!npc.isAlive() || !player.isAlive()) {
                npc.setInConversation(false);
                activeNPCChat.remove(key.playerId(), key.npcId());
                return true;
            }
            if (now - session.getLastActivity() > TIMEOUT_MS) {
                npc.setInConversation(false);
                player.sendSystemMessage(Component.literal(
                    "§7[" + npc.getNpcName() + " wandered off]"));
                activeNPCChat.remove(key.playerId(), key.npcId());
                return true;
            }
            if (player.distanceToSqr(npc) > MAX_DISTANCE * MAX_DISTANCE) {
                npc.setInConversation(false);
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

        String context = !isGreeting
            ? WorldContext.build(player) + "\n\nYour local villager view:\n" + WorldContext.build(npc)
            : "You just met this player.";
        String personality = data != null && data.traits() != null
            ? String.join(", ", data.traits()) : "friendly";
        String professionDesc = prof != Profession.NONE
            ? "a " + prof.displayName().toLowerCase()
            : "a villager";

        Relationship playerRelationship = npc.getRelationship(player.getUUID().toString());
        String relationship = playerRelationship == null ? "new acquaintance"
            : playerRelationship.type() + ", familiarity " + playerRelationship.familiarity()
                + "/100";

        String instructions = "You are " + npc.getNpcName()
            + ", a " + personality + " " + professionDesc + ".\n\n"
            + "Available actions: GO_HOME, GO_TO_WORK, TRADE, SLEEP, EAT, WANDER, SOCIALIZE, COORDINATE, "
            + "HELP_PLAYER, FOLLOW_PLAYER, RESTOCK_MATERIALS, STAND_STILL. "
            + "Use STAND_STILL for ordinary conversation.\n"
            + "Respond with JSON only: {\"action\": \"...\", \"dialogue\": \"...\"}\n\n"
            + "Be natural and concise, but keep your identity and remembered relationship consistent.";

        String contextText = PromptContext.compose(PromptContext.CONVERSATION_TOKEN_BUDGET, List.of(
            new PromptContext.Section("Identity", "name " + npc.getNpcName()
                + ", traits " + personality + ", profession " + professionDesc, 100),
            new PromptContext.Section("Current situation", context, 94),
            new PromptContext.Section("Needs", npc.getNeeds().formatForPrompt(), 90),
            new PromptContext.Section("Relationship with this player", relationship, 88),
            new PromptContext.Section("Village", VillageCoordinator.snapshot(npc).formatForPrompt(), 84),
            new PromptContext.Section("Long-term goal", npc.getLongTermGoal(), 80),
            new PromptContext.Section("Conversation history", session.formatHistory(), 75),
            new PromptContext.Section("Memories", session.getMemory().formatForPrompt(
                (int) (npc.level().getOverworldClockTime() / 24000)), 60)
        ));

        return List.of(
            Map.of("role", "system", "content", instructions + "\n\n" + contextText),
            Map.of("role", "user", "content", message)
        );
    }
}
