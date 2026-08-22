package com.beyondtrading.npc.behavior;

import com.beyondtrading.entity.AINPCEntity;

public class NPCBehaviorController {

    public void execute(AINPCEntity npc, NPCAction action) {
        if (npc == null || action == null) return;
        switch (action) {
            case GO_HOME, GO_TO_WORK, FOLLOW_PLAYER, STAND_STILL -> {
                npc.clearFollowTarget();
                action.execute(npc);
            }
            default -> { }
        }
    }

    public void execute(AINPCEntity npc, String action) {
        if (npc == null || action == null) return;
        NPCAction.parse(action).ifPresentOrElse(
            parsed -> execute(npc, parsed),
            () -> execute(npc, NPCAction.STAND_STILL));
    }
}
