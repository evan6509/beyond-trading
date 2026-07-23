package com.beyondtrading.command;

import com.beyondtrading.config.ModConfig;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.ModelListFetcher;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

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

                    CompletableFuture.runAsync(() -> {
                        var messages = List.of(
                            Map.of("role", "system", "content", "You are a helpful assistant. Reply concisely."),
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
    }
}
