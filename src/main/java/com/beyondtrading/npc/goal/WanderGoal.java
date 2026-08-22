package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WanderGoal extends NPCGoal {
    private static final int MAX_WAYPOINTS = 4;
    private static final int RADIUS = 20;
    private final List<BlockPos> waypoints;
    private int currentWaypoint;
    private int waypointTicks;
    private boolean reported;

    public WanderGoal() {
        super(GoalType.WANDER, 1200);
        this.waypoints = new ArrayList<>();
        this.currentWaypoint = 0;
        this.reported = false;
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        generateWaypoints(npc);
        navigateToNext(npc);
    }

    private void generateWaypoints(AINPCEntity npc) {
        waypoints.clear();
        var rand = new Random();
        currentWaypoint = 0;
        reported = false;
        for (int i = 0; i < MAX_WAYPOINTS; i++) {
            int x = (int) npc.getX() + rand.nextInt(RADIUS * 2 + 1) - RADIUS;
            int z = (int) npc.getZ() + rand.nextInt(RADIUS * 2 + 1) - RADIUS;
            int y = npc.level().getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                new BlockPos(x, 0, z)).getY();
            waypoints.add(new BlockPos(x, y, z));
        }
    }

    private void navigateToNext(AINPCEntity npc) {
        if (currentWaypoint < waypoints.size()) {
            var target = waypoints.get(currentWaypoint);
            npc.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.6);
            waypointTicks = 0;
        }
    }

    @Override
    public void tick(AINPCEntity npc) {
        ticksRemaining--;
        waypointTicks++;
        if (currentWaypoint < waypoints.size()
            && (npc.distanceToSqr(waypoints.get(currentWaypoint).getX(),
                waypoints.get(currentWaypoint).getY(), waypoints.get(currentWaypoint).getZ()) <= 16.0
                || (npc.getNavigation().isDone() && waypointTicks > 40))) {
            currentWaypoint++;
            navigateToNext(npc);
        }
        if (!reported && currentWaypoint >= waypoints.size()) {
            int day = (int) (npc.level().getOverworldClockTime() / 24000);
            npc.remember(day, "Wandered through the village and nearby surroundings", 15);
            npc.advanceLongTermGoal(1);
            npc.setLastActionOutcome("explored nearby surroundings");
            reported = true;
        }
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return currentWaypoint >= MAX_WAYPOINTS || ticksRemaining <= 0;
    }
}
