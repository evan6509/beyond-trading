package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.schedule.NPCScheduler;
import net.minecraft.world.entity.LivingEntity;

public class SocializeGoal extends NPCGoal {
    private LivingEntity target;
    private boolean socialized;

    public SocializeGoal() {
        super(GoalType.SOCIALIZE, 400);
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        socialized = false;
        target = npc.level().getEntitiesOfClass(AINPCEntity.class,
                npc.getBoundingBox().inflate(20),
                other -> other != npc && other.isAlive())
            .stream().findFirst().orElse(null);

        if (target != null) {
            npc.getNavigation().moveTo(target, 0.6);
        } else {
            npc.setLastActionOutcome("wanted to socialize but no neighbor was nearby");
        }
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (target != null) {
            if (target.isAlive() && npc.distanceToSqr(target) > 4.0) {
                npc.getNavigation().moveTo(target, 0.6);
            } else if (target instanceof AINPCEntity other && !socialized) {
                Relationship relation = npc.getRelationship(other.getUUID().toString());
                npc.addOrUpdateRelationship(relation == null
                    ? Relationship.withNPC(other.getUUID().toString(), other.getNpcName()).withChange(2)
                    : relation.withChange(1));
                Relationship reverse = other.getRelationship(npc.getUUID().toString());
                other.addOrUpdateRelationship(reverse == null
                    ? Relationship.withNPC(npc.getUUID().toString(), npc.getNpcName()).withChange(1)
                    : reverse.withChange(1));
                int day = (int) (npc.level().getOverworldClockTime() / 24000);
                npc.remember(day, "Socialized with " + other.getNpcName(), 20);
                NPCScheduler.get().broadcast(npc, "Socialized with " + other.getNpcName());
                npc.getNeeds().onSocialInteraction();
                npc.advanceLongTermGoal(1);
                other.advanceLongTermGoal(1);
                npc.setLastActionOutcome("socialized with " + other.getNpcName());
                npc.saveToDisk();
                other.saveToDisk();
                socialized = true;
            }
            ticksRemaining--;
        } else {
            ticksRemaining -= 5;
        }
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }
}
