package com.beyondtrading.npc.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NPCData {
    public String name;
    public List<String> personality;
    public List<String> goals;
    public List<String> needs;
    public Map<String, Integer> relationships;

    public NPCData() {
        this.personality = new ArrayList<>();
        this.goals = new ArrayList<>();
        this.needs = new ArrayList<>();
        this.relationships = new HashMap<>();
    }

    public static NPCData thomas() {
        NPCData npc = new NPCData();
        npc.name = "Thomas";
        npc.personality.add("Friendly");
        npc.personality.add("Cautious");
        npc.personality.add("Hardworking");
        npc.goals.add("Farm crops");
        npc.goals.add("Protect home");
        npc.needs.add("Rest at night");
        npc.needs.add("Eat regularly");
        return npc;
    }
}
