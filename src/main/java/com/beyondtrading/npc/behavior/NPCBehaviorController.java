package com.beyondtrading.npc.behavior;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;

public class NPCBehaviorController {

    public void execute(AINPCEntity entity, String actionString) {
        if (entity == null) return;
        NPCAction action = NPCAction.fromString(actionString);
        execute(entity, action);
    }

    public void execute(AINPCEntity entity, NPCAction action) {
        switch (action) {
            case GO_HOME -> goHome(entity);
            case CONTINUE_FARMING -> continueFarming(entity);
            case WARN_PLAYER -> warnPlayer(entity);
            case FARM -> farm(entity);
            case TRADE -> trade(entity);
            case TALK -> talk(entity);
            case FOLLOW_PLAYER -> followPlayer(entity);
            case STAND_STILL -> standStill(entity);
        }
    }

    private void goHome(AINPCEntity entity) {
        entity.clearFollowTarget();
        var home = entity.getHomePos();
        if (home != null) {
            entity.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 0.6);
        }
    }

    private void continueFarming(AINPCEntity entity) {
        entity.clearFollowTarget();
        var farm = entity.getFarmPos();
        if (farm != null) {
            entity.getNavigation().moveTo(farm.getX(), farm.getY(), farm.getZ(), 0.5);
        }
    }

    private void warnPlayer(AINPCEntity entity) {
        standStill(entity);
    }

    private void farm(AINPCEntity entity) {
        continueFarming(entity);
    }

    private void trade(AINPCEntity entity) {
        standStill(entity);
    }

    private void talk(AINPCEntity entity) {
        standStill(entity);
    }

    private void followPlayer(AINPCEntity entity) {
        Player player = entity.getLastPlayerInteracted();
        if (player != null && player.isAlive()) {
            entity.setFollowTarget(player);
        }
    }

    private void standStill(AINPCEntity entity) {
        entity.clearFollowTarget();
        entity.getNavigation().stop();
    }
}
