package com.beyondtrading.npc.schedule;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.data.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes the live shared situation for a group of nearby agents.
 *
 * The coordinator does not invent a second world or teleport villagers. It
 * derives village pressure from Minecraft entities and inventories, then
 * exposes a small, deterministic coordination primitive for the LLM to use.
 */
public final class VillageCoordinator {
    private static final double VILLAGE_RADIUS = 48.0;

    private VillageCoordinator() {}

    public static VillageSnapshot snapshot(AINPCEntity source) {
        if (!(source.level() instanceof ServerLevel level)) {
            return VillageSnapshot.empty();
        }

        List<AINPCEntity> residents = level.getEntitiesOfClass(AINPCEntity.class,
            source.getBoundingBox().inflate(VILLAGE_RADIUS),
            npc -> npc.isAlive());
        int residentFood = residents.stream()
            .mapToInt(VillageCoordinator::foodCount)
            .sum();
        Map<String, Integer> materials = new LinkedHashMap<>();
        residents.forEach(npc -> addInventoryMaterials(materials, npc));
        Map<String, Integer> storedMaterials = nearbyStoredMaterials(source, level);
        storedMaterials.forEach((key, count) -> materials.merge(key, count, Integer::sum));
        int storedFood = storedMaterials.entrySet().stream()
            .filter(entry -> isFoodKey(entry.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
        int food = residentFood + storedFood;
        int hungry = (int) residents.stream()
            .filter(npc -> npc.getNeeds().hunger() < 35)
            .count();
        int tired = (int) residents.stream()
            .filter(npc -> npc.getNeeds().energy() < 30)
            .count();
        Map<String, Long> professions = residents.stream()
            .collect(Collectors.groupingBy(
                npc -> npc.getProfession().displayName().toLowerCase(Locale.ROOT),
                Collectors.counting()));
        int players = level.getEntitiesOfClass(ServerPlayer.class,
            source.getBoundingBox().inflate(VILLAGE_RADIUS),
            player -> player.isAlive()).size();
        int hostiles = level.getEntitiesOfClass(Monster.class,
            source.getBoundingBox().inflate(16),
            monster -> monster.isAlive()).size();

        boolean foodShortage = food < Math.max(2, hungry * 2);
        boolean materialShortage = hasMaterialShortage(professions, materials);
        String pressure = foodShortage ? "FOOD_SHORTAGE" : "stable";
        if (materialShortage) pressure += ", MATERIAL_SHORTAGE";
        if (hostiles > 0) pressure += ", LOCAL_DANGER";

        return new VillageSnapshot(
            residents.size(), food, hungry, tired, players, hostiles, pressure,
            professions, materials);
    }

    /**
     * Shares one food item when possible and always exchanges local knowledge.
     * Both sides receive a memory and relationship change, making coordination
     * observable after the action has finished.
     */
    public static CoordinationResult coordinate(AINPCEntity source, AINPCEntity target) {
        if (target == null || target == source || !target.isAlive()
            || source.level() != target.level()
            || source.distanceToSqr(target) > 9.0) {
            return new CoordinationResult(false, "could not meet the neighbor");
        }

        ItemStack shared = takeHelpfulSupply(source, target);
        String sharedName = shared.isEmpty() ? null : shared.getHoverName().getString();
        boolean supplyShared = false;
        if (!shared.isEmpty() && target.getInventory().canAddItem(shared)) {
            ItemStack leftover = target.getInventory().addItem(shared);
            if (leftover.isEmpty()) {
                supplyShared = true;
            } else {
                source.getInventory().addItem(leftover);
            }
        } else if (!shared.isEmpty()) {
            source.getInventory().addItem(shared);
        }

        int day = (int) (source.level().getOverworldClockTime() / 24000);
        String event = supplyShared
            ? "Shared " + sharedName + " with " + target.getNpcName()
            : "Coordinated with " + target.getNpcName() + " and exchanged village news";
        source.remember(day, event, 35);
        target.remember(day, supplyShared
            ? "Received " + sharedName + " from " + source.getNpcName()
            : source.getNpcName() + " exchanged village news", 30);
        target.setLastActionOutcome(supplyShared
            ? "received " + sharedName + " from " + source.getNpcName()
            : "exchanged village news with " + source.getNpcName());

        Relationship sourceRelation = source.getRelationship(target.getUUID().toString());
        source.addOrUpdateRelationship(sourceRelation == null
            ? Relationship.withNPC(target.getUUID().toString(), target.getNpcName()).withChange(2)
            : sourceRelation.withChange(1));
        Relationship targetRelation = target.getRelationship(source.getUUID().toString());
        target.addOrUpdateRelationship(targetRelation == null
            ? Relationship.withNPC(source.getUUID().toString(), source.getNpcName()).withChange(1)
            : targetRelation.withChange(1));
        source.getNeeds().onSocialInteraction();
        target.getNeeds().onSocialInteraction();
        source.advanceLongTermGoal(1);
        target.advanceLongTermGoal(1);
        NPCScheduler.get().broadcast(source, event);
        // Coordination is a discrete relationship event. Persist both sides
        // immediately so a server stop cannot erase the exchange before the
        // normal periodic state flush.
        source.saveToDisk();
        target.saveToDisk();
        return new CoordinationResult(true, event);
    }

    private static ItemStack takeHelpfulSupply(AINPCEntity source, AINPCEntity target) {
        for (ItemStack stack : source.getInventory().getItems()) {
            if (!stack.isEmpty() && isHelpfulFor(target, stack)) {
                return source.getInventory().removeItemType(stack.getItem(), 1);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Lower values make a resident a higher-priority coordination target. */
    public static int supportPriority(AINPCEntity npc) {
        if (npc.getNeeds().hunger() < 35) return 0;
        if (snapshot(npc).pressure().contains("MATERIAL_SHORTAGE")) return 1;
        if (npc.getNeeds().energy() < 30) return 2;
        return 3;
    }

    private static boolean isHelpfulFor(AINPCEntity target, ItemStack stack) {
        if (target.getNeeds().hunger() < 35 && isFood(stack)) return true;
        Profession profession = target.getProfession();
        return switch (profession) {
            case FARMER -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT)
                || stack.is(Items.POTATO) || stack.is(Items.BEETROOT);
            case BLACKSMITH -> stack.is(Items.IRON_INGOT) || stack.is(Items.COAL);
            case LIBRARIAN -> stack.is(Items.PAPER) || stack.is(Items.LEATHER);
            case MERCHANT -> stack.is(Items.BREAD) || stack.is(Items.WHEAT)
                || stack.is(Items.EMERALD);
            case EXPLORER -> stack.is(Items.COMPASS) || stack.is(Items.MAP)
                || stack.is(Items.BREAD);
            default -> false;
        };
    }

    private static int foodCount(AINPCEntity npc) {
        return npc.getInventory().getItems().stream()
            .filter(stack -> {
                String key = materialKey(stack);
                return key != null && isFoodKey(key);
            })
            .mapToInt(ItemStack::getCount)
            .sum();
    }

    private static Map<String, Integer> nearbyStoredMaterials(AINPCEntity source, ServerLevel level) {
        BlockPos center = source.getWorkplacePos() != null
            ? source.getWorkplacePos() : source.blockPosition();
        Map<String, Integer> materials = new LinkedHashMap<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (!(level.getBlockEntity(center.offset(dx, dy, dz)) instanceof Container container)) {
                        continue;
                    }
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack stack = container.getItem(slot);
                        addMaterial(materials, stack);
                    }
                }
            }
        }
        return materials;
    }

    private static void addInventoryMaterials(Map<String, Integer> materials, AINPCEntity npc) {
        for (ItemStack stack : npc.getInventory().getItems()) addMaterial(materials, stack);
    }

    private static void addMaterial(Map<String, Integer> materials, ItemStack stack) {
        String key = materialKey(stack);
        if (key != null) materials.merge(key, stack.getCount(), Integer::sum);
    }

    private static String materialKey(ItemStack stack) {
        if (stack.is(Items.BREAD)) return "bread";
        if (stack.is(Items.CARROT)) return "carrot";
        if (stack.is(Items.POTATO)) return "potato";
        if (stack.is(Items.BEETROOT)) return "beetroot";
        if (stack.is(Items.WHEAT)) return "wheat";
        if (stack.is(Items.WHEAT_SEEDS)) return "wheat_seeds";
        if (stack.is(Items.IRON_INGOT)) return "iron_ingot";
        if (stack.is(Items.COAL)) return "coal";
        if (stack.is(Items.PAPER)) return "paper";
        if (stack.is(Items.LEATHER)) return "leather";
        if (stack.is(Items.EMERALD)) return "emerald";
        if (stack.is(Items.COMPASS)) return "compass";
        if (stack.is(Items.MAP)) return "map";
        if (stack.is(Items.BOOK)) return "book";
        return null;
    }

    private static boolean isFood(ItemStack stack) {
        String key = materialKey(stack);
        return key != null && isFoodKey(key);
    }

    private static boolean isFoodKey(String key) {
        return key.equals("bread") || key.equals("carrot") || key.equals("potato")
            || key.equals("beetroot") || key.equals("wheat");
    }

    private static boolean hasMaterialShortage(Map<String, Long> professions,
                                                Map<String, Integer> materials) {
        return (professionCount(professions, "farmer") > 0
                && materials.getOrDefault("wheat_seeds", 0) < 1)
            || (professionCount(professions, "blacksmith") > 0
                && materials.getOrDefault("iron_ingot", 0) < 1)
            || (professionCount(professions, "librarian") > 0
                && (materials.getOrDefault("paper", 0) < 3
                    || materials.getOrDefault("leather", 0) < 1))
            || (professionCount(professions, "merchant") > 0
                && materials.getOrDefault("bread", 0) < 1);
    }

    private static long professionCount(Map<String, Long> professions, String profession) {
        return professions.getOrDefault(profession, 0L);
    }

    public record CoordinationResult(boolean success, String outcome) {}

    public record VillageSnapshot(
        int residents,
        int foodItems,
        int hungryResidents,
        int tiredResidents,
        int nearbyPlayers,
        int nearbyHostiles,
        String pressure,
        Map<String, Long> professions,
        Map<String, Integer> materials
    ) {
        public static VillageSnapshot empty() {
            return new VillageSnapshot(0, 0, 0, 0, 0, 0, "unknown", Map.of(), Map.of());
        }

        public String formatForPrompt() {
            String workforce = professions.isEmpty() ? "none" : professions.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
            String stock = materials.isEmpty() ? "none" : materials.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
            return "Village situation: residents " + residents
                + ", food stock (agents and nearby storage) " + foodItems
                + ", hungry residents " + hungryResidents
                + ", tired residents " + tiredResidents
                + ", nearby players " + nearbyPlayers
                + ", nearby hostiles " + nearbyHostiles
                + ", pressure " + pressure
                + ", workforce [" + workforce + "]"
                + ", tracked materials [" + stock + "]";
        }
    }
}
