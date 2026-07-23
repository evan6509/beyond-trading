package com.beyondtrading.command;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.entity.ModEntities;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.ModelListFetcher;
import com.beyondtrading.llm.WorldContext;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class NPCCommand {
    private static final LLMInterface llm = new LLMInterface();
    private static final SuggestionProvider<CommandSourceStack> MODEL_SUGGESTIONS =
        (ctx, builder) -> {
            for (String model : ModelListFetcher.getCachedModels()) {
                builder.suggest(model);
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("ask")
            .then(argument("message", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String message = StringArgumentType.getString(ctx, "message");
                    var source = ctx.getSource();
                    source.sendSuccess(() -> Component.literal("§7[You] " + message), false);

                    ServerPlayer player = source.getPlayer();
                    String context = player != null
                        ? "\n\n" + WorldContext.build(player)
                        : "";

                    CompletableFuture.runAsync(() -> {
                        var messages = List.of(
                            Map.of("role", "system", "content",
                                WorldContext.getSystemPrompt() + context),
                            Map.of("role", "user", "content", message)
                        );
                        llm.chat(messages).ifPresentOrElse(
                            response -> source.getServer().execute(() ->
                                source.sendSuccess(() ->
                                    Component.literal("§e[LLM] " + response.content()), false)),
                            () -> source.getServer().execute(() ->
                                source.sendFailure(Component.literal("§cLLM request failed")))
                        );
                    });

                    return 1;
                }))
        );

        dispatcher.register(literal("model")
            .executes(ctx -> {
                var config = ModConfig.get().llm;
                ctx.getSource().sendSuccess(() ->
                    Component.literal("§eCurrent model: §7" + config.model()), false);
                return 1;
            })
            .then(argument("name", StringArgumentType.greedyString())
                .suggests(MODEL_SUGGESTIONS)
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name").strip();
                    if (name.isBlank()) {
                        ctx.getSource().sendFailure(Component.literal("§cModel name cannot be empty"));
                        return 0;
                    }
                    var config = ModConfig.get();
                    config.llm = new com.beyondtrading.config.LLMConfig(
                        config.llm.baseUrl(), config.llm.apiKey(), name, config.llm.timeoutMs());
                    config.save();
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("§aModel set to: §7" + name), false);
                    return 1;
                }))
            .then(literal("refresh")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("§7Fetching model list..."), false);
                    CompletableFuture.runAsync(() -> {
                        ModelListFetcher.refresh();
                        var models = ModelListFetcher.getCachedModels();
                        ctx.getSource().getServer().execute(() -> {
                            if (models.isEmpty()) {
                                ctx.getSource().sendFailure(
                                    Component.literal("§cNo models found or server unreachable"));
                            } else {
                                ctx.getSource().sendSuccess(() ->
                                    Component.literal("§aFound " + models.size() + " model(s): §7"
                                        + String.join(", ", models)), false);
                            }
                        });
                    });
                    return 1;
                }))
        );

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
        );
    }

    private static int summonNPC(ServerLevel level, Vec3 pos) {
        AINPCEntity npc = new AINPCEntity(ModEntities.AI_NPC, level);
        npc.setPos(pos.x, pos.y, pos.z);
        npc.setHomePos(BlockPos.containing(pos));
        level.addFreshEntity(npc);
        BeyondTradingMod.LOGGER.info("Summoned NPC Thomas at {}", pos);
        return 1;
    }

    private static int inspectNPC(CommandSourceStack source, Entity entity) {
        if (!(entity instanceof AINPCEntity npc)) {
            source.sendFailure(Component.literal("§cTarget is not an AI NPC."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "§6=== " + npc.getNpcName() + " ===§r\n"
            + "§eHome: §7" + (npc.getHomePos() != null ? npc.getHomePos().toShortString() : "none")),
            false);
        return 1;
    }
}
