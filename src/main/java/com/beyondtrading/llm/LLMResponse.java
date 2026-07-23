package com.beyondtrading.llm;

public record LLMResponse(String action, String dialogue) {
    public boolean hasAction() {
        return action != null && !action.isBlank()
            && !action.equalsIgnoreCase("none")
            && !action.equalsIgnoreCase("null")
            && !action.equalsIgnoreCase("talk");
    }
}
