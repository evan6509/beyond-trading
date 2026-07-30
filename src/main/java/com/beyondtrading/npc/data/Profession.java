package com.beyondtrading.npc.data;

import java.util.List;

public enum Profession {
    NONE("None", "none", "none", List.of(), List.of("WANDER")),
    FARMER("Farmer", "farm", "farmhouse",
        List.of("TEND_CROPS", "HARVEST", "RESTOCK"),
        List.of("TEND_CROPS", "RESTOCK", "SOCIALIZE", "WANDER", "SLEEP")),
    BLACKSMITH("Blacksmith", "forge", "blacksmith_house",
        List.of("SMITH", "REPAIR", "RESTOCK"),
        List.of("SMITH", "RESTOCK", "SOCIALIZE", "WANDER", "SLEEP")),
    LIBRARIAN("Librarian", "library", "library_home",
        List.of("READ", "ORGANIZE", "CATALOG"),
        List.of("READ", "ORGANIZE", "SOCIALIZE", "WANDER", "SLEEP")),
    EXPLORER("Explorer", "map_room", "cabin",
        List.of("EXPLORE", "MAP", "SCOUT"),
        List.of("EXPLORE", "MAP", "WANDER", "SLEEP")),
    MERCHANT("Merchant", "trading_post", "warehouse",
        List.of("TRADE", "STOCK", "BARTER"),
        List.of("TRADE", "STOCK", "SOCIALIZE", "WANDER", "SLEEP"));

    private final String displayName;
    private final String workplaceType;
    private final String homeType;
    private final List<String> dailyActivities;
    private final List<String> availableGoals;

    Profession(String displayName, String workplaceType, String homeType,
               List<String> dailyActivities, List<String> availableGoals) {
        this.displayName = displayName;
        this.workplaceType = workplaceType;
        this.homeType = homeType;
        this.dailyActivities = dailyActivities;
        this.availableGoals = availableGoals;
    }

    public String displayName() { return displayName; }
    public String workplaceType() { return workplaceType; }
    public String homeType() { return homeType; }
    public List<String> dailyActivities() { return dailyActivities; }
    public List<String> availableGoals() { return availableGoals; }

    public static Profession byName(String name) {
        if (name == null) return NONE;
        for (Profession p : values()) {
            if (p.name().equalsIgnoreCase(name) || p.displayName.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return NONE;
    }
}
