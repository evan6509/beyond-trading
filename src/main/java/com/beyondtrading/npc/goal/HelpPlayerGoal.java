package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Relationship;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class HelpPlayerGoal extends NPCGoal {
    private Player target;
    private boolean helpAnnounced;

    public HelpPlayerGoal() {
        super(GoalType.HELP_PLAYER, 600);
    }

    @Override
    public void start(AINPCEntity npc) {
        target = npc.getRecentPlayerInteracted();
        if (target == null || !target.isAlive()) {
            target = npc.level().getNearestPlayer(npc, 15.0);
        }
        if (target != null) {
            npc.getNavigation().moveTo(target, 0.6);
        }
        helpAnnounced = false;
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (target != null && target.isAlive()) {
            if (npc.distanceToSqr(target) > 4.0) {
                npc.getNavigation().moveTo(target, 0.6);
            } else {
                npc.lookAt(target, 30.0F, 30.0F);
                if (!helpAnnounced) {
                    String outcome = "stood by " + target.getName().getString();
                    if (target instanceof ServerPlayer player) {
                        boolean needsFood = player.getFoodData().getFoodLevel() < 18
                            || player.getHealth() < player.getMaxHealth() * 0.75F;
                        ItemStack aid = needsFood ? takeFood(npc) : ItemStack.EMPTY;
                        if (!aid.isEmpty()) {
                            if (!player.getInventory().add(aid)) player.drop(aid, false);
                            outcome = "gave " + aid.getHoverName().getString()
                                + " to " + player.getScoreboardName();
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§e[" + npc.getNpcName() + " gave you "
                                    + aid.getHoverName().getString() + "]"));
                        } else if (nearbyHostiles(player)) {
                            npc.setFollowTarget(player);
                            outcome = "escorted " + player.getScoreboardName()
                                + " while danger was nearby";
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§e[" + npc.getNpcName() + " is staying with you while it is dangerous]")
                            );
                        } else {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "§e[" + npc.getNpcName() + " is here to help]")
                            );
                        }
                    }
                    int day = (int) (npc.level().getOverworldClockTime() / 24000);
                    npc.remember(day, "Helped " + target.getName().getString()
                        + ": " + outcome, 35);
                    String id = target.getUUID().toString();
                    Relationship relation = npc.getRelationship(id);
                    npc.addOrUpdateRelationship(relation == null
                        ? Relationship.withPlayer(id, target.getName().getString()).withChange(2)
                        : relation.withChange(1));
                    npc.getNeeds().onSocialInteraction();
                    npc.setLastActionOutcome(outcome);
                    npc.saveToDisk();
                    helpAnnounced = true;
                }
            }
            ticksRemaining--;
        } else {
            ticksRemaining -= 10;
        }
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }

    @Override
    public void interrupt(AINPCEntity npc) {
        npc.clearFollowTarget();
        super.interrupt(npc);
    }

    private ItemStack takeFood(AINPCEntity npc) {
        for (var item : java.util.List.of(
            Items.BREAD, Items.CARROT, Items.POTATO, Items.BEETROOT)) {
            ItemStack food = npc.getInventory().removeItemType(item, 1);
            if (!food.isEmpty()) return food;
        }
        return ItemStack.EMPTY;
    }

    private boolean nearbyHostiles(Player player) {
        return player.level() instanceof net.minecraft.server.level.ServerLevel level
            && !level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(10),
                entity -> entity.isAlive()).isEmpty();
    }
}
