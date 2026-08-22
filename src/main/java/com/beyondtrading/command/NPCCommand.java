package com.beyondtrading.command;

import com.beyondtrading.BeyondTradingMod;
import com.beyondtrading.config.ModConfig;
import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.entity.ModEntities;
import com.beyondtrading.llm.LLMInterface;
import com.beyondtrading.llm.ModelListFetcher;
import com.beyondtrading.llm.WorldContext;
import com.beyondtrading.npc.data.NPCData;
import com.beyondtrading.npc.data.Profession;
import com.beyondtrading.npc.schedule.VillageCoordinator;
import com.beyondtrading.npc.schedule.NPCScheduler;
import com.beyondtrading.npc.data.Relationship;
import com.beyondtrading.npc.behavior.NPCAction;
import com.google.gson.Gson;
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
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class NPCCommand {
    private static final Gson GSON = new Gson();
    private static final LLMInterface llm = new LLMInterface();
    private static final SuggestionProvider<CommandSourceStack> MODEL_SUGGESTIONS =
        (ctx, builder) -> {
            for (String model : ModelListFetcher.getCachedModels()) {
                builder.suggest(model);
            }
            return builder.buildFuture();
        };
    private static final SuggestionProvider<CommandSourceStack> ACTION_SUGGESTIONS =
        (ctx, builder) -> {
            for (NPCAction action : NPCAction.values()) builder.suggest(action.name());
            return builder.buildFuture();
        };
    private static final List<String> REASONING_EFFORTS =
        // OpenAI-style effort values are supported by some backends; Qwen
        // thinking models exposed through LM Studio commonly support the
        // simpler on/off pair instead.
        List.of("none", "off", "on", "minimal", "low", "medium", "high", "xhigh");
    private static final SuggestionProvider<CommandSourceStack> REASONING_SUGGESTIONS =
        (ctx, builder) -> {
            REASONING_EFFORTS.forEach(builder::suggest);
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
                        llm.ask(List.of(
                            Map.of("role", "system", "content",
                                WorldContext.getSystemPrompt() + context),
                            Map.of("role", "user", "content", message)
                        )).ifPresentOrElse(
                            response -> source.getServer().execute(() ->
                                source.sendSuccess(() ->
                                    Component.literal("§e[LLM] " + response), false)),
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
                        config.llm.baseUrl(), config.llm.apiKey(), name, config.llm.timeoutMs(),
                        config.llm.reasoningEffort());
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
            .then(literal("reasoning")
                .then(argument("effort", StringArgumentType.word())
                    .suggests(REASONING_SUGGESTIONS)
                    .executes(ctx -> {
                        String effort = StringArgumentType.getString(ctx, "effort")
                            .strip().toLowerCase(java.util.Locale.ROOT);
                        if (!REASONING_EFFORTS.contains(effort)) {
                            ctx.getSource().sendFailure(Component.literal(
                                "§cUse one of: " + String.join(", ", REASONING_EFFORTS)));
                            return 0;
                        }
                        var config = ModConfig.get();
                        config.llm = new com.beyondtrading.config.LLMConfig(
                            config.llm.baseUrl(), config.llm.apiKey(), config.llm.model(),
                            config.llm.timeoutMs(), effort);
                        config.save();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§aReasoning effort set to: §7" + effort), false);
                        return 1;
                    })))
        );

        dispatcher.register(literal("beyondtrading")
            .requires(source -> source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
            .then(literal("summon")
                .then(argument("pos", Vec3Argument.vec3())
                    .executes(ctx -> {
                        ServerLevel level = ctx.getSource().getLevel();
                        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                        return summonNPC(level, pos, "thomas", null);
                    })
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                            String name = StringArgumentType.getString(ctx, "name");
                            return summonNPC(level, pos, name, null);
                        })
                        .then(argument("profession", StringArgumentType.word())
                            .executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                String name = StringArgumentType.getString(ctx, "name");
                                String profession = StringArgumentType.getString(ctx, "profession");
                                return summonNPC(level, pos, name, profession);
                            }))))
                .executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Vec3 pos = ctx.getSource().getPosition();
                    return summonNPC(level, pos, "thomas", null);
                })
                .then(argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerLevel level = ctx.getSource().getLevel();
                        Vec3 pos = ctx.getSource().getPosition();
                        String name = StringArgumentType.getString(ctx, "name");
                        return summonNPC(level, pos, name, null);
                    })
                    .then(argument("profession", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            Vec3 pos = ctx.getSource().getPosition();
                            String name = StringArgumentType.getString(ctx, "name");
                            String profession = StringArgumentType.getString(ctx, "profession");
                            return summonNPC(level, pos, name, profession);
                        }))))
            .then(literal("inspect")
                .then(argument("target", EntityArgument.entity())
                    .executes(ctx -> {
                        Entity entity = EntityArgument.getEntity(ctx, "target");
                        return inspectNPC(ctx.getSource(), entity);
                    })))
            .then(literal("decide")
                .then(argument("target", EntityArgument.entity())
                    .executes(ctx -> {
                        Entity entity = EntityArgument.getEntity(ctx, "target");
                        if (!(entity instanceof AINPCEntity npc)) {
                            ctx.getSource().sendFailure(Component.literal("§cTarget is not an AI villager."));
                            return 0;
                        }
                        if (!npc.getNPCBrain().requestDecisionNow()) {
                            ctx.getSource().sendFailure(Component.literal(
                                "§cThe villager is paused or already deciding."));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "§7Requested an immediate LLM decision for " + npc.getNpcName()), false);
                        return 1;
                    })))
            .then(literal("action")
                .then(argument("target", EntityArgument.entity())
                    .then(argument("action", StringArgumentType.word())
                        .suggests(ACTION_SUGGESTIONS)
                        .executes(ctx -> {
                            Entity entity = EntityArgument.getEntity(ctx, "target");
                            if (!(entity instanceof AINPCEntity npc)) {
                                ctx.getSource().sendFailure(Component.literal(
                                    "§cTarget is not an AI villager."));
                                return 0;
                            }
                            String action = StringArgumentType.getString(ctx, "action");
                            if (!NPCAction.parse(action).isPresent()
                                || !npc.getNPCBrain().applyExternalAction(action)) {
                                ctx.getSource().sendFailure(Component.literal(
                                    "§cAction was invalid or could not be started."));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "§7Started " + action.toUpperCase() + " for " + npc.getNpcName()), false);
                            return 1;
                        }))))
            .then(literal("config")
                .executes(ctx -> {
                    var config = ModConfig.get().llm;
                    String endpoint = ModelListFetcher.getWorkingBaseUrl();
                    String detected = endpoint == null ? "none detected" : endpoint;
                    String models = ModelListFetcher.getCachedModels().isEmpty()
                        ? "none cached" : String.join(", ", ModelListFetcher.getCachedModels());
                    String status = "§6=== Beyond Trading Status ===§r\n"
                        + "§eConfigured backend: §7" + config.baseUrl() + "§r\n"
                        + "§eDetected backend: §7" + detected + "§r\n"
                        + "§eConfigured model: §7" + config.model() + "§r\n"
                        + "§eReasoning effort: §7" + config.reasoningEffort() + "§r\n"
                        + "§eCached models: §7" + models + "§r\n"
                        + "§eRequest timeout: §7" + config.timeoutMs() + " ms§r\n"
                        + "§eTracked agents: §7" + NPCScheduler.get().getTrackedCount() + "§r";
                    ctx.getSource().sendSuccess(() -> Component.literal(status), false);
                    return 1;
                }))
            .then(literal("verify")
                .executes(ctx -> verifyRuntime(ctx.getSource())))
        );
    }

    /**
     * Read-only lifecycle contract for the running server. This deliberately
     * does not summon, move, or alter an entity; it reports whether every
     * loaded vanilla villager has been adopted and has the durable agent
     * surfaces needed by the simulation.
     */
    private static int verifyRuntime(CommandSourceStack source) {
        int loadedVillagers = 0;
        int agents = 0;
        int unadopted = 0;
        int unhealthy = 0;
        int nonVanillaIdentity = 0;
        StringBuilder details = new StringBuilder();

        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Villager villager)) continue;
                loadedVillagers++;
                if (!(villager instanceof AINPCEntity npc)) {
                    unadopted++;
                    continue;
                }

                agents++;
                if (npc.getType() != ModEntities.AI_NPC) nonVanillaIdentity++;
                boolean healthy = npc.getNpcData() != null
                    && npc.getNPCBrain() != null
                    && !npc.getCurrentGoals().isEmpty()
                    && npc.getMemory() != null;
                if (!healthy) unhealthy++;
                if (agents <= 16) {
                    details.append("\n  - ").append(npc.getNpcName())
                        .append(" (").append(npc.getProfession().displayName())
                        .append(", decision ").append(npc.getLastAutonomousDecision())
                        .append(", current-session decision ")
                        .append(npc.hasDecisionThisSession() ? "observed" : "pending")
                        .append(", memories ").append(npc.getMemory().getEntries().size())
                        .append(", relationships ").append(npc.getRelationships().size())
                        .append(", goal ").append(npc.getLongTermGoal()).append(")");
                }
            }
        }

        boolean backend = ModelListFetcher.getWorkingBaseUrl() != null;
        boolean passed = agents > 0
            && loadedVillagers == agents
            && unadopted == 0
            && unhealthy == 0
            && nonVanillaIdentity == 0
            && NPCScheduler.get().getTrackedCount() >= agents
            && backend;
        String report = "§6=== Beyond Trading Runtime Verification ===§r\n"
            + "§eLoaded villagers: §7" + loadedVillagers + "§r\n"
            + "§eAI agents: §7" + agents + "§r\n"
            + "§eUnadopted vanilla villagers: §7" + unadopted + "§r\n"
            + "§eUnhealthy agent records: §7" + unhealthy + "§r\n"
            + "§eNon-vanilla network identities: §7" + nonVanillaIdentity + "§r\n"
            + "§eTracked brains: §7" + NPCScheduler.get().getTrackedCount() + "§r\n"
            + "§eLocal LLM backend: §7" + (backend ? "available" : "unavailable") + "§r"
            + details
            + "\n§eResult: " + (passed ? "§aPASS" : "§cCHECK REQUIRED") + "§r";
        if (passed) source.sendSuccess(() -> Component.literal(report), false);
        else source.sendFailure(Component.literal(report));
        return passed ? 1 : 0;
    }

    private static int summonNPC(ServerLevel level, Vec3 pos, String name, String professionOverride) {
        NPCData data = loadNPCData(name);
        if (data == null) {
            Profession prof = professionOverride != null ? Profession.byName(professionOverride) : Profession.NONE;
            data = NPCData.defaults(name);
            if (prof != Profession.NONE) {
                data = new NPCData(name, prof.name(), data.traits(), null, null, prof.availableGoals());
            }
            BeyondTradingMod.LOGGER.info("Creating NPC from defaults: {}", name);
        } else if (professionOverride != null) {
            Profession prof = Profession.byName(professionOverride);
            if (prof != Profession.NONE) {
                data = new NPCData(data.name(), prof.name(), data.traits(),
                    data.homeId(), data.workplaceId(), prof.availableGoals());
            }
        }

        AINPCEntity npc = new AINPCEntity(ModEntities.AI_NPC, level);
        npc.setPos(pos.x, pos.y, pos.z);
        npc.setHomePos(BlockPos.containing(pos));
        npc.setWorkplacePos(BlockPos.containing(pos));
        npc.setNpcData(data);
        level.addFreshEntity(npc);
        npc.getNPCBrain();
        npc.saveToDisk();
        BeyondTradingMod.LOGGER.info("Summoned NPC {} ({}) at {}", data.name(), data.profession(), pos);
        return 1;
    }

    private static int inspectNPC(CommandSourceStack source, Entity entity) {
        if (!(entity instanceof AINPCEntity npc)) {
            source.sendFailure(Component.literal("§cTarget is not an AI NPC."));
            return 0;
        }
        NPCData data = npc.getNpcData();
        if (data == null) {
            source.sendFailure(Component.literal("§cNPC has no data loaded."));
            return 0;
        }

        StringBuilder info = new StringBuilder();
        info.append("§6=== ").append(data.name()).append(" ===§r\n");
        info.append("§eProfession: §7").append(data.profession()).append("§r\n");
        info.append("§eTraits: §7").append(data.traits() == null
            ? "friendly" : String.join(", ", data.traits())).append("§r\n");
        if (data.homeId() != null) info.append("§eHome: §7").append(data.homeId()).append("§r\n");
        if (data.workplaceId() != null) info.append("§eWorkplace: §7").append(data.workplaceId()).append("§r\n");
        info.append("§eHome Pos: §7").append(npc.getHomePos() != null ? npc.getHomePos().toShortString() : "none").append("§r\n");
        info.append("§eWorkplace Pos: §7").append(npc.getWorkplacePos() != null ? npc.getWorkplacePos().toShortString() : "none").append("§r\n");
        info.append("§eGoals: §7").append(String.join(", ", npc.getCurrentGoals())).append("§r\n");
        info.append("§eRelationships: §7").append(npc.getRelationships().size()).append("§r\n");
        npc.getRelationships().stream().limit(6).forEach(relation ->
            info.append("  §7- ").append(relation.targetName()).append(" (")
                .append(relation.type()).append(", familiarity ")
                .append(relation.familiarity()).append("/100)§r\n"));
        var memories = npc.getMemory() == null ? List.<com.beyondtrading.npc.data.MemoryEntry>of()
            : npc.getMemory().getEntries();
        info.append("§eRecent Memories: §7").append(memories.size()).append("§r\n");
        memories.stream().skip(Math.max(0, memories.size() - 5)).forEach(memory ->
            info.append("  §7- Day ").append(memory.day()).append(": ")
                .append(memory.event()).append("§r\n"));
        var goal = npc.getCurrentGoal();
        info.append("§eCurrent Goal: §7").append(goal != null ? goal.getStatus() : "none").append("§r\n");
        info.append("§eLast LLM Decision: §7").append(npc.getLastAutonomousDecision()).append("§r\n");
        info.append("§eDecision This Server Session: §7")
            .append(npc.hasDecisionThisSession() ? "yes" : "pending").append("§r\n");
        info.append("§eAction Outcome: §7").append(npc.getLastActionOutcome()).append("§r\n");
        info.append("§eNeeds: §7").append(npc.getNeeds().formatForPrompt()).append("§r\n");
        info.append("§eLong-term Goal: §7").append(npc.getLongTermGoal())
            .append(" (" + npc.getLongTermGoalProgress() + "/100)§r\n");
        info.append("§eVillage: §7").append(VillageCoordinator.snapshot(npc).formatForPrompt()).append("§r\n");
        info.append("§eIn Conversation: §7").append(npc.isInConversation() ? "yes" : "no").append("§r\n");
        info.append("§eUUID: §7").append(npc.getUUID()).append("§r");

        source.sendSuccess(() -> Component.literal(info.toString()), false);
        return 1;
    }

    private static NPCData loadNPCData(String name) {
        String path = "data/beyondtrading/npcs/" + name.toLowerCase() + ".json";
        try (InputStream is = NPCCommand.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            NPCData data = GSON.fromJson(json, NPCData.class);
            if (data == null || data.name() == null) return null;
            return data;
        } catch (IOException e) {
            return null;
        }
    }
}
