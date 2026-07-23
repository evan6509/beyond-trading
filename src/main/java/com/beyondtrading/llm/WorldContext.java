package com.beyondtrading.llm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

public class WorldContext {
    private static final String SYSTEM_PROMPT =
        "You are a helpful assistant for Minecraft 26.2. Give concise, " +
        "actionable advice (1-3 sentences). Prioritize survival if the " +
        "player is in danger. Reference the world context naturally.";

    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static String build(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();

        long timeOfDay = level.getOverworldClockTime() % 24000;
        long day = level.getOverworldClockTime() / 24000;

        String timeStr;
        if (timeOfDay < 6000) timeStr = "Dawn (" + timeOfDay + ")";
        else if (timeOfDay < 12000) timeStr = "Day (" + timeOfDay + ")";
        else if (timeOfDay < 13000) timeStr = "Dusk (" + timeOfDay + ")";
        else timeStr = "Night (" + timeOfDay + ")";

        String weather = level.isThundering() ? "Thunderstorm"
            : level.isRaining() ? "Rain" : "Clear";

        String biome = level.getBiome(player.blockPosition())
            .unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown");

        int hp = (int) player.getHealth();
        int maxHp = (int) player.getMaxHealth();
        int food = player.getFoodData().getFoodLevel();

        String heldItem = player.getMainHandItem().isEmpty()
            ? "empty"
            : player.getMainHandItem().getHoverName().getString();

        AABB area = player.getBoundingBox().inflate(20);
        int hostiles = level.getEntitiesOfClass(Monster.class, area,
            e -> e.isAlive()).size();
        int animals = level.getEntitiesOfClass(Animal.class, area,
            e -> e.isAlive()).size();

        String dim = level.dimension().identifier().getPath();

        return "Current situation:\n"
            + "- Time: " + timeStr + ", day " + day + "\n"
            + "- Biome: " + biome + "\n"
            + "- Weather: " + weather + "\n"
            + "- Player: health " + hp + "/" + maxHp + ", food " + food + "/20"
            + ", holding " + heldItem + "\n"
            + "- Nearby: " + hostiles + " hostile(s), " + animals + " animal(s)\n"
            + "- Dimension: " + dim;
    }
}
