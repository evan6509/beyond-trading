package com.beyondtrading.entity;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.Profession;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Converts naturally spawned vanilla villagers into persistent LLM agents. */
public final class VillagerConversionManager {
    private static int tickCounter;

    private VillagerConversionManager() {}

    public static void tick(MinecraftServer server) {
        if ((tickCounter++ % 20) != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            List<Villager> pending = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Villager villager && !(villager instanceof AINPCEntity)
                    && villager.isAlive() && !villager.isTrading()) {
                    pending.add(villager);
                }
            }
            for (Villager villager : pending) convert(level, villager);
        }
    }

    private static void convert(ServerLevel level, Villager original) {
        String name = original.getCustomName() != null
            ? original.getCustomName().getString()
            : generatedName(original.getUUID());
        Profession profession = mapProfession(original);
        NPCData data = new NPCData(
            name,
            profession.name(),
            traitsFor(profession, original.getUUID()),
            null,
            null,
            profession.availableGoals());

        AINPCEntity agent = new AINPCEntity(ModEntities.AI_NPC, level);
        agent.setUUID(original.getUUID());
        agent.setPos(original.getX(), original.getY(), original.getZ());
        agent.setYRot(original.getYRot());
        agent.setXRot(original.getXRot());
        agent.setHealth(original.getHealth());
        agent.setAge(original.getAge());
        agent.setVillagerData(original.getVillagerData());
        agent.setVillagerDataFinalized(original.getVillagerDataFinalized());
        agent.setVillagerXp(original.getVillagerXp());
        agent.setGossips(original.getGossips());
        agent.setOffers(original.getOffers());
        agent.setCustomName(Component.literal(name));
        agent.setCustomNameVisible(original.isCustomNameVisible());

        for (int slot = 0; slot < original.getInventory().getContainerSize(); slot++) {
            agent.getInventory().setItem(slot, original.getInventory().getItem(slot).copy());
        }

        agent.setHomePos(memoryPosition(original, MemoryModuleType.HOME, level));
        agent.setWorkplacePos(memoryPosition(original, MemoryModuleType.JOB_SITE, level));
        // Reuse an existing UUID record when a previously adopted villager is
        // restored by the game. The vanilla entity is only the initial seed;
        // it must not erase accumulated memories, needs, or relationships.
        agent.loadFromDisk();
        if (!agent.hasNpcData()) agent.setNpcData(data);

        original.remove(Entity.RemovalReason.DISCARDED);
        level.addFreshEntity(agent);
        // Register immediately; relying on a later interaction or inspect
        // command would leave an adopted villager present but autonomous only
        // after somebody touched it.
        agent.getNPCBrain();
        agent.saveToDisk();
        BeyondTradingMod.LOGGER.info("Adopted vanilla villager {} ({}) as an LLM agent",
            name, agent.getUUID());
    }

    private static net.minecraft.core.BlockPos memoryPosition(
        Villager villager, MemoryModuleType<GlobalPos> memory, ServerLevel level) {
        return villager.getBrain().getMemory(memory)
            .filter(global -> global.dimension().equals(level.dimension()))
            .map(GlobalPos::pos)
            .orElse(villager.blockPosition());
    }

    private static Profession mapProfession(Villager villager) {
        String key = villager.getVillagerData().profession().unwrapKey()
            .map(resource -> resource.identifier().getPath())
            .orElse("none").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "farmer" -> Profession.FARMER;
            case "librarian" -> Profession.LIBRARIAN;
            case "cartographer" -> Profession.EXPLORER;
            case "toolsmith", "weaponsmith", "armorer" -> Profession.BLACKSMITH;
            case "none", "nitwit" -> Profession.NONE;
            default -> Profession.MERCHANT;
        };
    }

    private static List<String> traitsFor(Profession profession, UUID uuid) {
        List<String> traits = new ArrayList<>(switch (profession) {
            case FARMER -> List.of("patient", "hardworking");
            case LIBRARIAN -> List.of("curious", "thoughtful");
            case EXPLORER -> List.of("observant", "restless");
            case BLACKSMITH -> List.of("practical", "careful");
            case MERCHANT -> List.of("sociable", "opportunistic");
            default -> List.of("friendly");
        });
        String[] personalities = {
            "cautious", "generous", "ambitious", "stubborn", "gentle", "witty", "loyal"
        };
        traits.add(personalities[Math.floorMod(uuid.hashCode(), personalities.length)]);
        return List.copyOf(traits);
    }

    private static String generatedName(UUID uuid) {
        String[] firstNames = {
            "Ada", "Bram", "Cora", "Dorian", "Elin", "Fenn", "Greta", "Hale",
            "Iris", "Jonas", "Kira", "Luca", "Mara", "Niko", "Orla", "Perrin"
        };
        return firstNames[Math.floorMod(uuid.hashCode(), firstNames.length)]
            + " " + uuid.toString().substring(0, 4);
    }
}
