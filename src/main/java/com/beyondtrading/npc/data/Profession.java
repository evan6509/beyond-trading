package com.beyondtrading.npc.data;

import java.util.List;

public enum Profession {
    NONE("None", "none", "none", List.of(), List.of("WANDER")),
    FARMER("Farmer", "farm", "farmhouse",
        List.of("TEND_CROPS", "HARVEST", "RESTOCK"),
        List.of("GO_TO_WORK", "RESTOCK_MATERIALS", "COORDINATE", "SOCIALIZE", "WANDER", "SLEEP")),
    BLACKSMITH("Blacksmith", "forge", "blacksmith_house",
        List.of("SMITH", "REPAIR", "RESTOCK"),
        List.of("GO_TO_WORK", "RESTOCK_MATERIALS", "COORDINATE", "SOCIALIZE", "WANDER", "SLEEP")),
    LIBRARIAN("Librarian", "library", "library_home",
        List.of("READ", "ORGANIZE", "CATALOG"),
        List.of("GO_TO_WORK", "RESTOCK_MATERIALS", "COORDINATE", "SOCIALIZE", "WANDER", "SLEEP")),
    EXPLORER("Explorer", "map_room", "cabin",
        List.of("EXPLORE", "MAP", "SCOUT"),
        List.of("GO_TO_WORK", "RESTOCK_MATERIALS", "COORDINATE", "WANDER", "SLEEP")),
    MERCHANT("Merchant", "trading_post", "warehouse",
        List.of("TRADE", "STOCK", "BARTER"),
        List.of("GO_TO_WORK", "TRADE", "RESTOCK_MATERIALS", "COORDINATE", "SOCIALIZE", "WANDER", "SLEEP"));

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

    /** A human-readable priority gives a new agent a meaningful starting drive. */
    public String defaultLongTermGoal() {
        return switch (this) {
            case FARMER -> "Keep the village fed through every season";
            case BLACKSMITH -> "Keep the village's tools and defenses in working order";
            case LIBRARIAN -> "Preserve useful knowledge for the village";
            case EXPLORER -> "Map safe routes and useful resources around the village";
            case MERCHANT -> "Keep the village market supplied and fair";
            case NONE -> "Find a useful place in the village and help where needed";
        };
    }

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
