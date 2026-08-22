package com.beyondtrading;

import com.beyondtrading.llm.LLMResponse;
import com.beyondtrading.llm.LLMResponseParser;
import com.beyondtrading.llm.PromptContext;
import com.beyondtrading.npc.behavior.NPCAction;
import com.beyondtrading.npc.data.NPCMemory;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCNeeds;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.data.GoalProgressPolicy;
import com.beyondtrading.config.LLMConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationContractTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyBoundedActionsAreAccepted() {
        assertEquals(NPCAction.GO_TO_WORK, NPCAction.parse(" go_to_work ").orElseThrow());
        assertEquals(NPCAction.TRADE, NPCAction.parse("trade").orElseThrow());
        assertEquals(NPCAction.COORDINATE, NPCAction.parse("coordinate").orElseThrow());
        assertTrue(NPCAction.parse("teleport_to_player").isEmpty());
        assertTrue(NPCAction.parse(null).isEmpty());
    }

    @Test
    void malformedOrConversationalResponsesCannotBecomeActions() {
        assertTrue(new LLMResponse("GO_HOME", "Go home", "Protect the farm").hasAction());
        assertFalse(new LLMResponse("talk", "Hello").hasAction());
        assertFalse(new LLMResponse(null, "not a decision").hasAction());
    }

    @Test
    void thinkingModelWrappersYieldOnlyTheFinalBoundedResponse() {
        LLMResponse response = LLMResponseParser.parse(
            "<think>Need to protect the farmer.</think>\n"
                + "```json\n{\"action\":\"GO_HOME\",\"dialogue\":\"I should get inside.\"}\n```"
        );
        assertEquals("GO_HOME", response.action());
        assertEquals("I should get inside.", response.dialogue());
    }

    @Test
    void thinkingModelJsonWithTrailingProseStillParses() {
        LLMResponse response = LLMResponseParser.parse(
            "I have considered the danger. Final answer: "
                + "{\"action\":\"SLEEP\",\"dialogue\":\"I am going to rest.\"}"
        );
        assertEquals("SLEEP", response.action());
    }

    @Test
    void invalidThinkingOutputHasNoExecutableAction() {
        LLMResponse response = LLMResponseParser.parse(
            "<think>The context was too long and the answer was truncated..."
        );
        assertFalse(response.hasAction());
        assertEquals("I need a moment to think.", response.dialogue());
    }

    @Test
    void modelFieldsAreBoundedBeforeTheyReachChatAndState() {
        String longDialogue = "dialogue ".repeat(100);
        String longGoal = "goal ".repeat(100);
        LLMResponse response = LLMResponseParser.parse(
            "{\"action\":\" GO_TO_WORK \",\"dialogue\":\""
                + longDialogue + "\",\"goal\":\"" + longGoal + "\"}");

        assertEquals("GO_TO_WORK", response.action());
        assertTrue(response.dialogue().length() <= 320);
        assertTrue(response.goal().length() <= 180);
        assertFalse(response.dialogue().contains("\n"));
    }

    @Test
    void blankDialogueGetsAStableSafeDefault() {
        assertEquals("Hello!", LLMResponseParser.parse(
            "{\"action\":\"STAND_STILL\",\"dialogue\":\" \"}").dialogue());
    }

    @Test
    void llmConfigDefaultsToFastStructuredReasoningAndNormalizesMissingValues() {
        assertEquals("none", LLMConfig.defaults().reasoningEffort());
        assertEquals("none", new LLMConfig("url", "key", "model", 1, null)
            .reasoningEffort());
        assertEquals("minimal", new LLMConfig("url", "key", "model", 1, " MINIMAL ")
            .reasoningEffort());
        assertEquals("on", new LLMConfig("url", "key", "model", 1, " ON ")
            .reasoningEffort());
    }

    @Test
    void configuredProfessionGoalsUseTheBoundedActionVocabulary() {
        assertTrue(Profession.FARMER.availableGoals().contains("GO_TO_WORK"));
        assertTrue(Profession.FARMER.availableGoals().contains("RESTOCK_MATERIALS"));
        assertFalse(Profession.FARMER.availableGoals().contains("TRADE"));
        assertFalse(Profession.FARMER.availableGoals().contains("TEND_CROPS"));
        assertEquals("WANDER", NPCData.defaults("unknown").defaultGoals().get(0));
        assertTrue(Profession.FARMER.defaultLongTermGoal().contains("fed"));
        assertTrue(Profession.MERCHANT.defaultLongTermGoal().contains("market"));
        assertTrue(Profession.MERCHANT.availableGoals().contains("TRADE"));
    }

    @Test
    void needsRemainBoundedAndRespondToSimulationEvents() {
        NPCNeeds needs = new NPCNeeds(-10, 200, 50, 50);
        assertEquals(0, needs.hunger());
        assertEquals(100, needs.energy());

        needs.onEat();
        needs.onSleepTick();
        needs.onSocialInteraction();
        assertTrue(needs.hunger() > 0);
        assertTrue(needs.energy() <= 100);
        assertTrue(needs.safety() <= 100);
        assertTrue(needs.social() <= 100);
    }

    @Test
    void criticalNeedsHaveExplicitGameLayerThresholds() {
        NPCNeeds needs = new NPCNeeds(12, 10, 80, 50);
        assertTrue(needs.hungerIsCritical());
        assertTrue(needs.energyIsCritical());

        needs.setValues(13, 11, 80, 50);
        assertFalse(needs.hungerIsCritical());
        assertFalse(needs.energyIsCritical());
    }

    @Test
    void goalParaphrasesDoNotResetLongTermProgress() {
        assertFalse(GoalProgressPolicy.meaningfullyDifferent(
            "Keep the village fed through every season",
            "Keep village fed through seasons"));
        assertTrue(GoalProgressPolicy.meaningfullyDifferent(
            "Keep the village fed through every season",
            "Preserve useful knowledge for the village"));
    }

    @Test
    void relationshipsStayBoundedAndUpdateInteractionTime() throws Exception {
        Relationship relationship = Relationship.withPlayer(
            UUID.randomUUID().toString(), "Alex");
        Relationship changed = relationship.withChange(500);
        assertEquals(100, changed.familiarity());
        assertTrue(changed.lastInteraction() >= relationship.lastInteraction());
        Relationship reduced = changed.withChange(-500);
        assertEquals(0, reduced.familiarity());
    }

    @Test
    void memoriesPersistAsReadableMarkdown() throws Exception {
        UUID id = UUID.randomUUID();
        NPCMemory memory = new NPCMemory(id, temporaryDirectory);
        memory.remember(3, "Harvested and replanted crops", 35);
        memory.flush();

        Path file = temporaryDirectory.resolve("beyondtrading/memories/" + id + ".md");
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("Harvested and replanted crops"));

        NPCMemory reloaded = new NPCMemory(id, temporaryDirectory);
        assertEquals(1, reloaded.getEntries().size());
        assertEquals("Harvested and replanted crops", reloaded.getEntries().get(0).event());
    }

    @Test
    void repeatedSameDayEventsDoNotCrowdOutUsefulMemory() {
        NPCMemory memory = new NPCMemory(UUID.randomUUID(), temporaryDirectory);
        memory.remember(3, "Could not eat because no food was available", 30);
        memory.remember(3, "Could not eat because no food was available", 30);
        assertEquals(1, memory.getEntries().size());
    }

    @Test
    void legacyMemoryFilesAreCompactedOnLoad() throws Exception {
        UUID id = UUID.randomUUID();
        Path directory = temporaryDirectory.resolve("beyondtrading/memories");
        Files.createDirectories(directory);
        StringBuilder markdown = new StringBuilder("# Memories\n\n");
        for (int i = 0; i < 25; i++) {
            markdown.append("### Day 1 — Legacy event ").append(i).append("\n")
                .append("Importance: ").append(i).append("/100\n\n");
        }
        Files.writeString(directory.resolve(id + ".md"), markdown);

        NPCMemory memory = new NPCMemory(id, temporaryDirectory);
        assertEquals(20, memory.getEntries().size());
        memory.flush();
        assertEquals(20, new NPCMemory(id, temporaryDirectory).getEntries().size());
    }

    @Test
    void memoryPromptsBalanceImportanceWithRecency() {
        UUID id = UUID.randomUUID();
        NPCMemory memory = new NPCMemory(id, temporaryDirectory);
        memory.remember(1, "An old historic event", 100);
        memory.remember(10, "A recent change that matters now", 60);

        String prompt = memory.formatForPrompt(10);
        assertTrue(prompt.indexOf("A recent change that matters now")
            < prompt.indexOf("An old historic event"));
    }

    @Test
    void promptContextKeepsHighPriorityStateInsideItsBudget() {
        String lowPriority = "old memory ".repeat(5_000);
        String context = PromptContext.compose(100, java.util.List.of(
            new PromptContext.Section("Memories", lowPriority, 1),
            new PromptContext.Section("Needs", "safety 12, hunger 80", 100),
            new PromptContext.Section("Goal", "return home", 90)
        ));

        assertTrue(context.length() <= 400);
        assertTrue(context.contains("Needs:"));
        assertTrue(context.contains("safety 12"));
        assertTrue(context.contains("Goal:"));
    }

    @Test
    void promptContextTruncatesAtReadableBoundaries() {
        String fitted = PromptContext.fit("first line\nsecond line\nthird line", 30);
        assertTrue(fitted.contains("first line"));
        assertTrue(fitted.contains("context truncated"));
        assertTrue(fitted.length() <= 30);
    }

    @Test
    void compactPromptPreservesSystemPrefixAndMessageRoles() {
        String longSystem = "Identity: Ada\nNeeds: safety 10\nMemories: " + "old event ".repeat(2_000);
        var compact = PromptContext.compactMessages(java.util.List.of(
            java.util.Map.of("role", "system", "content", longSystem),
            java.util.Map.of("role", "user", "content", "Decide now")
        ), 80);

        assertEquals("system", compact.get(0).get("role"));
        assertTrue(compact.get(0).get("content").contains("Identity: Ada"));
        assertEquals("Decide now", compact.get(1).get("content"));
        assertTrue(compact.get(0).get("content").length() <= 80);
    }

}
