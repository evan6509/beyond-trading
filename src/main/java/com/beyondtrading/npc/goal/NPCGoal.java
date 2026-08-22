package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;

public abstract class NPCGoal {
    protected final GoalType type;
    protected int ticksRemaining;

    protected NPCGoal(GoalType type, int ticksRemaining) {
        this.type = type;
        this.ticksRemaining = ticksRemaining;
    }

    public GoalType getType() { return type; }

    public abstract void start(AINPCEntity npc);

    public abstract void tick(AINPCEntity npc);

    public abstract boolean isComplete(AINPCEntity npc);

    public void interrupt(AINPCEntity npc) {
        npc.getNavigation().stop();
    }

    public String getStatus() {
        return type.name() + " (" + ticksRemaining + " ticks)";
    }
}
