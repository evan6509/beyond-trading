package com.beyondtrading.entity;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.npc.ConversationManager;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCMemory;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AINPCEntity extends PathfinderMob {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NPCData npcData;
    private BlockPos homePos;
    private BlockPos workplacePos;
    private final List<Relationship> relationships;
    private final List<String> currentGoals;
    private NPCMemory memory;
    private long lastMemoryFlush;
    private LivingEntity followTarget;
    private Player lastPlayerInteracted;
    private boolean dataLoaded;

    public AINPCEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.relationships = new ArrayList<>();
        this.currentGoals = new ArrayList<>();
        this.lastMemoryFlush = 0;
        this.dataLoaded = false;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitPos) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        ConversationManager.get().engage((ServerPlayer) player, this);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;

        if (followTarget != null && followTarget.isAlive()) {
            if (distanceToSqr(followTarget) > 3.0) {
                getNavigation().moveTo(followTarget, 0.6);
            }
        }

        long now = System.currentTimeMillis();
        if (memory != null && now - lastMemoryFlush > 30000) {
            memory.flush();
            lastMemoryFlush = now;
        }
    }

    // ---- Persistence (file-based JSON, avoids NBT API version issues) ----

    private Path getDataDir() {
        if (!(level() instanceof ServerLevel sl)) return null;
        return sl.getServer().getWorldPath(LevelResource.ROOT)
            .resolve("beyondtrading/npcdata");
    }

    private Path getDataFile() {
        Path dir = getDataDir();
        return dir != null ? dir.resolve(getUUID() + ".json") : null;
    }

    public void loadFromDisk() {
        if (dataLoaded) return;
        dataLoaded = true;

        Path file = getDataFile();
        if (file == null || !Files.exists(file)) return;

        try {
            String json = Files.readString(file);
            NPCSaveData saved = GSON.fromJson(json, NPCSaveData.class);
            if (saved != null) {
                this.npcData = saved.npcData;
                if (saved.homeX != null) this.homePos = new BlockPos(saved.homeX, saved.homeY, saved.homeZ);
                if (saved.workX != null) this.workplacePos = new BlockPos(saved.workX, saved.workY, saved.workZ);
                if (saved.currentGoals != null) this.currentGoals.addAll(saved.currentGoals);
                if (saved.relationships != null) this.relationships.addAll(saved.relationships);
            }
            BeyondTradingMod.LOGGER.debug("Loaded NPC data for {} ({})", getNpcName(), getUUID());
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to load NPC data for {}: {}", getUUID(), e.getMessage());
        }
    }

    public void saveToDisk() {
        Path file = getDataFile();
        if (file == null) return;

        try {
            Files.createDirectories(file.getParent());

            NPCSaveData saved = new NPCSaveData();
            saved.npcData = this.npcData;
            if (homePos != null) { saved.homeX = homePos.getX(); saved.homeY = homePos.getY(); saved.homeZ = homePos.getZ(); }
            if (workplacePos != null) { saved.workX = workplacePos.getX(); saved.workY = workplacePos.getY(); saved.workZ = workplacePos.getZ(); }
            saved.currentGoals = List.copyOf(this.currentGoals);
            saved.relationships = List.copyOf(this.relationships);

            Files.writeString(file, GSON.toJson(saved));
            BeyondTradingMod.LOGGER.debug("Saved NPC data for {} ({})", getNpcName(), getUUID());
        } catch (IOException e) {
            BeyondTradingMod.LOGGER.warn("Failed to save NPC data for {}: {}", getUUID(), e.getMessage());
        }

        if (memory != null) memory.flush();
    }

    private static class NPCSaveData {
        NPCData npcData;
        Integer homeX, homeY, homeZ;
        Integer workX, workY, workZ;
        List<String> currentGoals;
        List<Relationship> relationships;
    }

    // ---- Memory ----

    public NPCMemory getMemory() {
        if (memory == null && level() instanceof ServerLevel sl) {
            memory = new NPCMemory(getUUID(), sl.getServer().getWorldPath(LevelResource.ROOT));
            lastMemoryFlush = System.currentTimeMillis();
        }
        return memory;
    }

    public void remember(int day, String event, int importance) {
        NPCMemory mem = getMemory();
        if (mem != null) mem.remember(day, event, importance);
    }

    public void flushData() {
        if (memory != null) memory.flush();
    }

    // ---- NPC Data ----

    public void setNpcData(NPCData data) {
        this.npcData = data;
    }

    public NPCData getNpcData() {
        if (!dataLoaded) loadFromDisk();
        return npcData;
    }

    public String getNpcName() {
        NPCData d = getNpcData();
        return d != null ? d.name() : "Villager";
    }

    public Profession getProfession() {
        NPCData d = getNpcData();
        if (d == null) return Profession.NONE;
        return Profession.byName(d.profession());
    }

    // ---- Positions ----

    public void setHomePos(BlockPos pos) {
        this.homePos = pos;
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public void setWorkplacePos(BlockPos pos) {
        this.workplacePos = pos;
    }

    public BlockPos getWorkplacePos() {
        return workplacePos;
    }

    // ---- Goals ----

    public List<String> getCurrentGoals() {
        return currentGoals;
    }

    public void setCurrentGoals(List<String> goals) {
        currentGoals.clear();
        currentGoals.addAll(goals);
    }

    // ---- Relationships ----

    public List<Relationship> getRelationships() {
        return relationships;
    }

    public Relationship getRelationship(String targetId) {
        for (Relationship r : relationships) {
            if (r.targetId().equals(targetId)) return r;
        }
        return null;
    }

    public void addOrUpdateRelationship(Relationship rel) {
        for (int i = 0; i < relationships.size(); i++) {
            if (relationships.get(i).targetId().equals(rel.targetId())) {
                relationships.set(i, rel);
                return;
            }
        }
        relationships.add(rel);
    }

    // ---- Follow / Interact (used by behavior controller) ----

    public void setFollowTarget(LivingEntity target) {
        this.followTarget = target;
    }

    public void clearFollowTarget() {
        this.followTarget = null;
        getNavigation().stop();
    }

    public void setLastPlayerInteracted(Player player) {
        this.lastPlayerInteracted = player;
    }

    public Player getLastPlayerInteracted() {
        return lastPlayerInteracted;
    }
}
