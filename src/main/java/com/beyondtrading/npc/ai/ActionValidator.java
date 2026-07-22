package com.beyondtrading.npc.ai;

import java.util.Set;

public class ActionValidator {
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
        "GO_HOME",
        "CONTINUE_FARMING",
        "WARN_PLAYER",
        "FARM",
        "TRADE",
        "TALK",
        "FOLLOW_PLAYER",
        "STAND_STILL"
    );

    public String validate(String action) {
        if (action == null) return "STAND_STILL";
        String upper = action.strip().toUpperCase();
        if (ALLOWED_ACTIONS.contains(upper)) {
            return upper;
        }
        return "STAND_STILL";
    }
}
