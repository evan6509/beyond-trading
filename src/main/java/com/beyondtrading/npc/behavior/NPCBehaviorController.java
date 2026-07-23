package com.beyondtrading.npc.behavior;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class NPCBehaviorController {

    public void execute(AINPCEntity npc, String action) {
        if (npc == null || action == null) return;
        switch (action.toUpperCase()) {
            case "GO_HOME" -> goHome(npc);
            case "FOLLOW_PLAYER" -> followPlayer(npc);
            case "STAND_STILL" -> standStill(npc);
            default -> standStill(npc);
        }
    }

    private void goHome(AINPCEntity npc) {
        npc.clearFollowTarget();
        BlockPos home = npc.getHomePos();
        if (home != null) {
            npc.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 0.6);
        }
    }

    private void followPlayer(AINPCEntity npc) {
        Player player = npc.getLastPlayerInteracted();
        if (player != null && player.isAlive()) {
            npc.setFollowTarget(player);
        }
    }

    private void standStill(AINPCEntity npc) {
        npc.clearFollowTarget();
        npc.getNavigation().stop();
    }
}
