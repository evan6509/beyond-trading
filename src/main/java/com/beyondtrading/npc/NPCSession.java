package com.beyondtrading.npc;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.NPCMemory;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class NPCSession {
    private static final int MAX_HISTORY_TURNS = 12;
    private static final int MAX_HISTORY_CHARS = 6_000;
    private static final int MAX_MESSAGE_CHARS = 1_200;
    private final AINPCEntity npc;
    private final ServerPlayer player;
    private final NPCMemory memory;
    private final List<String[]> history;
    private volatile long lastActivity;
    private boolean responseInFlight;

    public NPCSession(AINPCEntity npc, ServerPlayer player) {
        this.npc = npc;
        this.player = player;
        this.memory = npc.getMemory();
        this.history = new ArrayList<>();
        this.lastActivity = System.currentTimeMillis();
        this.responseInFlight = false;
    }

    public AINPCEntity getNpc() { return npc; }
    public ServerPlayer getPlayer() { return player; }
    public NPCMemory getMemory() { return memory; }
    public long getLastActivity() { return lastActivity; }
    public synchronized void touch() { this.lastActivity = System.currentTimeMillis(); }

    public synchronized boolean beginResponse() {
        if (responseInFlight) return false;
        responseInFlight = true;
        touch();
        return true;
    }

    public synchronized void endResponse() {
        responseInFlight = false;
        touch();
    }

    public synchronized boolean isResponseInFlight() { return responseInFlight; }

    public synchronized void addTurn(String playerMsg, String npcResponse) {
        history.add(new String[]{clip(playerMsg), clip(npcResponse)});
        if (history.size() > MAX_HISTORY_TURNS) {
            history.remove(0);
        }
        touch();
    }

    public synchronized String formatHistory() {
        if (history.isEmpty()) return "";
        List<String> selected = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            String[] turn = history.get(i);
            StringBuilder turnText = new StringBuilder();
            if (turn[0] != null && !turn[0].isBlank()) {
                turnText.append("Player: ").append(turn[0]).append("\n");
            }
            turnText.append("You: ").append(turn[1]).append("\n");
            if (chars + turnText.length() > MAX_HISTORY_CHARS && !selected.isEmpty()) break;
            selected.add(turnText.toString());
            chars += turnText.length();
        }
        java.util.Collections.reverse(selected);
        StringBuilder sb = new StringBuilder("Conversation history:\n");
        if (selected.size() < history.size()) sb.append("[earlier turns omitted]\n");
        selected.forEach(sb::append);
        return sb.toString();
    }

    private static String clip(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_MESSAGE_CHARS
            ? compact : compact.substring(0, MAX_MESSAGE_CHARS) + "…";
    }
}
