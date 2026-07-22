package com.beyondtrading.npc.ai;

import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.NPCMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LLMPromptBuilder {

    public List<Map<String, String>> build(NPCData npc, NPCMemory memory,
                                           String situation, String playerMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(npc, memory, situation)));
        messages.add(Map.of("role", "user", "content", playerMessage));
        return messages;
    }

    private String buildSystemPrompt(NPCData npc, NPCMemory memory, String situation) {
        return """
You are %s, a Minecraft villager NPC.

Personality: %s
Goals: %s
Needs: %s

Current situation: %s

Recent memories:
%s

Available actions:
- GO_HOME: Walk to your bed and rest
- CONTINUE_FARMING: Keep working on the farm
- WARN_PLAYER: Alert the player about danger
- FARM: Tend to crops
- TRADE: Offer to trade items with player
- TALK: Have a conversation
- FOLLOW_PLAYER: Follow the player
- STAND_STILL: Stand in place and do nothing

Respond with JSON only:
{
  "action": "<one of the available actions>",
  "dialogue": "<what you say to the player in response>",
  "memory_update": "<important event to remember, or null>"
}""".formatted(
            npc.name,
            String.join(", ", npc.personality),
            String.join(", ", npc.goals),
            String.join(", ", npc.needs),
            situation,
            memory.formatForPrompt()
        );
    }
}
