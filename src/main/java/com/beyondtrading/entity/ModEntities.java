package com.beyondtrading.entity;

import com.beyondtrading.BeyondTradingMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

public class ModEntities {
    public static final EntityType<AINPCEntity> AI_NPC = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(BeyondTradingMod.MOD_ID, "ai_npc"),
        EntityType.Builder.of(AINPCEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(BeyondTradingMod.MOD_ID, "ai_npc")))
    );

    public static void register() {
        FabricDefaultAttributeRegistry.register(AI_NPC, AINPCEntity.createAttributes());
        BeyondTradingMod.LOGGER.info("Registered AI NPC entity");
    }
}
