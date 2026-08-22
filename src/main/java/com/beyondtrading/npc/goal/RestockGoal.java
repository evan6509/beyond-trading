package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class RestockGoal extends NPCGoal {
    private boolean arrived;
    private boolean performed;
    private int approachTicks;
    private BlockPos workplace;

    public RestockGoal() {
        super(GoalType.RESTOCK_MATERIALS, 300);
    }

    @Override
    public void start(AINPCEntity npc) {
        npc.clearFollowTarget();
        workplace = npc.getWorkplacePos();
        if (workplace != null) {
            npc.getNavigation().moveTo(workplace.getX(), workplace.getY(), workplace.getZ(), 0.6);
            arrived = false;
        } else {
            arrived = true;
        }
        approachTicks = 0;
        performed = false;
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (!arrived) {
            approachTicks++;
            if (npc.distanceToSqr(workplace.getX(), workplace.getY(), workplace.getZ()) <= 9.0) {
                arrived = true;
            } else if (npc.getNavigation().isDone() && approachTicks > 20) {
                npc.setLastActionOutcome("could not reach the workplace to restock");
                ticksRemaining = 0;
                return;
            }
        }
        if (arrived && !performed) {
            boolean restocked = restockFromNearbyContainer(npc);
            if (!restocked) {
                npc.setLastActionOutcome("checked for supplies; no accessible materials found");
            } else if (npc.getNeeds().hungerIsCritical() && hasFood(npc)) {
                // Let the brain's emergency policy consume the newly found
                // food on the next server tick instead of making the hungry
                // villager wait out the full restock duration.
                npc.setLastActionOutcome("restocked food; ready to eat");
                ticksRemaining = 0;
            }
            performed = true;
        }
        ticksRemaining--;
    }

    private boolean restockFromNearbyContainer(AINPCEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos center = npc.getWorkplacePos() != null
            ? npc.getWorkplacePos() : npc.blockPosition();
        // First seek materials that advance this profession; only then accept
        // general supplies so a shortage does not fill the villager's limited
        // inventory with irrelevant items.
        for (int preferredPass = 0; preferredPass < 2; preferredPass++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (!(level.getBlockEntity(center.offset(dx, dy, dz)) instanceof Container container)) {
                            continue;
                        }
                            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                                ItemStack stack = container.getItem(slot);
                            if (stack.isEmpty() || (preferredPass == 0 && !isProfessionMaterial(npc, stack))) {
                                continue;
                            }
                            int amount = Math.min(16, stack.getCount());
                            ItemStack transfer = stack.copy();
                            transfer.setCount(amount);
                            if (!npc.getInventory().canAddItem(transfer)) continue;
                            ItemStack taken = container.removeItem(slot, amount);
                            npc.getInventory().addItem(taken);
                            npc.setLastActionOutcome("restocked " + taken.getHoverName().getString());
                            npc.advanceLongTermGoal(1);
                            int day = (int) (level.getOverworldClockTime() / 24000);
                            npc.remember(day, "Restocked " + taken.getHoverName().getString(), 25);
                            return true;
                        }
                    }
                }
            }
        }
        // Villagers can also recover from a shortage when useful supplies are
        // already on the ground. Keep this bounded to the workplace area and
        // only pick up profession-relevant items or food.
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(8), entity -> entity.isAlive())) {
            ItemStack stack = item.getItem();
            if (!isProfessionMaterial(npc, stack)) continue;
            int amount = Math.min(16, stack.getCount());
            ItemStack transfer = stack.copy();
            transfer.setCount(amount);
            if (!npc.getInventory().canAddItem(transfer)) continue;
            stack.shrink(amount);
            if (stack.isEmpty()) item.discard();
            npc.getInventory().addItem(transfer);
            npc.setLastActionOutcome("picked up " + transfer.getHoverName().getString()
                + " to restock");
            npc.advanceLongTermGoal(1);
            int day = (int) (level.getOverworldClockTime() / 24000);
            npc.remember(day, "Picked up " + transfer.getHoverName().getString()
                + " from the ground", 25);
            return true;
        }
        return false;
    }

    private boolean isProfessionMaterial(AINPCEntity npc, ItemStack stack) {
        if (npc.getNeeds().hunger() < 35 && isFood(stack)) return true;
        Profession profession = npc.getProfession();
        return switch (profession) {
            case FARMER -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.CARROT)
                || stack.is(Items.POTATO) || stack.is(Items.BEETROOT);
            case BLACKSMITH -> stack.is(Items.IRON_INGOT) || stack.is(Items.COAL);
            case LIBRARIAN -> stack.is(Items.PAPER) || stack.is(Items.LEATHER)
                || stack.is(Items.BOOK);
            case MERCHANT -> stack.is(Items.BREAD) || stack.is(Items.WHEAT)
                || stack.is(Items.EMERALD);
            case EXPLORER -> stack.is(Items.COMPASS) || stack.is(Items.MAP)
                || stack.is(Items.BREAD);
            default -> true;
        };
    }

    private boolean isFood(ItemStack stack) {
        return stack.is(Items.BREAD) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
            || stack.is(Items.BEETROOT) || stack.is(Items.WHEAT);
    }

    private boolean hasFood(AINPCEntity npc) {
        return npc.getInventory().getItems().stream().anyMatch(this::isFood);
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }
}
