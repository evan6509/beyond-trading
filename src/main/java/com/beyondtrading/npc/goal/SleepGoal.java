package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public class SleepGoal extends NPCGoal {
    private static final int SLEEP_INTERVAL = 100;
    private boolean arrived;
    private int approachTicks;
    private BlockPos home;

    public SleepGoal() {
        super(GoalType.SLEEP, 72000);
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        home = findBed(npc, npc.getHomePos());
        if (home == null) {
            ticksRemaining = 0;
            npc.setLastActionOutcome(npc.getHomePos() == null
                ? "could not sleep; no home is known"
                : "could not sleep; no usable bed was found near home");
            return;
        }
        // A saved bed may have moved or the agent may have been summoned
        // without a home. Once a usable bed is found, keep that discovery as
        // the villager's durable home anchor for later routines.
        npc.setHomePos(home);
        npc.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 0.6);
        arrived = false;
        approachTicks = 0;
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (!arrived) {
            approachTicks++;
            if (npc.distanceToSqr(home.getX(), home.getY(), home.getZ()) <= 9.0) {
                arrived = true;
            } else if (npc.getNavigation().isDone() && approachTicks > 20) {
                npc.setLastActionOutcome("could not reach home to sleep");
                ticksRemaining = 0;
                return;
            }
        }
        if (arrived && !npc.isSleeping() && npc.getHomePos() != null) {
            npc.startSleeping(npc.getHomePos());
            if (!npc.isSleeping()) {
                npc.setLastActionOutcome("arrived home but no usable bed was available");
                ticksRemaining = 0;
                return;
            }
        }
        ticksRemaining--;
        if (arrived && npc.isSleeping() && ticksRemaining % 20 == 0) {
            npc.getNeeds().onSleepTick();
        }
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        if (ticksRemaining <= 0) {
            finishSleep(npc);
            return true;
        }
        if (ticksRemaining % SLEEP_INTERVAL == 0) {
            long time = npc.level().getOverworldClockTime() % 24000;
            if (time < 13000) {
                finishSleep(npc);
                return true;
            }
        }
        return false;
    }

    @Override
    public void interrupt(AINPCEntity npc) {
        npc.stopSleeping();
        super.interrupt(npc);
    }

    private BlockPos findBed(AINPCEntity npc, BlockPos preferred) {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;

        BlockPos center = preferred != null ? preferred : npc.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(candidate);
                    if (!state.is(BlockTags.BEDS) || !level.getBlockState(candidate.above()).isAir()) {
                        continue;
                    }
                    double distance = npc.distanceToSqr(candidate.getX(), candidate.getY(), candidate.getZ());
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private void finishSleep(AINPCEntity npc) {
        if (npc.isSleeping()) {
            npc.stopSleeping();
            npc.setLastActionOutcome("slept at home");
        } else {
            npc.stopSleeping();
        }
    }
}
