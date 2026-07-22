package com.beyondtrading.entity;

import com.beyondtrading.npc.ai.NPCManager;
import com.beyondtrading.npc.conversation.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class AINPCEntity extends PathfinderMob {
    private BlockPos homePos;
    private BlockPos farmPos;
    private LivingEntity followTarget;
    private Player lastPlayerInteracted;

    public AINPCEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FollowPlayerGoal(this, 2.0, 10.0F, 3.0F));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.6));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hitPos) {
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        setLastPlayerInteracted(player);

        ServerLevel serverLevel = (ServerLevel) level();
        NPCManager npcManager = NPCManager.get(serverLevel.getServer());
        npcManager.initializeNPC(getUUID(), "thomas", this);

        ConversationManager.get().startConversation(player, this);

        return InteractionResult.SUCCESS;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (followTarget != null && followTarget.isAlive()) {
            if (distanceToSqr(followTarget) > 2.0) {
                getNavigation().moveTo(followTarget, 0.6);
            }
        }
    }

    public void setHomePos(BlockPos pos) {
        this.homePos = pos;
    }

    public BlockPos getHomePos() {
        return homePos;
    }

    public void setFarmPos(BlockPos pos) {
        this.farmPos = pos;
    }

    public BlockPos getFarmPos() {
        return farmPos;
    }

    public void setFollowTarget(LivingEntity target) {
        this.followTarget = target;
    }

    public void clearFollowTarget() {
        this.followTarget = null;
        getNavigation().stop();
    }

    public LivingEntity getFollowTarget() {
        return followTarget;
    }

    public void setLastPlayerInteracted(Player player) {
        this.lastPlayerInteracted = player;
    }

    public Player getLastPlayerInteracted() {
        return lastPlayerInteracted;
    }

    private static class FollowPlayerGoal extends Goal {
        private final AINPCEntity npc;
        private final double speed;
        private final float maxDist;
        private final float minDist;

        public FollowPlayerGoal(AINPCEntity npc, double speed, float maxDist, float minDist) {
            this.npc = npc;
            this.speed = speed;
            this.maxDist = maxDist;
            this.minDist = minDist;
        }

        @Override
        public boolean canUse() {
            return npc.getFollowTarget() != null && npc.getFollowTarget().isAlive()
                && npc.distanceToSqr(npc.getFollowTarget()) > minDist * minDist;
        }

        @Override
        public boolean canContinueToUse() {
            return npc.getFollowTarget() != null && npc.getFollowTarget().isAlive()
                && npc.distanceToSqr(npc.getFollowTarget()) > maxDist * maxDist;
        }

        @Override
        public void start() {
            npc.getNavigation().moveTo(npc.getFollowTarget(), speed);
        }

        @Override
        public void tick() {
            if (npc.getFollowTarget() != null) {
                npc.getNavigation().moveTo(npc.getFollowTarget(), speed);
            }
        }

        @Override
        public void stop() {
            npc.getNavigation().stop();
        }
    }
}
