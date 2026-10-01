package me.mrhikmen.colorlight.client.core.light.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import me.mrhikmen.colorlight.client.config.Translatable;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.script.ScriptRuntime;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class ColorLightCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("colorlight")
                    .then(ClientCommands.literal("inspect")
                            .executes(ColorLightCommand::debugDaylight))

                    .then(ClientCommands.literal("target")
                            .then(ClientCommands.literal("set")
                                    .then(ClientCommands.argument("r", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                            .then(ClientCommands.argument("g", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                                    .then(ClientCommands.argument("b", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                                            .then(ClientCommands.argument("strength", IntegerArgumentType.integer(1, 32))
                                                                    .executes(ctx -> addAtTarget(ctx, null))
                                                                    .then(ClientCommands.argument("method", StringArgumentType.greedyString())
                                                                            .suggests((ctx, builder) -> {
                                                                                for (PropagationMethod method : PropagationMethodRegistry.all())
                                                                                    builder.suggest(method.id().getPath());
                                                                                return builder.buildFuture();
                                                                            })
                                                                            .executes(ctx -> addAtTarget(ctx, StringArgumentType.getString(ctx, "method")))))))))

                            .then(ClientCommands.literal("reset")
                                    .executes(ColorLightCommand::removeAtTarget))
                    )

                    .then(ClientCommands.literal("clear")
                            .executes(ColorLightCommand::clearAll))

                    .then(ClientCommands.literal("scripts")
                            .executes(ColorLightCommand::listScripts))
            );
        });
    }

    private static int debugDaylight(CommandContext<FabricClientCommandSource> ctx) {

        BlockPos pos = targetPos();
        if (pos == null) {
            var player = Minecraft.getInstance().player;
            if (player == null) return 0;
            pos = player.blockPosition();
        }

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null) {
            ctx.getSource().sendError(Translatable.ENGINE_OFF);
            return 0;
        }

        ctx.getSource().sendFeedback(Component.literal(engine.debugDaylight(pos)));
        return 1;
    }

    private static int clearAll(CommandContext<FabricClientCommandSource> ctx) {

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return 0;

        // clearAll() marks every section it had lit as dirty itself
        engine.clearAll();

        ctx.getSource().sendFeedback(Translatable.CLEAN_ALL);
        return 1;
    }

    /** Lists the propagation methods and any script problems, so pack authors see what loaded. */
    private static int listScripts(CommandContext<FabricClientCommandSource> ctx) {
        for (PropagationMethod method : PropagationMethodRegistry.all()) {
            String source = (method instanceof me.mrhikmen.colorlight.client.lua.ScriptedPropagationMethod scripted) ? scripted.source() : "mod (Java)";
            ctx.getSource().sendFeedback(Component.literal(method.id() + " - " + method.displayName() + "  [" + source + "]"));
        }
        for (String problem : ScriptRuntime.problems())
            ctx.getSource().sendError(Component.literal(problem));
        if (ScriptRuntime.problems().isEmpty())
            ctx.getSource().sendFeedback(Component.literal("No script problems."));
        return 1;
    }

    private static int addAtTarget(CommandContext<FabricClientCommandSource> ctx, String methodName) {

        ColorLightCommand.removeAtTarget(ctx);

        BlockPos pos = targetPos();
        if (pos == null) {
            ctx.getSource().sendError(Translatable.LOOK_AT_BLOCK);
            return 0;
        }

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null) {
            ctx.getSource().sendError(Translatable.ENGINE_OFF);
            return 0;
        }

        int r = IntegerArgumentType.getInteger(ctx, "r");
        int g = IntegerArgumentType.getInteger(ctx, "g");
        int b = IntegerArgumentType.getInteger(ctx, "b");
        int strength = IntegerArgumentType.getInteger(ctx, "strength");

        Identifier method = PropagationMethodRegistry.parse(methodName);
        if (methodName != null && !methodName.contains(":") && !PropagationMethodRegistry.contains(method)) {
            Identifier own = PropagationMethodRegistry.parse("colorlight:" + methodName.trim());
            if (own != null)
                method = own;
        }
        if (methodName != null && !PropagationMethodRegistry.contains(method)) {
            ctx.getSource().sendError(Component.literal("Unknown propagation method '" + methodName + "'. Try /colorlight scripts"));
            return 0;
        }
        engine.addSource(pos, r, g, b, strength, method);

        ctx.getSource().sendFeedback(Translatable.LIGHT_ADD);
        return 1;
    }

    private static int removeAtTarget(CommandContext<FabricClientCommandSource> ctx) {

        BlockPos pos = targetPos();
        if (pos == null) {
            ctx.getSource().sendError(Translatable.LOOK_AT_BLOCK);
            return 0;
        }

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return 0;

        engine.removeSource(pos);

        ctx.getSource().sendFeedback(Translatable.LIGHT_DEL);
        return 1;
    }

    private static BlockPos targetPos() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private ColorLightCommand() {
    }
}