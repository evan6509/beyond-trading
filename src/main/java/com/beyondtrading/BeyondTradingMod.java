package com.beyondtrading;

import com.beyondtrading.command.NPCCommand;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.entity.ModEntities;
import com.beyondtrading.llm.ModelListFetcher;
import com.beyondtrading.npc.ConversationManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeyondTradingMod implements ModInitializer {
    public static final String MOD_ID = "beyondtrading";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.get();
        ModEntities.register();
        ModelListFetcher.refresh();

        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
            NPCCommand.register(dispatcher));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChat);

        ServerTickEvents.START_SERVER_TICK.register(server ->
            ConversationManager.get().tick());

        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        LOGGER.info("Beyond Trading initialized");
    }

    private boolean onAllowChat(PlayerChatMessage message, ServerPlayer player, ChatType.Bound bound) {
        if (ConversationManager.get().isEngaged(player)) {
            ConversationManager.get().handleChat(player, message.signedContent());
            return false;
        }
        return true;
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("Saving all NPC data...");
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AINPCEntity npc) {
                    npc.saveToDisk();
                    count++;
                }
            }
        }
        LOGGER.info("Saved {} NPC(s)", count);
    }
}
