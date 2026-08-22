package com.beyondtrading.llm;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.schedule.VillageCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;

import java.util.stream.Collectors;

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

    /** Context used by an autonomous NPC decision, rather than a player chat. */
    public static String build(AINPCEntity npc) {
        ServerLevel level = (ServerLevel) npc.level();
        long timeOfDay = level.getOverworldClockTime() % 24000;
        long day = level.getOverworldClockTime() / 24000;
        String timeStr = timeOfDay < 6000 ? "Dawn" : timeOfDay < 12000 ? "Day"
            : timeOfDay < 13000 ? "Dusk" : "Night";
        String weather = level.isThundering() ? "Thunderstorm"
            : level.isRaining() ? "Rain" : "Clear";
        String biome = level.getBiome(npc.blockPosition()).unwrapKey()
            .map(key -> key.identifier().getPath()).orElse("unknown");

        AABB area = npc.getBoundingBox().inflate(20);
        int players = level.getEntitiesOfClass(ServerPlayer.class, area,
            player -> player.isAlive()).size();
        int hostiles = level.getEntitiesOfClass(Monster.class, area,
            entity -> entity.isAlive()).size();
        int animals = level.getEntitiesOfClass(Animal.class, area,
            entity -> entity.isAlive()).size();
        var nearbyVillagers = level.getEntitiesOfClass(AINPCEntity.class, area,
                entity -> entity != npc && entity.isAlive()).stream()
            .limit(5)
            .map(entity -> entity.getNpcName() + " (" + entity.getProfession().displayName() + ")")
            .collect(Collectors.joining(", "));
        int otherNPCs = nearbyVillagers.isBlank() ? 0 : nearbyVillagers.split(", ").length;
        int matureCrops = 0;
        int emptyFarmland = 0;
        int bedBlocks = 0;
        BlockPos center = npc.getWorkplacePos() != null
            ? npc.getWorkplacePos() : npc.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    var state = level.getBlockState(center.offset(dx, dy, dz));
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        matureCrops++;
                    }
                    if (state.is(Blocks.FARMLAND)
                        && level.getBlockState(center.offset(dx, dy, dz).above()).isAir()) {
                        emptyFarmland++;
                    }
                    if (state.is(BlockTags.BEDS)) bedBlocks++;
                }
            }
        }
        int droppedItems = level.getEntitiesOfClass(ItemEntity.class,
            npc.getBoundingBox().inflate(10), entity -> entity.isAlive()).size();
        ServerPlayer recentPlayer = npc.getRecentPlayerInteracted() instanceof ServerPlayer player
            ? player : null;
        String recentInteraction = recentPlayer == null
            ? "none"
            : recentPlayer.getScoreboardName() + " spoke with this villager recently";
        String playerDetails = level.getEntitiesOfClass(ServerPlayer.class, area,
                player -> player.isAlive()).stream()
            .limit(4)
            .map(player -> player.getScoreboardName()
                + " (distance " + (int) Math.ceil(Math.sqrt(npc.distanceToSqr(player)))
                + " blocks, health " + (int) player.getHealth() + "/" + (int) player.getMaxHealth()
                + ", food " + player.getFoodData().getFoodLevel() + "/20"
                + (player == recentPlayer ? ", recently interacted" : ""))
            .collect(Collectors.joining("; "));
        if (playerDetails.isBlank()) playerDetails = "none";

        String home = npc.getHomePos() == null ? "unknown" : npc.getHomePos().toShortString();
        String workplace = npc.getWorkplacePos() == null
            ? "unknown" : npc.getWorkplacePos().toShortString();
        String inventory = npc.getInventory().getItems().stream()
            .filter(stack -> !stack.isEmpty())
            .map(stack -> stack.getHoverName().getString() + " x" + stack.getCount())
            .limit(8)
            .collect(Collectors.joining(", "));
        if (inventory.isBlank()) inventory = "empty";

        return "Current world situation:\n"
            + "- Time: " + timeStr + " (" + timeOfDay + "), day " + day + "\n"
            + "- Biome: " + biome + "\n"
            + "- Weather: " + weather + "\n"
            + "- Position: " + npc.blockPosition().toShortString() + "\n"
            + "- Home position: " + home + "\n"
            + "- Workplace position: " + workplace + "\n"
            + "- Inventory: " + inventory + "\n"
            + "- Local affordances: " + matureCrops + " mature crop(s), "
            + emptyFarmland + " empty farmland plot(s), " + bedBlocks
            + " bed block(s), " + droppedItems + " dropped item pile(s) nearby\n"
            + "- Recent player interaction: " + recentInteraction + "\n"
            + "- Nearby player details: " + playerDetails + "\n"
            + "- " + VillageCoordinator.snapshot(npc).formatForPrompt() + "\n"
            + "- Nearby: " + players + " player(s), " + otherNPCs + " other villager(s)"
            + (nearbyVillagers.isBlank() ? "" : " [" + nearbyVillagers + "]") + ", "
            + hostiles + " hostile(s), " + animals + " animal(s)\n"
            + "- Dimension: " + level.dimension().identifier().getPath();
    }
}
