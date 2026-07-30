package com.beyondtrading.npc.data;

import java.util.List;

public record NPCData(
    String name,
    String profession,
    List<String> traits,
    String homeId,
    String workplaceId,
    List<String> defaultGoals
) {
    public static NPCData defaults(String name) {
        return new NPCData(name, "NONE", List.of("friendly"), null, null, List.of());
    }
}
