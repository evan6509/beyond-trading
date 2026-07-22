package com.beyondtrading;

import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.ModEntities;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeyondTradingMod implements ModInitializer {
    public static final String MOD_ID = "beyondtrading";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModConfig.get();
        ModEntities.register();
        LOGGER.info("Beyond Trading initialized");
    }
}
