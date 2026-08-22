package com.beyondtrading.npc.brain;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.PromptContext;
import com.beyondtrading.llm.WorldContext;
import com.beyondtrading.npc.behavior.NPCAction;
import com.beyondtrading.npc.behavior.NPCBehaviorController;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.goal.HelpPlayerGoal;
import com.beyondtrading.npc.goal.CoordinateGoal;
import com.beyondtrading.npc.goal.FleeGoal;
import com.beyondtrading.npc.goal.NPCGoal;
import com.beyondtrading.npc.goal.RestockGoal;
import com.beyondtrading.npc.goal.SleepGoal;
import com.beyondtrading.npc.goal.SocializeGoal;
import com.beyondtrading.npc.goal.TravelGoal;
import com.beyondtrading.npc.goal.WanderGoal;
import com.beyondtrading.npc.goal.WorkGoal;
import com.beyondtrading.npc.goal.GoalType;
import com.beyondtrading.npc.schedule.VillageCoordinator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.monster.Monster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The autonomous controller for one villager.
 *
 * The local LLM chooses a bounded action every few seconds. The actual
 * movement is still performed by Minecraft navigation and goal classes, so a
 * model cannot execute arbitrary code or directly mutate the world.
 */
public class NPCBrain {
    private static final int DECISION_INTERVAL_TICKS = 400; // 20 seconds
    private static final int MAX_FAILURE_BACKOFF_TICKS = 6000; // 5 minutes

    private final AINPCEntity npc;
    private final LLMInterface llm;
    private final NPCBehaviorController behaviorController;
    private final ProfessionBehavior professionBehavior;

    private NPCGoal currentGoal;
    private boolean paused;
    private boolean requestInFlight;
    private int tickCounter;
    private int nextDecisionTick;
    private long decisionSequence;
    private boolean emergencyReturning;
    private int decisionFailureStreak;
    private long lastDecisionFailureMemoryGameTime = Long.MIN_VALUE;
    private long lastActionOutcomeMemoryGameTime = Long.MIN_VALUE;

    public NPCBrain(AINPCEntity npc) {
        this.npc = npc;
        this.llm = new LLMInterface();
        this.behaviorController = new NPCBehaviorController();
        this.professionBehavior = ProfessionBehavior.forProfession(npc.getProfession());
        this.paused = false;
        this.requestInFlight = false;
        this.tickCounter = 0;
        this.nextDecisionTick = 20 + Math.floorMod(npc.getUUID().hashCode(), DECISION_INTERVAL_TICKS);
        this.decisionSequence = 0;
        this.emergencyReturning = false;
        this.decisionFailureStreak = 0;
    }

    public void tick() {
        if (paused) return;
        tickCounter++;

        if (enforceEmergencyPolicy()) return;

        if (currentGoal != null) {
            if (!currentGoal.isComplete(npc)) {
                currentGoal.tick(npc);
            }
            if (currentGoal.isComplete(npc)) {
                String completed = currentGoal.getType().name();
                String outcome = npc.getLastActionOutcome();
                if (outcome != null && !outcome.isBlank()
                    && !outcome.startsWith("started ")
                    && !outcome.startsWith("LLM unavailable")) {
                    int day = (int) (npc.level().getOverworldClockTime() / 24000);
                    npc.remember(day, "Completed " + completed + ": " + outcome, 15);
                }
                if (outcome == null || outcome.isBlank() || outcome.startsWith("started ")) {
                    npc.setLastActionOutcome("completed " + completed);
                } else {
                    npc.setLastActionOutcome("completed " + completed + ": " + outcome);
                }
                currentGoal = null;
            }
        }

        if (!requestInFlight && tickCounter >= nextDecisionTick) {
            requestDecision();
        }
    }

    private boolean enforceEmergencyPolicy() {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;

        boolean night = level.getOverworldClockTime() % 24000 >= 13000;
        boolean hungerCritical = npc.getNeeds().hungerIsCritical();
        boolean energyCritical = npc.getNeeds().energyIsCritical();

        // Needs are game-state safety rails, not suggestions for the model.
        // A villager that becomes critically hungry during a long work goal
        // must be allowed to eat immediately instead of waiting for the next
        // 20-second LLM decision window. If food is unavailable, eat() starts
        // the bounded restock routine and the routine is allowed to finish.
        if (hungerCritical) {
            if (currentGoal != null
                && currentGoal.getType() == GoalType.RESTOCK_MATERIALS) {
                return false;
            }
            interruptCurrentGoal();
            npc.setLastActionOutcome("emergency: hunger critical; seeking food");
            eat(npc);
            return true;
        }

        // Sleeping is only useful once Minecraft permits it. During the day,
        // keep the normal work/fallback policy in control rather than
        // repeatedly starting a sleep goal that cannot enter a bed.
        if (energyCritical && night && npc.getHomePos() != null) {
            if (currentGoal != null && currentGoal.getType() == GoalType.SLEEP) {
                return false;
            }
            interruptCurrentGoal();
            npc.setLastActionOutcome("emergency: energy critical; going to sleep");
            startGoal(new SleepGoal());
            return true;
        }

        boolean nearbyDanger = !level.getEntitiesOfClass(
            Monster.class, npc.getBoundingBox().inflate(12), entity -> entity.isAlive()).isEmpty();
        if (npc.getHomePos() == null) {
            if (!nearbyDanger) return false;
            if (currentGoal != null && currentGoal.getType() == GoalType.FLEE) return false;
            interruptCurrentGoal();
            npc.setLastActionOutcome("emergency: no home known; fleeing hostile danger");
            startGoal(new FleeGoal());
            return true;
        }

        boolean emergency = npc.getNeeds().safety() < 20 || level.isThundering() || nearbyDanger;
        if (!emergency) {
            emergencyReturning = false;
            return false;
        }

        if (currentGoal != null && currentGoal.getType() == GoalType.GO_HOME) {
            // Let the bounded travel goal finish while the emergency policy
            // prevents new discretionary decisions.
            return false;
        }
        if (currentGoal != null && currentGoal.getType() == GoalType.SLEEP && !nearbyDanger) {
            // Sleeping is a valid recovery for low safety or weather alone;
            // an actual hostile in the radius must still wake the villager.
            return false;
        }
        if (!emergencyReturning) {
            interruptCurrentGoal();
            emergencyReturning = true;
            npc.setLastActionOutcome(level.isThundering()
                ? "emergency: thunderstorm; returning home"
                : nearbyDanger
                    ? "emergency: hostile danger nearby; returning home"
                    : "emergency: safety critical; returning home");
            startHomeGoal();
        }
        return true;
    }

    private void requestDecision() {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        requestInFlight = true;
        nextDecisionTick = tickCounter + DECISION_INTERVAL_TICKS;
        long requestId = ++decisionSequence;
        MinecraftServer server = level.getServer();
        List<Map<String, String>> messages = buildDecisionMessages();

        CompletableFuture.runAsync(() -> {
            var response = llm.chatStructured(messages);
            server.execute(() -> finishDecision(requestId, response.orElse(null)));
        }).exceptionally(error -> {
            server.execute(() -> finishDecision(requestId, null));
            return null;
        });
    }

    /** Force one immediate decision for in-game debugging and demonstrations. */
    public boolean requestDecisionNow() {
        if (paused || requestInFlight
            || !(npc.level() instanceof net.minecraft.server.level.ServerLevel)) {
            return false;
        }
        nextDecisionTick = tickCounter;
        requestDecision();
        return true;
    }

    private List<Map<String, String>> buildDecisionMessages() {
        NPCData data = npc.getNpcData();
        Profession profession = npc.getProfession();
        String traits = data != null && data.traits() != null
            ? String.join(", ", data.traits()) : "friendly";
        String goals = npc.getCurrentGoals().isEmpty()
            ? "none" : String.join(", ", npc.getCurrentGoals());
        String relationships = npc.getRelationships().isEmpty() ? "none"
            : npc.getRelationships().stream()
                .sorted(java.util.Comparator.comparingInt(
                    com.beyondtrading.npc.data.Relationship::familiarity).reversed())
                .limit(8)
                .map(r -> r.targetName() + " (" + r.type() + ", familiarity "
                    + r.familiarity() + ")")
                .reduce((left, right) -> left + "; " + right).orElse("none");
        int currentDay = (int) (npc.level().getOverworldClockTime() / 24000);
        String memories = npc.getMemory() != null
            ? npc.getMemory().formatForPrompt(currentDay) : "";
        String activeGoal = currentGoal == null ? "none" : currentGoal.getStatus();

        String instructions = "You are the decision-making mind of the Minecraft villager "
            + npc.getNpcName() + ". You are " + traits + " and work as a "
            + profession.displayName().toLowerCase() + ".\n\n"
            + "Your profession-specific priorities are: "
            + String.join(", ", profession.availableGoals()) + ".\n"
            + "Choose exactly one bounded action for the next part of the day. "
            + "You may only choose: GO_HOME, GO_TO_WORK, TRADE, SLEEP, EAT, WANDER, "
            + "SOCIALIZE, COORDINATE, HELP_PLAYER, FOLLOW_PLAYER, RESTOCK_MATERIALS, or STAND_STILL.\n"
            + "GO_HOME and GO_TO_WORK navigate to known positions. EAT consumes food from "
            + "your inventory. FOLLOW_PLAYER only works "
            + "when a player has recently spoken to you. Do not invent actions, items, "
            + "coordinates, or abilities. In daylight, prefer useful work, restocking, or "
            + "coordination; at night, sleep unless a nearby player or urgent need matters; "
            + "treat danger and FOOD_SHORTAGE as reasons to change the routine. Preserve the "
            + "TRADE action for merchants with an engaged player; other professions should use GO_TO_WORK. "
            + "long-term goal unless circumstances justify changing it. The goal must be a short priority phrase; "
            + "do not include progress numbers, status text, parentheses, or an action name. Respond with JSON only in this exact shape: "
            + "{\"action\":\"ACTION\",\"dialogue\":\"short optional thought\","
            + "\"goal\":\"short long-term priority\"}.";

        String context = PromptContext.compose(PromptContext.DECISION_TOKEN_BUDGET, List.of(
            new PromptContext.Section("Identity", "name " + npc.getNpcName()
                + ", traits " + traits + ", profession " + profession.displayName(), 100),
            new PromptContext.Section("Live world", WorldContext.build(npc), 95),
            new PromptContext.Section("Needs", npc.getNeeds().formatForPrompt(), 94),
            new PromptContext.Section("Long-term priority", "Configured goals: " + goals
                + "\nLong-term goal: " + npc.getLongTermGoal() + " (progress "
                + npc.getLongTermGoalProgress() + "/100)", 92),
            new PromptContext.Section("Active bounded goal", activeGoal, 88),
            new PromptContext.Section("Last action outcome", npc.getLastActionOutcome(), 86),
            new PromptContext.Section("Relationships", relationships, 78),
            new PromptContext.Section("Memories", memories, 60)
        ));

        return List.of(
            Map.of("role", "system", "content", instructions + "\n\n" + context),
            Map.of("role", "user", "content", "Decide what this villager should do next."));
    }

    private void finishDecision(long requestId, LLMResponse response) {
        requestInFlight = false;
        if (requestId != decisionSequence || paused || !npc.isAlive()) return;
        npc.markDecisionObserved();

        if (response == null || !response.hasAction()) {
            npc.setLastAutonomousDecision("fallback");
            npc.setLastActionOutcome("LLM unavailable; using fallback");
            rememberDecisionFailure("LLM unavailable; used the safe fallback routine", 20);
            scheduleRetryAfterFailure();
            startFallbackIfNeeded();
            return;
        }

        var parsedAction = NPCAction.parse(response.action());
        if (parsedAction.isEmpty()) {
            npc.setLastAutonomousDecision("invalid action");
            npc.setLastActionOutcome("LLM returned an unsupported action");
            rememberDecisionFailure("LLM returned an unsupported action; used the safe fallback routine", 25);
            scheduleRetryAfterFailure();
            startFallbackIfNeeded();
            return;
        }

        decisionFailureStreak = 0;
        nextDecisionTick = tickCounter + DECISION_INTERVAL_TICKS;
        NPCAction action = validateAction(parsedAction.get());
        if (response.goal() != null && !response.goal().isBlank()) {
            npc.setLongTermGoal(response.goal());
        }
        npc.setLastAutonomousDecision(action.name());
        recordActionSelection(parsedAction.get(), action, "started");
        applyAction(action);
    }

    /** Back off only the model request; bounded game routines keep running. */
    private void scheduleRetryAfterFailure() {
        decisionFailureStreak = Math.min(6, decisionFailureStreak + 1);
        int multiplier = 1 << Math.max(0, decisionFailureStreak - 1);
        int delay = Math.min(MAX_FAILURE_BACKOFF_TICKS,
            DECISION_INTERVAL_TICKS * multiplier);
        nextDecisionTick = tickCounter + delay;
    }

    private NPCAction validateAction(NPCAction requested) {
        // The LLM supplies intent, but urgent safety rules belong to the game
        // layer so a bad or distracted response cannot strand the villager.
        if (requested == NPCAction.TRADE && npc.getProfession() != Profession.MERCHANT) {
            npc.setLastActionOutcome("overrode TRADE because this profession is not a merchant");
            return NPCAction.GO_TO_WORK;
        }
        if (npc.getNeeds().safety() < 20 && npc.getHomePos() != null
            && requested != NPCAction.GO_HOME && requested != NPCAction.SLEEP) {
            npc.setLastActionOutcome("overrode " + requested.name() + " because safety was critical");
            return NPCAction.GO_HOME;
        }
        if (npc.level() instanceof net.minecraft.server.level.ServerLevel level
            && level.isThundering() && npc.getHomePos() != null
            && requested != NPCAction.GO_HOME && requested != NPCAction.SLEEP) {
            npc.setLastActionOutcome("overrode " + requested.name() + " because of a thunderstorm");
            return NPCAction.GO_HOME;
        }

        if (npc.level() instanceof net.minecraft.server.level.ServerLevel level
            && level.getOverworldClockTime() % 24000 >= 13000
            && npc.getHomePos() != null
            && !hasNearbyPlayer(npc)
            && requested != NPCAction.SLEEP
            && requested != NPCAction.EAT) {
            npc.setLastActionOutcome("overrode " + requested.name() + " because it was nighttime");
            return NPCAction.SLEEP;
        }

        if (npc.level() instanceof net.minecraft.server.level.ServerLevel level
            && level.getOverworldClockTime() % 24000 < 13000
            && requested == NPCAction.STAND_STILL
            && npc.getWorkplacePos() != null
            && npc.getProfession() != Profession.NONE
            && !hasNearbyPlayer(npc)) {
            npc.setLastActionOutcome("overrode idling during daylight; going to work");
            return NPCAction.GO_TO_WORK;
        }
        return requested;
    }

    /** Preserve a game-layer override instead of replacing it with a generic start message. */
    private void recordActionSelection(NPCAction requested, NPCAction selected, String verb) {
        if (requested == selected) {
            npc.setLastActionOutcome(verb + " " + selected.name());
            return;
        }

        String reason = npc.getLastActionOutcome();
        if (reason == null || reason.isBlank() || reason.startsWith("started ")) {
            reason = "overrode " + requested.name();
        }
        npc.setLastActionOutcome(reason + "; " + verb + " " + selected.name());
    }

    private boolean hasNearbyPlayer(AINPCEntity npc) {
        return npc.level() instanceof net.minecraft.server.level.ServerLevel level
            && !level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                npc.getBoundingBox().inflate(12), player -> player.isAlive()).isEmpty();
    }

    private void applyAction(NPCAction action) {
        if (sameAsCurrentGoal(action)) return;
        interruptCurrentGoal();

        switch (action) {
            case GO_HOME -> startHomeGoal();
            case FOLLOW_PLAYER, STAND_STILL -> {
                behaviorController.execute(npc, action);
                if (action == NPCAction.FOLLOW_PLAYER) {
                    rememberActionOutcome("Follow action: " + npc.getLastActionOutcome(), 25);
                }
            }
            case GO_TO_WORK -> startGoal(new WorkGoal(professionBehavior.getWorkDurationTicks(npc)));
            case TRADE -> startGoal(new WorkGoal(
                GoalType.TRADE, professionBehavior.getWorkDurationTicks(npc)));
            case SLEEP -> startGoal(new SleepGoal());
            case EAT -> eat(npc);
            case WANDER -> startGoal(new WanderGoal());
            case SOCIALIZE -> startGoal(new SocializeGoal());
            case COORDINATE -> startGoal(new CoordinateGoal());
            case HELP_PLAYER -> startGoal(new HelpPlayerGoal());
            case RESTOCK_MATERIALS -> startGoal(new RestockGoal());
        }
    }

    private boolean sameAsCurrentGoal(NPCAction action) {
        if (currentGoal == null || currentGoal.isComplete(npc)) return false;
        GoalType type = currentGoal.getType();
        return switch (action) {
            case GO_HOME -> type == GoalType.GO_HOME;
            case GO_TO_WORK -> type == GoalType.WORK;
            case TRADE -> type == GoalType.TRADE;
            case SLEEP -> type == GoalType.SLEEP;
            case WANDER -> type == GoalType.WANDER;
            case SOCIALIZE -> type == GoalType.SOCIALIZE;
            case COORDINATE -> type == GoalType.COORDINATE;
            case HELP_PLAYER -> type == GoalType.HELP_PLAYER;
            case RESTOCK_MATERIALS -> type == GoalType.RESTOCK_MATERIALS;
            default -> false;
        };
    }

    private void eat(AINPCEntity npc) {
        net.minecraft.world.item.ItemStack food = null;
        for (var item : List.of(
            net.minecraft.world.item.Items.BREAD,
            net.minecraft.world.item.Items.CARROT,
            net.minecraft.world.item.Items.POTATO,
            net.minecraft.world.item.Items.BEETROOT,
            net.minecraft.world.item.Items.WHEAT)) {
            food = npc.getInventory().removeItemType(item, 1);
            if (!food.isEmpty()) break;
        }
        if (food == null || food.isEmpty()) {
            food = takeFoodFromNearbyStorage(npc);
        }
        if (food == null || food.isEmpty()) {
            npc.setLastActionOutcome("wanted to eat but had no food");
            rememberActionOutcome("Could not eat because no food was available", 30);
            if (npc.getWorkplacePos() != null) startGoal(new RestockGoal());
            return;
        }
        npc.getNeeds().onEat();
        npc.remember((int) (npc.level().getOverworldClockTime() / 24000),
            "Ate " + food.getHoverName().getString(), 20);
        npc.setLastActionOutcome("ate " + food.getHoverName().getString());
    }

    private net.minecraft.world.item.ItemStack takeFoodFromNearbyStorage(AINPCEntity npc) {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        net.minecraft.core.BlockPos center = npc.getWorkplacePos() != null
            ? npc.getWorkplacePos() : npc.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (!(level.getBlockEntity(center.offset(dx, dy, dz))
                        instanceof net.minecraft.world.Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        var stack = container.getItem(slot);
                        if (isFood(stack)) return container.removeItem(slot, 1);
                    }
                }
            }
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** Apply an action requested during a conversation while autonomy is paused. */
    public boolean applyExternalAction(String rawAction) {
        var parsedAction = NPCAction.parse(rawAction);
        if (parsedAction.isEmpty()) return false;
        NPCAction action = validateAction(parsedAction.get());
        npc.setLastAutonomousDecision(action.name());
        recordActionSelection(parsedAction.get(), action, "conversation requested");
        applyAction(action);
        return true;
    }

    private void startGoal(NPCGoal goal) {
        currentGoal = goal;
        currentGoal.start(npc);
    }

    private void startFallbackIfNeeded() {
        if (currentGoal != null && !currentGoal.isComplete(npc)) return;
        startFallbackGoal();
    }

    /** Keep offline/model errors explainable without turning them into spam. */
    private void rememberDecisionFailure(String event, int importance) {
        long gameTime = npc.level().getGameTime();
        if (lastDecisionFailureMemoryGameTime != Long.MIN_VALUE
            && gameTime - lastDecisionFailureMemoryGameTime < 2400) {
            return;
        }
        lastDecisionFailureMemoryGameTime = gameTime;
        int day = (int) (npc.level().getOverworldClockTime() / 24000);
        npc.remember(day, event, importance);
    }

    private void rememberActionOutcome(String event, int importance) {
        long gameTime = npc.level().getGameTime();
        if (lastActionOutcomeMemoryGameTime != Long.MIN_VALUE
            && gameTime - lastActionOutcomeMemoryGameTime < 1200) {
            return;
        }
        lastActionOutcomeMemoryGameTime = gameTime;
        int day = (int) (npc.level().getOverworldClockTime() / 24000);
        npc.remember(day, event, importance);
    }

    private void startFallbackGoal() {
        interruptCurrentGoal();
        long time = npc.level().getOverworldClockTime() % 24000;
        boolean night = time > 13000;

        if (npc.level() instanceof net.minecraft.server.level.ServerLevel level
            && level.isThundering() && npc.getHomePos() != null) {
            npc.setLastActionOutcome("thunderstorm; returning home");
            startHomeGoal();
        } else if (npc.getNeeds().hunger() < 25) {
            npc.setLastActionOutcome("fallback: eating because hunger was low");
            eat(npc);
        } else if (hasFood(npc)
            && VillageCoordinator.snapshot(npc).hungryResidents() > 0) {
            startGoal(new CoordinateGoal());
        } else if (VillageCoordinator.snapshot(npc).pressure().contains("MATERIAL_SHORTAGE")
            && npc.getWorkplacePos() != null) {
            startGoal(new RestockGoal());
        } else if (npc.getNeeds().safety() < 30 && npc.getHomePos() != null) {
            npc.setLastActionOutcome("low safety; returning home");
            startHomeGoal();
        } else if (npc.getNeeds().energy() < 25 && npc.getHomePos() != null) {
            startGoal(new SleepGoal());
        } else if (npc.getNeeds().social() < 20) {
            startGoal(new SocializeGoal());
        } else if (night && npc.getHomePos() != null) {
            startGoal(new SleepGoal());
        } else if (!night && npc.getWorkplacePos() != null) {
            startGoal(new WorkGoal(professionBehavior.getWorkDurationTicks(npc)));
        } else {
            startGoal(new WanderGoal());
        }
    }

    private boolean hasFood(AINPCEntity npc) {
        return npc.getInventory().getItems().stream().anyMatch(stack ->
            stack.is(net.minecraft.world.item.Items.BREAD)
                || stack.is(net.minecraft.world.item.Items.CARROT)
                || stack.is(net.minecraft.world.item.Items.POTATO)
                || stack.is(net.minecraft.world.item.Items.BEETROOT)
                || stack.is(net.minecraft.world.item.Items.WHEAT));
    }

    private boolean isFood(net.minecraft.world.item.ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.BREAD)
            || stack.is(net.minecraft.world.item.Items.CARROT)
            || stack.is(net.minecraft.world.item.Items.POTATO)
            || stack.is(net.minecraft.world.item.Items.BEETROOT)
            || stack.is(net.minecraft.world.item.Items.WHEAT);
    }

    private void interruptCurrentGoal() {
        if (currentGoal != null) {
            currentGoal.interrupt(npc);
            currentGoal = null;
        }
    }

    private void startHomeGoal() {
        startGoal(new TravelGoal(npc.getHomePos(), "go home"));
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
        nextDecisionTick = tickCounter + 1;
    }

    public void interrupt() {
        interruptCurrentGoal();
        decisionSequence++;
    }

    public NPCGoal getCurrentGoal() {
        return currentGoal;
    }

    public boolean isPaused() {
        return paused;
    }
}
