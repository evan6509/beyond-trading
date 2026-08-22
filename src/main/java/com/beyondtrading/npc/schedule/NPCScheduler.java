package com.beyondtrading.npc.schedule;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.entity.AINPCEntity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NPCScheduler {
    private static NPCScheduler instance;

    private final Set<AINPCEntity> tracked;

    private NPCScheduler() {
        this.tracked = ConcurrentHashMap.newKeySet();
    }

    public static NPCScheduler get() {
        if (instance == null) instance = new NPCScheduler();
        return instance;
    }

    public void register(AINPCEntity npc) {
        tracked.add(npc);
    }

    public void unregister(AINPCEntity npc) {
        tracked.remove(npc);
    }

    public void tick() {
        tracked.removeIf(npc -> !npc.isAlive());

        for (AINPCEntity npc : tracked) {
            if (!npc.isAlive()) continue;
            var brain = npc.getNPCBrain();
            if (brain != null) {
                brain.tick();
            }
        }
    }

    public int getTrackedCount() {
        return tracked.size();
    }

    public void clear() {
        tracked.clear();
    }

    /** Share a short event with nearby villagers as local village knowledge. */
    public void broadcast(AINPCEntity source, String event) {
        if (source == null || event == null || event.isBlank()) return;
        int day = (int) (source.level().getOverworldClockTime() / 24000);
        for (AINPCEntity other : tracked) {
            if (other == source || !other.isAlive() || other.level() != source.level()) continue;
            if (other.distanceToSqr(source) <= 32 * 32) {
                other.remember(day, source.getNpcName() + " reported: " + event, 25);
            }
        }
    }
}
