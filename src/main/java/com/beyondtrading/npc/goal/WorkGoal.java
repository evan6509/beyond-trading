package com.beyondtrading.npc.goal;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.schedule.NPCScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public class WorkGoal extends NPCGoal {
    private boolean arrived;
    private int actionCooldown;
    private int approachTicks;
    private BlockPos workplace;

    public WorkGoal(int durationTicks) {
        this(GoalType.WORK, durationTicks);
    }

    public WorkGoal(GoalType type, int durationTicks) {
        super(type, durationTicks);
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
        actionCooldown = 0;
    }

    @Override
    public void tick(AINPCEntity npc) {
        if (!arrived) {
            approachTicks++;
            if (npc.distanceToSqr(workplace.getX(), workplace.getY(), workplace.getZ()) <= 9.0) {
                arrived = true;
            } else if (npc.getNavigation().isDone() && approachTicks > 20) {
                npc.setLastActionOutcome("could not reach the workplace");
                ticksRemaining = 0;
                return;
            }
        }
        if (arrived && actionCooldown-- <= 0) {
            performWork(npc);
            npc.getNeeds().onWorkTick();
            actionCooldown = 40;
        }
        ticksRemaining--;
    }

    /** Perform a real profession action when this villager is a farmer. */
    private void performWork(AINPCEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return;

        switch (npc.getProfession()) {
            case FARMER -> farm(npc, level);
            case MERCHANT -> trade(npc, level);
            case BLACKSMITH -> repair(npc, level);
            case LIBRARIAN -> craftBook(npc, level);
            case EXPLORER -> gather(npc, level);
            default -> npc.setLastActionOutcome("worked; no profession action available");
        }
    }

    private void farm(AINPCEntity npc, ServerLevel level) {
        BlockPos center = npc.getWorkplacePos() != null
            ? npc.getWorkplacePos() : npc.blockPosition();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos cropPos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(cropPos);
                    if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
                        continue;
                    }

                    ItemStack produce = cropProduce(state);
                    ItemStack seeds = state.is(Blocks.WHEAT)
                        ? new ItemStack(Items.WHEAT_SEEDS, 1) : ItemStack.EMPTY;
                    if (!npc.getInventory().canAddItem(produce)
                        || (!seeds.isEmpty() && !npc.getInventory().canAddItem(seeds))) {
                        npc.setLastActionOutcome("found mature crops but inventory was full");
                        return;
                    }
                    if (level.destroyBlock(cropPos, false, npc, 0)) {
                        level.setBlock(cropPos, crop.getStateForAge(0), 3);
                        npc.getInventory().addItem(produce);
                        if (!seeds.isEmpty()) {
                            npc.getInventory().addItem(seeds);
                        }
                        int stored = depositFarmGoods(npc, level, center);
                        int day = (int) (level.getOverworldClockTime() / 24000);
                        npc.remember(day, "Harvested and replanted crops at the farm", 35);
                        npc.advanceLongTermGoal(3);
                        npc.setLastActionOutcome(stored > 0
                            ? "harvested crops and stored " + stored + " item(s) in the village pantry"
                            : "harvested and replanted crops");
                        return;
                    }
                }
            }
        }

        // A field can be empty when the agent first arrives. Planting from
        // carried seed/produce makes farming a productive bounded action,
        // rather than a read-only check for a pre-existing crop.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos farmland = center.offset(dx, dy, dz);
                    if (!level.getBlockState(farmland).is(Blocks.FARMLAND)) continue;
                    BlockPos plantPos = farmland.above();
                    if (!level.getBlockState(plantPos).isAir()) continue;

                    ItemStack seed = findPlantingItem(npc);
                    if (seed.isEmpty()) {
                        npc.setLastActionOutcome("found empty farmland but had no seeds to plant");
                        return;
                    }
                    level.setBlock(plantPos, plantingBlock(seed).defaultBlockState(), 3);
                    npc.getInventory().removeItemType(seed.getItem(), 1);
                    int day = (int) (level.getOverworldClockTime() / 24000);
                    npc.remember(day, "Planted crops at the farm", 30);
                    npc.advanceLongTermGoal(2);
                    npc.setLastActionOutcome("planted crops at the farm");
                    return;
                }
            }
        }
        npc.setLastActionOutcome("worked at the farm; no mature crops or empty plots found");
    }

    /** Move farm output into a nearby container so other residents can use it. */
    private int depositFarmGoods(AINPCEntity npc, ServerLevel level, BlockPos center) {
        Container pantry = findNearbyContainer(level, center, 6);
        if (pantry == null) return 0;

        int moved = 0;
        for (var item : new net.minecraft.world.item.Item[] {
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT
        }) {
            int available = npc.getInventory().countItem(item);
            if (available <= 0) continue;
            ItemStack transfer = npc.getInventory().removeItemType(item, available);
            int before = transfer.getCount();
            ItemStack leftover = insertIntoContainer(pantry, transfer);
            moved += before - leftover.getCount();
            if (!leftover.isEmpty()) npc.getInventory().addItem(leftover);
        }
        return moved;
    }

    private ItemStack insertIntoContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                container.setItem(slot, remaining.copy());
                remaining.setCount(0);
                break;
            }
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) continue;
            int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize())
                - existing.getCount();
            if (room <= 0) continue;
            int moved = Math.min(room, remaining.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
            container.setItem(slot, existing);
        }
        container.setChanged();
        return remaining;
    }

    private Container findNearbyContainer(ServerLevel level, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (level.getBlockEntity(center.offset(dx, dy, dz)) instanceof Container container) {
                        return container;
                    }
                }
            }
        }
        return null;
    }

    private ItemStack findPlantingItem(AINPCEntity npc) {
        for (net.minecraft.world.item.Item candidate : new net.minecraft.world.item.Item[] {
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT
        }) {
            if (npc.getInventory().countItem(candidate) > 0) {
                return new ItemStack(candidate, 1);
            }
        }
        return ItemStack.EMPTY;
    }

    private net.minecraft.world.level.block.Block plantingBlock(ItemStack seed) {
        if (seed.is(Items.CARROT)) return Blocks.CARROTS;
        if (seed.is(Items.POTATO)) return Blocks.POTATOES;
        if (seed.is(Items.BEETROOT)) return Blocks.BEETROOTS;
        return Blocks.WHEAT;
    }

    private ItemStack cropProduce(BlockState state) {
        if (state.is(Blocks.CARROTS)) return new ItemStack(Items.CARROT, 1);
        if (state.is(Blocks.POTATOES)) return new ItemStack(Items.POTATO, 1);
        if (state.is(Blocks.BEETROOTS)) return new ItemStack(Items.BEETROOT, 1);
        return new ItemStack(Items.WHEAT, 1);
    }

    private void trade(AINPCEntity npc, ServerLevel level) {
        makeBreadFromWheat(npc);
        ServerPlayer player = nearbyPlayer(npc, level);
        if (player == null) {
            npc.setLastActionOutcome("waited at the market for a customer");
            return;
        }
        if (player.getInventory().countItem(Items.EMERALD) <= 0) {
            npc.setLastActionOutcome("offered a trade; customer had no emeralds");
            return;
        }
        ItemStack payment = new ItemStack(Items.EMERALD, 1);
        ItemStack goods = new ItemStack(Items.BREAD, 2);
        if (npc.getInventory().countItem(Items.BREAD) < goods.getCount()) {
            npc.setLastActionOutcome("waited at the market; bread stock was empty");
            return;
        }
        if (!npc.getInventory().canAddItem(payment)) {
            npc.setLastActionOutcome("waited at the market; payment storage was full");
            return;
        }

        player.getInventory().removeItem(payment);
        npc.getInventory().removeItemType(Items.BREAD, goods.getCount());
        npc.getInventory().addItem(payment);
        if (!player.getInventory().add(goods)) player.drop(goods, false);
        recordWork(npc, "Traded an emerald for bread with " + player.getScoreboardName(), player);
        npc.setLastActionOutcome("traded bread with " + player.getScoreboardName());
    }

    /** Turn locally produced wheat into market stock before serving a customer. */
    private void makeBreadFromWheat(AINPCEntity npc) {
        int breadNeeded = Math.max(0, 2 - npc.getInventory().countItem(Items.BREAD));
        int wheatAvailable = npc.getInventory().countItem(Items.WHEAT);
        int loaves = Math.min(breadNeeded, wheatAvailable / 3);
        if (loaves <= 0) return;

        npc.getInventory().removeItemType(Items.WHEAT, loaves * 3);
        npc.getInventory().addItem(new ItemStack(Items.BREAD, loaves));
        recordWork(npc, "Baked " + loaves + " bread from village wheat");
        npc.setLastActionOutcome("baked bread from wheat for the market");
    }

    private void repair(AINPCEntity npc, ServerLevel level) {
        ServerPlayer player = nearbyPlayer(npc, level);
        if (player == null) {
            repairOwnTool(npc);
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!held.isDamageableItem() || held.getDamageValue() <= 0
            || player.getInventory().countItem(Items.IRON_INGOT) <= 0) {
            npc.setLastActionOutcome("checked the forge; no repair was needed");
            return;
        }

        player.getInventory().removeItem(new ItemStack(Items.IRON_INGOT, 1));
        held.setDamageValue(Math.max(0, held.getDamageValue() - 100));
        recordWork(npc, "Repaired " + held.getHoverName().getString()
            + " for " + player.getScoreboardName(), player);
        npc.setLastActionOutcome("repaired the player's item");
    }

    private void craftBook(AINPCEntity npc, ServerLevel level) {
        ServerPlayer player = nearbyPlayer(npc, level);
        if (player == null) {
            craftOwnBook(npc);
            return;
        }
        if (player.getInventory().countItem(Items.PAPER) < 3
            || player.getInventory().countItem(Items.LEATHER) < 1) {
            npc.setLastActionOutcome("offered bookmaking; materials were missing");
            return;
        }

        player.getInventory().removeItem(new ItemStack(Items.PAPER, 3));
        player.getInventory().removeItem(new ItemStack(Items.LEATHER, 1));
        ItemStack book = new ItemStack(Items.BOOK, 1);
        if (!player.getInventory().add(book)) player.drop(book, false);
        recordWork(npc, "Crafted a book for " + player.getScoreboardName(), player);
        npc.setLastActionOutcome("crafted a book");
    }

    private void repairOwnTool(AINPCEntity npc) {
        for (ItemStack tool : npc.getInventory().getItems()) {
            if (!tool.isDamageableItem() || tool.getDamageValue() <= 0) continue;
            ItemStack ingot = npc.getInventory().removeItemType(Items.IRON_INGOT, 1);
            if (ingot.isEmpty()) {
                npc.setLastActionOutcome("found a damaged tool but had no iron");
                return;
            }
            tool.setDamageValue(Math.max(0, tool.getDamageValue() - 100));
            recordWork(npc, "Repaired own " + tool.getHoverName().getString());
            npc.setLastActionOutcome("repaired own equipment");
            return;
        }
        npc.setLastActionOutcome("worked at the forge; no damaged tool needed repair");
    }

    private void craftOwnBook(AINPCEntity npc) {
        ItemStack book = new ItemStack(Items.BOOK, 1);
        if (npc.getInventory().countItem(Items.PAPER) < 3
            || npc.getInventory().countItem(Items.LEATHER) < 1) {
            npc.setLastActionOutcome("readied the library; materials were missing");
            return;
        }
        if (!npc.getInventory().canAddItem(book)) {
            npc.setLastActionOutcome("had bookmaking materials but inventory was full");
            return;
        }
        npc.getInventory().removeItemType(Items.PAPER, 3);
        npc.getInventory().removeItemType(Items.LEATHER, 1);
        npc.getInventory().addItem(book);
        recordWork(npc, "Crafted a book from library materials");
        npc.setLastActionOutcome("crafted a book from own materials");
    }

    private void gather(AINPCEntity npc, ServerLevel level) {
        ItemEntity item = level.getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(8), entity -> entity.isAlive())
            .stream().findFirst().orElse(null);
        if (item == null) {
            npc.setLastActionOutcome("searched nearby ground; found nothing to gather");
            return;
        }

        ItemStack gathered = item.getItem().copy();
        gathered.setCount(Math.min(16, gathered.getCount()));
        if (!npc.getInventory().canAddItem(gathered)) {
            npc.setLastActionOutcome("found " + gathered.getHoverName().getString()
                + " but inventory was full");
            return;
        }
        item.getItem().shrink(gathered.getCount());
        if (item.getItem().isEmpty()) item.discard();
        npc.getInventory().addItem(gathered);
        recordWork(npc, "Gathered " + gathered.getHoverName().getString());
        npc.setLastActionOutcome("gathered " + gathered.getHoverName().getString());
    }

    private ServerPlayer nearbyPlayer(AINPCEntity npc, ServerLevel level) {
        // Profession actions that consume or modify player resources require
        // an actual recent interaction. A passerby near a workstation must
        // never be charged, repaired, or given materials accidentally.
        if (npc.isTrading()) return null;
        Player player = npc.getLastPlayerInteracted();
        if (player instanceof ServerPlayer serverPlayer
            && npc.hasRecentPlayerInteraction()
            && serverPlayer.level() == npc.level()
            && npc.distanceToSqr(serverPlayer) <= 64) {
            return serverPlayer;
        }
        return null;
    }

    private void recordWork(AINPCEntity npc, String event) {
        int day = (int) (npc.level().getOverworldClockTime() / 24000);
        npc.remember(day, event, 35);
        NPCScheduler.get().broadcast(npc, event);
        npc.advanceLongTermGoal(3);
    }

    private void recordWork(AINPCEntity npc, String event, ServerPlayer player) {
        recordWork(npc, event);
        String id = player.getUUID().toString();
        Relationship relation = npc.getRelationship(id);
        npc.addOrUpdateRelationship(relation == null
            ? Relationship.withPlayer(id, player.getScoreboardName()).withChange(2)
            : relation.withChange(1));
        npc.getNeeds().onSocialInteraction();
    }

    @Override
    public boolean isComplete(AINPCEntity npc) {
        return ticksRemaining <= 0;
    }
}
