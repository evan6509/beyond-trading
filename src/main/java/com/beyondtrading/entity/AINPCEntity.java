package com.beyondtrading.entity;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.npc.ConversationManager;
import com.beyondtrading.npc.brain.NPCBrain;
import com.beyondtrading.npc.behavior.NPCAction;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCMemory;
import com.beyondtrading.npc.data.NPCNeeds;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.data.GoalProgressPolicy;
import com.beyondtrading.npc.goal.NPCGoal;
import com.beyondtrading.npc.schedule.NPCScheduler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A vanilla Villager with an LLM agent layered over its normal game identity. */
public class AINPCEntity extends Villager {
    public static final String AGENT_TAG = "beyondtrading_agent";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NPCData npcData;
    private BlockPos homePos;
    private BlockPos workplacePos;
    private final List<Relationship> relationships;
    private final List<String> currentGoals;
    private final NPCNeeds needs;
    private String longTermGoal;
    private int longTermGoalProgress;
    private NPCMemory memory;
    private NPCBrain brain;
    private boolean inConversation;
    private long lastMemoryFlush;
    private LivingEntity followTarget;
    private Player lastPlayerInteracted;
    private long lastPlayerInteractionGameTime;
    private String lastAutonomousDecision;
    private String lastActionOutcome;
    private long lastDecisionGameTime;
    private boolean decisionObservedThisSession;
    private boolean dataLoaded;

    public AINPCEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
        addTag(AGENT_TAG);
        this.relationships = new ArrayList<>();
        this.currentGoals = new ArrayList<>();
        this.needs = new NPCNeeds();
        this.longTermGoal = "none";
        this.longTermGoalProgress = 0;
        this.inConversation = false;
        this.lastMemoryFlush = 0;
        this.lastAutonomousDecision = "none";
        this.lastActionOutcome = "none";
        this.lastDecisionGameTime = -1;
        this.decisionObservedThisSession = false;
        this.lastPlayerInteractionGameTime = -1;
        this.dataLoaded = false;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Vanilla Villager has its own Brain and schedule. The agent brain is
        // the authority for autonomous decisions; retaining both schedulers
        // would make navigation and goals fight each other every tick.
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitPos) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        // Sneak-right-click keeps the normal Villager trading screen
        // available; ordinary right-click is the LLM conversation affordance.
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer
                && ConversationManager.get().isEngaged(serverPlayer)) {
                ConversationManager.get().disengage(serverPlayer);
            }
            setLastPlayerInteracted(player);
            return super.interact(player, hand, hitPos);
        }
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        ConversationManager.get().engage((ServerPlayer) player, this);
        return InteractionResult.SUCCESS;
    }

    /** Feed ordinary vanilla-client trades back into the agent's social state. */
    @Override
    public void notifyTrade(MerchantOffer offer) {
        super.notifyTrade(offer);
        // Native trading can remain open longer than the ordinary player
        // influence window. While the vanilla merchant screen is active,
        // the stored player is still the authoritative trade participant;
        // do not lose the memory/relationship update merely because the
        // conversation-style expiry elapsed.
        if (!(level() instanceof ServerLevel)
            || !(lastPlayerInteracted instanceof ServerPlayer player)
            || !player.isAlive()
            || player.level() != level()
            || (!hasRecentPlayerInteraction() && !isTrading())) {
            return;
        }

        int day = (int) (level().getLevelData().getGameTime() / 24000);
        remember(day, "Completed a vanilla trade with " + player.getScoreboardName(), 35);
        Relationship current = getRelationship(player.getUUID().toString());
        if (current == null) {
            addOrUpdateRelationship(Relationship.withPlayer(player.getUUID().toString(), player.getScoreboardName())
                .withChange(5));
        } else {
            addOrUpdateRelationship(current.withChange(5));
        }
        needs.onSocialInteraction();
        advanceLongTermGoal(5);
        saveToDisk();
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        NPCScheduler.get().unregister(this);
        saveToDisk();
        super.remove(reason);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;

        // Initialize autonomy as soon as a server-side NPC is loaded. Without
        // this, the brain only existed after a player interacted with it.
        getNPCBrain();
        needs.tick(this);

        if (followTarget != null && !isFollowTargetValid()) {
            clearFollowTarget();
        } else if (followTarget != null && followTarget.isAlive()) {
            if (distanceToSqr(followTarget) > 3.0) {
                getNavigation().moveTo(followTarget, 0.6);
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastMemoryFlush > 30000) {
            // Needs and long-term goals are JSON state as well as memory, so
            // flush the whole agent state on the same cadence.
            saveToDisk();
            lastMemoryFlush = now;
        }
    }

    // ---- Brain / Autonomy ----

    public NPCBrain getNPCBrain() {
        if (brain == null && level() instanceof ServerLevel) {
            brain = new NPCBrain(this);
            NPCScheduler.get().register(this);
        }
        return brain;
    }

    public void setInConversation(boolean inConversation) {
        this.inConversation = inConversation;
        var b = getNPCBrain();
        if (b != null) {
            if (inConversation) b.pause();
            else b.resume();
        }
    }

    public boolean isInConversation() {
        return inConversation;
    }

    public NPCGoal getCurrentGoal() {
        var b = getNPCBrain();
        return b != null ? b.getCurrentGoal() : null;
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
                if (saved.currentGoals != null) addNormalizedGoals(saved.currentGoals);
                if (saved.relationships != null) this.relationships.addAll(saved.relationships);
                if (saved.longTermGoal != null) {
                    String normalizedGoal = normalizeLongTermGoal(saved.longTermGoal);
                    if (!normalizedGoal.isBlank()) this.longTermGoal = normalizedGoal;
                }
                if (saved.longTermGoalProgress != null) {
                    this.longTermGoalProgress = Math.max(0, Math.min(100, saved.longTermGoalProgress));
                }
                if (saved.lastAutonomousDecision != null) {
                    this.lastAutonomousDecision = saved.lastAutonomousDecision;
                }
                if (saved.lastActionOutcome != null) {
                    this.lastActionOutcome = saved.lastActionOutcome;
                }
                if (saved.hunger != null && saved.energy != null && saved.safety != null && saved.social != null) {
                    needs.setValues(saved.hunger, saved.energy, saved.safety, saved.social);
                }
                if (npcData != null && currentGoals.isEmpty() && npcData.defaultGoals() != null) {
                    addNormalizedGoals(npcData.defaultGoals());
                }
                if (npcData != null && isPlaceholderLongTermGoal(longTermGoal)) {
                    longTermGoal = Profession.byName(npcData.profession()).defaultLongTermGoal();
                }
                syncVanillaProfession();
            }
            BeyondTradingMod.LOGGER.debug("Loaded NPC data for {} ({})", getNpcName(), getUUID());
        } catch (Exception e) {
            BeyondTradingMod.LOGGER.warn("Failed to load NPC data for {}: {}", getUUID(), e.getMessage());
        }
    }

    public void saveToDisk() {
        Path file = getDataFile();
        if (file == null) return;

        // A newly created or naturally restored agent can be saved during
        // shutdown before anything has asked for its identity. Never persist
        // a blank NPC record just because the save happened early.
        NPCData identity = getNpcData();

        try {
            Files.createDirectories(file.getParent());

            NPCSaveData saved = new NPCSaveData();
            saved.npcData = identity;
            if (homePos != null) { saved.homeX = homePos.getX(); saved.homeY = homePos.getY(); saved.homeZ = homePos.getZ(); }
            if (workplacePos != null) { saved.workX = workplacePos.getX(); saved.workY = workplacePos.getY(); saved.workZ = workplacePos.getZ(); }
            saved.currentGoals = List.copyOf(this.currentGoals);
            saved.relationships = List.copyOf(this.relationships);
            saved.hunger = needs.hunger();
            saved.energy = needs.energy();
            saved.safety = needs.safety();
            saved.social = needs.social();
            saved.longTermGoal = longTermGoal;
            saved.longTermGoalProgress = longTermGoalProgress;
            saved.lastAutonomousDecision = lastAutonomousDecision;
            saved.lastActionOutcome = lastActionOutcome;

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(saved));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
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
        Integer hunger, energy, safety, social;
        String longTermGoal;
        Integer longTermGoalProgress;
        String lastAutonomousDecision;
        String lastActionOutcome;
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

    public NPCNeeds getNeeds() {
        return needs;
    }

    public String getLongTermGoal() {
        return longTermGoal;
    }

    public int getLongTermGoalProgress() {
        return longTermGoalProgress;
    }

    public void setLongTermGoal(String goal) {
        if (goal == null || goal.isBlank()) return;
        String normalized = normalizeLongTermGoal(goal);
        if (normalized.isBlank()) return;
        if (normalized.equalsIgnoreCase("none")
            || normalized.equalsIgnoreCase("null")
            || normalized.equalsIgnoreCase("no change")
            || normalized.equalsIgnoreCase("unchanged")
            || NPCAction.parse(normalized).isPresent()) return;
        if (GoalProgressPolicy.meaningfullyDifferent(longTermGoal, normalized)) {
            longTermGoalProgress = 0;
        }
        longTermGoal = normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    /** Keep model status annotations out of the durable human-readable goal. */
    private static String normalizeLongTermGoal(String goal) {
        String normalized = goal == null ? "" : goal.replaceAll("\\s+", " ").strip();
        normalized = normalized.replaceAll(
            "(?i)\\s*\\((?:progress|goal progress|status)[^)]*\\)\\s*$", "").strip();
        normalized = normalized.replaceAll(
            "(?i)\\s+progress\\s+\\d+\\s*/\\s*\\d+\\s*$", "").strip();
        return normalized;
    }

    public void advanceLongTermGoal(int amount) {
        longTermGoalProgress = Math.max(0, Math.min(100, longTermGoalProgress + amount));
    }

    // ---- NPC Data ----

    public void setNpcData(NPCData data) {
        this.npcData = data;
        this.dataLoaded = true;
        if (data != null && currentGoals.isEmpty() && data.defaultGoals() != null) {
            addNormalizedGoals(data.defaultGoals());
        }
        if (data != null && isPlaceholderLongTermGoal(longTermGoal)) {
            longTermGoal = Profession.byName(data.profession()).defaultLongTermGoal();
        }
        syncVanillaProfession();
    }

    private void syncVanillaProfession() {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        Profession profession = getProfession();
        // MERCHANT and NONE are custom categories. Preserve the native
        // profession for adopted fletchers, clerics, masons, etc. so their
        // original villager identity and offers remain intact.
        if (profession == Profession.MERCHANT || profession == Profession.NONE) return;
        ResourceKey<VillagerProfession> professionKey = switch (profession) {
            case FARMER -> VillagerProfession.FARMER;
            case LIBRARIAN -> VillagerProfession.LIBRARIAN;
            case BLACKSMITH -> VillagerProfession.TOOLSMITH;
            case EXPLORER -> VillagerProfession.CARTOGRAPHER;
            default -> VillagerProfession.NONE;
        };
        var professions = serverLevel.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
        setVillagerData(getVillagerData().withProfession(professions.getOrThrow(professionKey)));
    }

    public NPCData getNpcData() {
        if (!dataLoaded) loadFromDisk();
        if (npcData == null) {
            setNpcData(NPCData.defaults("Villager " + getUUID().toString().substring(0, 4)));
        }
        return npcData;
    }

    /** Used by adoption to distinguish a missing record from a repaired default. */
    public boolean hasNpcData() {
        if (!dataLoaded) loadFromDisk();
        return npcData != null;
    }

    public String getNpcName() {
        NPCData d = getNpcData();
        return d != null && d.name() != null && !d.name().isBlank()
            ? d.name() : "Villager " + getUUID().toString().substring(0, 4);
    }

    @Override
    public Component getName() {
        return Component.literal(getNpcName());
    }

    public void setLastAutonomousDecision(String decision) {
        this.lastAutonomousDecision = decision == null || decision.isBlank()
            ? "none" : decision;
    }

    public String getLastAutonomousDecision() {
        return lastAutonomousDecision;
    }

    /** Mark that this server instance completed an autonomous decision cycle. */
    public void markDecisionObserved() {
        this.lastDecisionGameTime = level().getGameTime();
        this.decisionObservedThisSession = true;
    }

    public boolean hasDecisionThisSession() {
        return decisionObservedThisSession;
    }

    public long getLastDecisionGameTime() {
        return lastDecisionGameTime;
    }

    public void setLastActionOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            this.lastActionOutcome = "none";
            return;
        }
        String normalized = outcome.strip();
        this.lastActionOutcome = normalized.length() > 240
            ? normalized.substring(0, 240) : normalized;
    }

    public String getLastActionOutcome() {
        return lastActionOutcome;
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

    public synchronized List<String> getCurrentGoals() {
        return List.copyOf(currentGoals);
    }

    public void setCurrentGoals(List<String> goals) {
        currentGoals.clear();
        addNormalizedGoals(goals);
    }

    private void addNormalizedGoals(List<String> goals) {
        if (goals == null) return;
        for (String goal : goals) {
            String normalized = switch (goal == null ? "" : goal.toUpperCase(Locale.ROOT)) {
                case "TEND_CROPS", "HARVEST", "SMITH", "READ", "ORGANIZE", "CATALOG",
                    "EXPLORE", "MAP", "SCOUT", "STOCK", "BARTER" -> "GO_TO_WORK";
                case "RESTOCK" -> "RESTOCK_MATERIALS";
                default -> goal == null ? null : goal.toUpperCase(Locale.ROOT);
            };
            if (normalized != null && !normalized.isBlank() && !currentGoals.contains(normalized)) {
                currentGoals.add(normalized);
            }
        }
    }

    private static boolean isPlaceholderLongTermGoal(String goal) {
        if (goal == null || goal.isBlank()
            || goal.equalsIgnoreCase("none")
            || goal.equalsIgnoreCase("null")
            || goal.equalsIgnoreCase("no change")
            || goal.equalsIgnoreCase("unchanged")) {
            return true;
        }
        return NPCAction.parse(goal).isPresent();
    }

    // ---- Relationships ----

    public synchronized List<Relationship> getRelationships() {
        return List.copyOf(relationships);
    }

    public synchronized Relationship getRelationship(String targetId) {
        for (Relationship r : relationships) {
            if (r.targetId().equals(targetId)) return r;
        }
        return null;
    }

    public synchronized void addOrUpdateRelationship(Relationship rel) {
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
        this.lastPlayerInteractionGameTime = level().getGameTime();
    }

    public Player getLastPlayerInteracted() {
        return lastPlayerInteracted;
    }

    /** Player influence lasts for two in-game minutes unless renewed. */
    public Player getRecentPlayerInteracted() {
        return hasRecentPlayerInteraction() ? lastPlayerInteracted : null;
    }

    public boolean hasRecentPlayerInteraction() {
        return lastPlayerInteracted != null
            && lastPlayerInteracted.isAlive()
            && level().getGameTime() - lastPlayerInteractionGameTime <= 2400;
    }

    private boolean isFollowTargetValid() {
        return followTarget == lastPlayerInteracted && hasRecentPlayerInteraction();
    }
}
