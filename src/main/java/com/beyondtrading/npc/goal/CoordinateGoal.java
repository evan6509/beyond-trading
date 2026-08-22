package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.schedule.VillageCoordinator;

import java.util.Comparator;

public class CoordinateGoal extends NPCGoal {
    private AINPCEntity target;
    private boolean coordinated;

    public CoordinateGoal() {
        super(GoalType.COORDINATE, 400);
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        coordinated = false;
        target = npc.level().getEntitiesOfClass(AINPCEntity.class,
                npc.getBoundingBox().inflate(24),
                other -> other != npc && other.isAlive())
            .stream()
            .min(Comparator
                .comparingInt(VillageCoordinator::supportPriority)
                .thenComparingDouble(npc::distanceToSqr))
            .orElse(null);
        if (target != null) npc.getNavigation().moveTo(target, 0.6);
        else npc.setLastActionOutcome("wanted to coordinate but no neighbor was nearby");
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (target == null || !target.isAlive()) {
            ticksRemaining -= 10;
            return;
        }
        if (npc.distanceToSqr(target) > 9.0) {
            npc.getNavigation().moveTo(target, 0.6);
        } else if (!coordinated) {
            VillageCoordinator.CoordinationResult result =
                VillageCoordinator.coordinate(npc, target);
            npc.setLastActionOutcome(result.outcome());
            coordinated = true;
        }
        ticksRemaining--;
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }
}
