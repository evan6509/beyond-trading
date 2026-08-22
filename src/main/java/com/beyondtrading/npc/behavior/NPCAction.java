package com.beyondtrading.npc.behavior;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Optional;

public enum NPCAction {
    GO_HOME,
    GO_TO_WORK,
    TRADE,
    SLEEP,
    EAT,
    WANDER,
    SOCIALIZE,
    COORDINATE,
    HELP_PLAYER,
    FOLLOW_PLAYER,
    RESTOCK_MATERIALS,
    STAND_STILL;

    public static Optional<NPCAction> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void execute(AINPCEntity npc) {
        switch (this) {
            case GO_HOME -> {
                BlockPos home = npc.getHomePos();
                if (home != null) {
                    npc.getNavigation().moveTo(home.getX(), home.getY(), home.getZ(), 0.6);
                } else {
                    npc.setLastActionOutcome("wanted to go home but no home is known");
                }
            }
            case GO_TO_WORK -> {
                BlockPos workplace = npc.getWorkplacePos();
                if (workplace != null) {
                    npc.getNavigation().moveTo(
                        workplace.getX(), workplace.getY(), workplace.getZ(), 0.6);
                } else {
                    npc.setLastActionOutcome("wanted to go to work but no workplace is known");
                }
            }
            case FOLLOW_PLAYER -> {
                var player = npc.getRecentPlayerInteracted();
                if (player != null) {
                    npc.setFollowTarget(player);
                    npc.setLastActionOutcome("following " + player.getName().getString());
                } else {
                    npc.setLastActionOutcome("wanted to follow a player but no player was available");
                }
            }
            case STAND_STILL -> {
                npc.getNavigation().stop();
                npc.setLastActionOutcome("standing still");
            }
            default -> { }
        }
    }
}
