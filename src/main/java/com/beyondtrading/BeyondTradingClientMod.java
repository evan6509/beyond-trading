package com.beyondtrading;

import com.beyondtrading.entity.AINPCRenderer;
import com.beyondtrading.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class BeyondTradingClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(ModEntities.AI_NPC, AINPCRenderer::new);
    }
}
