package com.beyondtrading.npc.data;

public record Relationship(
    String targetId,
    String targetName,
    String type,
    int familiarity,
    long lastInteraction
) {
    public static Relationship withPlayer(String playerId, String playerName) {
        return new Relationship(playerId, playerName, "player", 0, System.currentTimeMillis());
    }

    public static Relationship withNPC(String npcId, String npcName) {
        return new Relationship(npcId, npcName, "npc", 0, System.currentTimeMillis());
    }

    public Relationship withChange(int delta) {
        return new Relationship(targetId, targetName, type,
            Math.min(100, Math.max(0, familiarity + delta)), System.currentTimeMillis());
    }

    public Relationship withInteraction() {
        return new Relationship(targetId, targetName, type, familiarity, System.currentTimeMillis());
    }
}
