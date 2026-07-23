package com.beyondtrading.npc.data;

import java.util.List;

public record NPCData(
    String name,
    List<String> personality,
    String role
) {}
