package com.beyondtrading.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;

public class ModEntities {
    /**
     * Agents deliberately use the vanilla network identity. A vanilla client
     * can therefore render and interact with them without installing this
     * mod; the server-side subclass still owns the LLM brain and state.
     */
    @SuppressWarnings("unchecked")
    public static final EntityType<Villager> AI_NPC = (EntityType<Villager>) (EntityType<?>)
        BuiltInRegistries.ENTITY_TYPE.getValue(
            Identifier.fromNamespaceAndPath("minecraft", "villager"));

    public static void register() {
        com.beyondtrading.BeyondTradingMod.LOGGER.info(
            "Using vanilla villager network identity for server-side AI NPCs");
    }
}
