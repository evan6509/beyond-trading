package com.beyondtrading.npc.conversation;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.ai.LLMResponse;
import com.beyondtrading.npc.ai.NPCManager;
import com.beyondtrading.npc.behavior.NPCBehaviorController;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationManager {
    private static ConversationManager instance;

    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final NPCBehaviorController behaviorController = new NPCBehaviorController();

    private ConversationManager() {}

    public static ConversationManager get() {
        if (instance == null) {
            instance = new ConversationManager();
        }
        return instance;
    }

    public void startConversation(Player player, AINPCEntity npc) {
        UUID playerId = player.getUUID();
        npc.setLastPlayerInteracted(player);
        conversations.put(playerId, new Conversation(npc.getUUID(), npc.getName().getString()));
        player.sendSystemMessage(
            Component.literal("<" + npc.getName().getString() + "> Yes? How can I help you?"));
    }

    public void endConversation(Player player) {
        conversations.remove(player.getUUID());
    }

    public boolean isInConversation(Player player) {
        return conversations.containsKey(player.getUUID());
    }

    public boolean handleChatMessage(ServerPlayer player, String message) {
        Conversation conv = conversations.get(player.getUUID());
        if (conv == null) return false;

        ServerLevel level = (ServerLevel) player.level();
        var server = level.getServer();

        CompletableFuture.runAsync(() -> {
            try {
                NPCManager npcManager = NPCManager.get(server);
                LLMResponse response = npcManager.processChat(conv.npcUUID, player, message);

                server.execute(() -> {
                    player.sendSystemMessage(
                        Component.literal("<" + conv.npcName + "> " + response.dialogue()));

                    if (player.level().getEntity(conv.npcUUID) instanceof AINPCEntity npc) {
                        behaviorController.execute(npc, response.action());
                    }

                    if (response.hasMemoryUpdate()) {
                        BeyondTradingMod.LOGGER.info("{} remembered: {}", conv.npcName, response.memoryUpdate());
                    }
                });
            } catch (Exception e) {
                BeyondTradingMod.LOGGER.error("Failed to process chat for {}", conv.npcName, e);
                server.execute(() ->
                    player.sendSystemMessage(
                        Component.literal("<" + conv.npcName + "> ...")));
            }
        });

        return true;
    }

    private record Conversation(UUID npcUUID, String npcName) {}
}
