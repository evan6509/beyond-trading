package com.beyondtrading.npc;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class NPCSession {
    private final AINPCEntity npc;
    private final ServerPlayer player;
    private final List<String[]> history;
    private long lastActivity;

    public NPCSession(AINPCEntity npc, ServerPlayer player) {
        this.npc = npc;
        this.player = player;
        this.history = new ArrayList<>();
        this.lastActivity = System.currentTimeMillis();
    }

    public AINPCEntity getNpc() { return npc; }
    public ServerPlayer getPlayer() { return player; }
    public long getLastActivity() { return lastActivity; }

    public void touch() { this.lastActivity = System.currentTimeMillis(); }

    public void addTurn(String playerMsg, String npcResponse) {
        history.add(new String[]{playerMsg, npcResponse});
        touch();
    }

    public List<String[]> getHistory() {
        return List.copyOf(history);
    }

    public String formatHistory() {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nRecent conversation:\n");
        for (String[] turn : history) {
            sb.append("Player: ").append(turn[0]).append("\n");
            sb.append("You: ").append(turn[1]).append("\n");
        }
        return sb.toString();
    }
}
