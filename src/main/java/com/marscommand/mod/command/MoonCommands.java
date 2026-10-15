package com.marscommand.mod.command;

import com.marscommand.mod.mission.MissionRules;
import com.marscommand.mod.mission.MoonDirector;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class MoonCommands {
    private MoonCommands() {}

    @FunctionalInterface
    private interface Action {
        int run(CommandContext<CommandSourceStack> context, MoonDirector director) throws CommandSyntaxException;
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("marscommand")
                .executes(context -> execute(context, MoonCommands::status))
                .then(Commands.literal("status").executes(context -> execute(context, MoonCommands::status)))
                .then(Commands.literal("missions").executes(context -> execute(context, (ctx, director) -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    director.openJournal(player, 0);
                    return 1;
                })))
                .then(Commands.literal("mission").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> execute(context, (ctx, director) -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            director.openJournal(player, LongArgumentType.getLong(ctx, "id"));
                            return 1;
                        }))))
                .then(Commands.literal("submit").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> execute(context, (ctx, director) -> {
                            int count = director.submit(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"));
                            return count;
                        }))))
                .then(Commands.literal("refuse").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> execute(context, (ctx, director) -> {
                            director.refuse(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"), false);
                            director.openJournal(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"));
                            return 1;
                        }))
                        .then(Commands.literal("confirm").executes(context -> execute(context, (ctx, director) ->
                                director.refuse(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"), true) ? 1 : 0)))))
                .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").executes(context -> execute(context, (ctx, director) -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("Operator-only Mars Command state: goal="
                                    + director.state().goalId + ", progress=" + director.state().goalProgress
                                    + ", phase=" + director.state().phaseIndex + ", totals=" + director.state().phaseTotals
                                    + ", activeMillis=" + director.state().activeMillis), false);
                            return 1;
                        })))
                        .then(Commands.literal("validate").executes(context -> execute(context, (ctx, director) -> {
                            director.rules().validate();
                            director.state().validate(director.rules());
                            ctx.getSource().sendSuccess(() -> Component.literal("Loaded configuration and mission state are valid. Restart to load edited configuration."), false);
                            return 1;
                        })))
                        .then(Commands.literal("strike").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("emergency", BoolArgumentType.bool())
                                        .executes(context -> execute(context, (ctx, director) -> {
                                            director.testStrike(EntityArgument.getPlayer(ctx, "player"),
                                                    BoolArgumentType.getBool(ctx, "emergency"));
                                            return 1;
                                        })))))
                        .then(Commands.literal("assign").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("template", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            MoonDirector director = director(context);
                                            director.rules().templates().forEach(template -> builder.suggest(template.id()));
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> execute(context, (ctx, director) -> {
                                            long id = director.assignTest(EntityArgument.getPlayer(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "template"));
                                            ctx.getSource().sendSuccess(() -> Component.literal("Assigned mission " + id), false);
                                            return 1;
                                        })))))
                        .then(Commands.literal("readiness").then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> execute(context, (ctx, director) -> {
                                    ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                                    for (MissionRules.Template template : director.rules().templates()) {
                                        double score = director.readiness(player, template);
                                        ctx.getSource().sendSuccess(() -> Component.literal(template.id()
                                                + ": readiness=" + score + ", required=" + director.rules().readinessThreshold()), false);
                                    }
                                    return 1;
                                }))))));
    }

    private static int status(CommandContext<CommandSourceStack> context, MoonDirector director) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        director.openJournal(player, 0);
        return 1;
    }

    private static int execute(CommandContext<CommandSourceStack> context, Action action) throws CommandSyntaxException {
        try {
            return action.run(context, director(context));
        } catch (IllegalArgumentException exception) {
            throw new SimpleCommandExceptionType(Component.literal(exception.getMessage())).create();
        }
    }

    private static MoonDirector director(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        try {
            return MoonDirector.get(context.getSource().getServer());
        } catch (IllegalStateException exception) {
            LogUtils.getLogger().error("Mars Command command rejected because the director is unavailable", exception);
            throw new SimpleCommandExceptionType(Component.translatable("message.mars_command.unavailable")).create();
        }
    }
}
