package com.beyondtrading.entity;

import com.beyondtrading.BeyondTradingMod;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.resources.Identifier;

public class AINPCRenderer extends MobRenderer<AINPCEntity, VillagerRenderState, VillagerModel> {
    public AINPCRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
    }

    @Override
    public VillagerRenderState createRenderState() {
        return new VillagerRenderState();
    }

    @Override
    public void extractRenderState(AINPCEntity entity, VillagerRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
    }

    @Override
    public Identifier getTextureLocation(VillagerRenderState state) {
        return Identifier.fromNamespaceAndPath(BeyondTradingMod.MOD_ID, "textures/entity/ai_npc.png");
    }
}
