package com.beyondtrading.npc.behavior;

public enum NPCAction {
    GO_HOME,
    CONTINUE_FARMING,
    WARN_PLAYER,
    FARM,
    TRADE,
    TALK,
    FOLLOW_PLAYER,
    STAND_STILL;

    public static NPCAction fromString(String s) {
        if (s == null) return STAND_STILL;
        try {
            return valueOf(s.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STAND_STILL;
        }
    }
}
