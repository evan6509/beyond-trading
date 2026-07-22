package com.beyondtrading.npc.ai;

public record LLMResponse(String action, String dialogue, String memoryUpdate) {
    public boolean hasMemoryUpdate() {
        return memoryUpdate != null && !memoryUpdate.isBlank()
            && !memoryUpdate.equalsIgnoreCase("null");
    }
}
