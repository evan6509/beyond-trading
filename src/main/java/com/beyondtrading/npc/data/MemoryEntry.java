package com.beyondtrading.npc.data;

public class MemoryEntry {
    public int day;
    public String event;
    public int importance;

    public MemoryEntry() {}

    public MemoryEntry(int day, String event, int importance) {
        this.day = day;
        this.event = event;
        this.importance = importance;
    }
}
