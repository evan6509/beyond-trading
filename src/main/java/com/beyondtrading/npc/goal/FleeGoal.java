package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/**
 * Game-layer emergency movement for an agent that has no known home.
 * The LLM never supplies a destination; Minecraft navigation executes the
 * bounded escape and the result becomes the villager's action outcome.
 */
public final class FleeGoal extends NPCGoal {
    private static final int MAX_TICKS = 160;
    private static final double THREAT_RADIUS = 16.0;
    private static final double SAFE_DISTANCE = 14.0;
    private int retargetCooldown;

    public FleeGoal() {
        super(GoalType.FLEE, MAX_TICKS);
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        retargetCooldown = 0;
        navigateAway(npc);
    }

    @Override
    public void tick(AINPCEntity npc) {
        Monster threat = nearestThreat(npc);
        if (threat == null || npc.distanceToSqr(threat) >= SAFE_DISTANCE * SAFE_DISTANCE) {
            npc.getNavigation().stop();
            npc.setLastActionOutcome("escaped nearby hostile danger");
            ticksRemaining = 0;
            return;
        }

        if (retargetCooldown-- <= 0 || npc.getNavigation().isDone()) {
            navigateAway(npc);
        }
        ticksRemaining--;
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }

    private void navigateAway(AINPCEntity npc) {
        Monster threat = nearestThreat(npc);
        if (threat == null) {
            ticksRemaining = 0;
            return;
        }

        Vec3 away = npc.position().subtract(threat.position());
        if (away.horizontalDistanceSqr() < 0.01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        BlockPos destination = BlockPos.containing(
            npc.getX() + away.x * 10.0,
            npc.getY(),
            npc.getZ() + away.z * 10.0);
        npc.getNavigation().moveTo(
            destination.getX(), destination.getY(), destination.getZ(), 0.8);
        retargetCooldown = 20;
        npc.setLastActionOutcome("fleeing from nearby hostile danger");
    }

    private Monster nearestThreat(AINPCEntity npc) {
        return npc.level().getEntitiesOfClass(
                Monster.class, npc.getBoundingBox().inflate(THREAT_RADIUS),
                entity -> entity.isAlive())
            .stream()
            .min(java.util.Comparator.comparingDouble(npc::distanceToSqr))
            .orElse(null);
    }
}
