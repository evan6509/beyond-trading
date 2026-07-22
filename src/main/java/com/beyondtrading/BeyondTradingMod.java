package com.beyondtrading;

import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.ModEntities;
import com.beyondtrading.npc.conversation.ConversationManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeyondTradingMod implements ModInitializer {
    public static final String MOD_ID = "beyondtrading";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.get();
        ModEntities.register();

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChat);
        ServerMessageEvents.CHAT_MESSAGE.register(this::onChatMessage);

        LOGGER.info("Beyond Trading initialized");
    }

    private boolean onAllowChat(PlayerChatMessage message, ServerPlayer player, ChatType.Bound bound) {
        return !ConversationManager.get().isInConversation(player);
    }

    private void onChatMessage(PlayerChatMessage message, ServerPlayer player, ChatType.Bound bound) {
        ConversationManager.get().handleChatMessage(player, message.signedContent());
    }
}
