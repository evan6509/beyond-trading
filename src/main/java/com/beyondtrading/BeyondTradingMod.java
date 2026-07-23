package com.beyondtrading;

import com.beyondtrading.command.NPCCommand;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.llm.ModelListFetcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeyondTradingMod implements ModInitializer {
    public static final String MOD_ID = "beyondtrading";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.get();
        ModelListFetcher.refresh();

        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
            NPCCommand.register(dispatcher));

        LOGGER.info("Beyond Trading initialized");
    }
}
