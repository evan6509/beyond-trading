package com.beyondtrading.npc.behavior;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;

public enum NPCAction {
    GO_HOME,
    STAND_STILL;

    public void execute(AINPCEntity npc) {
        switch (this) {
            case GO_HOME -> {
                BlockPos home = npc.getHomePos();
                if (home != null) {
                    npc.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 0.6);
                }
            }
            case STAND_STILL -> npc.getNavigation().stop();
        }
    }
}
