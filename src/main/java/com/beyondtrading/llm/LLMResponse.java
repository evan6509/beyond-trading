package com.beyondtrading.llm;

public record LLMResponse(String action, String dialogue, String goal) {
    public LLMResponse(String action, String dialogue) {
        this(action, dialogue, null);
    }

    public boolean hasAction() {
        return action != null && !action.isBlank()
            && !action.equalsIgnoreCase("none")
            && !action.equalsIgnoreCase("null")
            && !action.equalsIgnoreCase("talk");
    }
}
