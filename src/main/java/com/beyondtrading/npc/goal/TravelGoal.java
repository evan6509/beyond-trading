package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;

/**
 * Bounded navigation for an explicit destination chosen by the agent.
 * Minecraft owns pathfinding; this goal only observes arrival or a failed
 * navigation attempt and turns that result into durable agent state.
 */
public final class TravelGoal extends NPCGoal {
    private final BlockPos destination;
    private final String label;
    private boolean arrived;
    private int approachTicks;

    public TravelGoal(BlockPos destination, String label) {
        super(GoalType.GO_HOME, 600);
        this.destination = destination;
        this.label = label;
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        if (destination == null) {
            ticksRemaining = 0;
            npc.setLastActionOutcome("wanted to " + label + " but no destination is known");
            return;
        }
        npc.getNavigation().moveTo(destination.getX(), destination.getY(), destination.getZ(), 0.6);
        arrived = false;
        approachTicks = 0;
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (destination == null) {
            ticksRemaining = 0;
            return;
        }

        approachTicks++;
        if (npc.distanceToSqr(destination.getX(), destination.getY(), destination.getZ()) <= 9.0) {
            if (!arrived) {
                arrived = true;
                npc.setLastActionOutcome("arrived at " + label);
            }
            ticksRemaining = 0;
            return;
        }

        if (npc.getNavigation().isDone() && approachTicks > 20) {
            npc.setLastActionOutcome("could not reach " + label);
            ticksRemaining = 0;
            return;
        }
        ticksRemaining--;
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0 || arrived;
    }
}
