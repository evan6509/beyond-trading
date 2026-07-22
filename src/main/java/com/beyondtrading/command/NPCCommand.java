package com.beyondtrading.command;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.entity.ModEntities;
import com.beyondtrading.npc.ai.NPCManager;
import com.beyondtrading.npc.data.MemoryEntry;
import com.beyondtrading.npc.data.NPCData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

public class NPCCommand {

    public static void register(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(literal("beyondtrading")
            .requires(source -> source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))

            .then(literal("summon")
                .then(argument("pos", Vec3Argument.vec3())
                    .executes(ctx -> {
                        ServerLevel level = ctx.getSource().getLevel();
                        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                        return summonNPC(level, pos);
                    }))
                .executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Vec3 pos = ctx.getSource().getPosition();
                    return summonNPC(level, pos);
                }))

            .then(literal("inspect")
                .then(argument("target", EntityArgument.entity())
                    .executes(ctx -> {
                        Entity entity = EntityArgument.getEntity(ctx, "target");
                        return inspectNPC(ctx.getSource(), entity);
                    })))

            .then(literal("config")
                .executes(ctx -> {
                    var config = ModConfig.get();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§e[Beyond Trading Config]§r\n" +
                        "  LLM URL: §7" + config.llm.baseUrl() + "§r\n" +
                        "  Model: §7" + config.llm.model() + "§r\n" +
                        "  Timeout: §7" + config.llm.timeoutMs() + "ms§r"
                    ), false);
                    return 1;
                }))
        );
    }

    private static int summonNPC(ServerLevel level, Vec3 pos) {
        AINPCEntity npc = new AINPCEntity(ModEntities.AI_NPC, level);
        npc.setPos(pos.x, pos.y, pos.z);
        npc.setHomePos(net.minecraft.core.BlockPos.containing(pos));
        level.addFreshEntity(npc);

        NPCManager.get(level.getServer()).initializeNPC(npc.getUUID(), "thomas", npc);

        BeyondTradingMod.LOGGER.info("Summoned AI NPC Thomas at {}", pos);
        return 1;
    }

    private static int inspectNPC(CommandSourceStack source, Entity entity) {
        if (!(entity instanceof AINPCEntity npc)) {
            source.sendFailure(Component.literal("§cTarget is not an AI NPC."));
            return 0;
        }

        NPCManager npcManager = NPCManager.get(source.getServer());
        NPCData data = npcManager.getNPCData(entity.getUUID());
        List<MemoryEntry> memories = npcManager.getMemories(entity.getUUID());

        if (data == null) {
            source.sendFailure(Component.literal("§cNPC data not loaded."));
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§6=== ").append(data.name).append(" ===§r\n");
        sb.append("§ePersonality: §7").append(String.join(", ", data.personality)).append("§r\n");
        sb.append("§eGoals: §7").append(String.join(", ", data.goals)).append("§r\n");
        sb.append("§eNeeds: §7").append(String.join(", ", data.needs)).append("§r\n");

        if (!memories.isEmpty()) {
            sb.append("§eMemories:§r\n");
            for (MemoryEntry m : memories) {
                sb.append("  §7Day ").append(m.day).append(": ");
                sb.append(m.event).append(" (");
                sb.append(m.importance).append("/100)§r\n");
            }
        } else {
            sb.append("§eMemories: §7None yet§r\n");
        }

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
