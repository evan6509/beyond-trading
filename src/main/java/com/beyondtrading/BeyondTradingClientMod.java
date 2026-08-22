package com.beyondtrading;

import net.fabricmc.api.ClientModInitializer;

public class BeyondTradingClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // No client entrypoint is required. AI agents use the vanilla
        // villager entity identity and renderer, so an unmodded client works.
    }
}
