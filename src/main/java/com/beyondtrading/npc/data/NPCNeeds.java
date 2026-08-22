package com.beyondtrading.npc.data;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * Small persistent drives that give an agent reasons to prefer one action
 * over another. Values are intentionally simple and bounded so the LLM sees
 * a stable state instead of being asked to invent physiology.
 */
public class NPCNeeds {
    private int hunger;
    private int energy;
    private int safety;
    private int social;
    private Boolean lastDanger;
    private Boolean lastThunder;
    private Boolean lastRain;

    public NPCNeeds() {
        this(80, 80, 80, 50);
    }

    public NPCNeeds(int hunger, int energy, int safety, int social) {
        this.hunger = clamp(hunger);
        this.energy = clamp(energy);
        this.safety = clamp(safety);
        this.social = clamp(social);
        this.lastDanger = null;
        this.lastThunder = null;
        this.lastRain = null;
    }

    public synchronized void tick(AINPCEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;

        boolean night = level.getOverworldClockTime() % 24000 > 13000;
        boolean atHome = npc.getHomePos() != null
            && npc.distanceToSqr(npc.getHomePos().getX(), npc.getHomePos().getY(), npc.getHomePos().getZ()) < 25;
        boolean danger = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
            npc.getBoundingBox().inflate(12), entity -> entity.isAlive()).size() > 0;
        boolean thunder = level.isThundering();
        boolean rain = level.isRaining();

        rememberEnvironmentTransition(npc, danger, thunder, rain);

        int safetyDelta = danger ? -4 : 1;
        if (thunder) safetyDelta -= 3;
        else if (rain) safetyDelta -= 1;
        safety = clamp(safety + safetyDelta);

        if (level.getGameTime() % 1200 != 0) return;

        hunger = clamp(hunger - 1);

        energy = clamp(energy + (night && atHome ? 3 : -1));
        social = clamp(social - 1);
    }

    public synchronized void onSleepTick() {
        energy = clamp(energy + 4);
        safety = clamp(safety + 1);
    }

    public synchronized void onWorkTick() {
        energy = clamp(energy - 1);
        hunger = clamp(hunger - 1);
    }

    public synchronized void onEat() {
        hunger = clamp(hunger + 30);
    }

    public synchronized void onSocialInteraction() {
        social = clamp(social + 12);
    }

    public synchronized int hunger() { return hunger; }
    public synchronized int energy() { return energy; }
    public synchronized int safety() { return safety; }
    public synchronized int social() { return social; }

    /** Thresholds used by the game-layer emergency policy before an LLM turn. */
    public synchronized boolean hungerIsCritical() { return hunger <= 12; }
    public synchronized boolean energyIsCritical() { return energy <= 10; }

    public synchronized void setValues(int hunger, int energy, int safety, int social) {
        this.hunger = clamp(hunger);
        this.energy = clamp(energy);
        this.safety = clamp(safety);
        this.social = clamp(social);
    }

    public synchronized String formatForPrompt() {
        return "Needs: hunger " + hunger + "/100, energy " + energy + "/100, safety "
            + safety + "/100, social " + social + "/100";
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private void rememberEnvironmentTransition(AINPCEntity npc, boolean danger,
                                               boolean thunder, boolean rain) {
        int day = (int) (npc.level().getOverworldClockTime() / 24000);
        if (lastDanger != null && lastDanger != danger) {
            npc.remember(day, danger
                ? "Noticed hostile danger nearby"
                : "Nearby hostile danger passed", 40);
        }
        if (lastThunder != null && lastThunder != thunder) {
            npc.remember(day, thunder
                ? "A thunderstorm began; safety became a priority"
                : "The thunderstorm passed", 45);
        }
        if (lastRain != null && lastRain != rain && thunder == false) {
            npc.remember(day, rain ? "Rain began" : "The rain stopped", 20);
        }
        lastDanger = danger;
        lastThunder = thunder;
        lastRain = rain;
    }
}
