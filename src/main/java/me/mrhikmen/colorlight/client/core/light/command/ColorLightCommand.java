package me.mrhikmen.colorlight.client.core.light.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import me.mrhikmen.colorlight.api.propagation.PropagationMethod;
import me.mrhikmen.colorlight.api.propagation.PropagationMethodRegistry;
import me.mrhikmen.colorlight.client.config.Translatable;
import me.mrhikmen.colorlight.client.core.light.color.ColorLightUtil;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.client.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.client.core.resourcepack.script.ScriptRuntime;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ColorLightCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {dispatcher.register(
                    ClientCommands.literal("colorlight")

                            .then(ClientCommands.literal("inspect")
                                    .executes(ColorLightCommand::debugDaylight))

                            .then(ClientCommands.literal("target")
                                    .then(ClientCommands.literal("set")
                                            .then(ClientCommands.argument("r", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                                            .then(ClientCommands.argument("g", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                                                            .then(ClientCommands.argument("b", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                                                                            .then(ClientCommands.argument("strength", IntegerArgumentType.integer(1, 32))
                                                                                                            .executes(ctx ->
                                                                                                                    addAtTarget(ctx, null)
                                                                                                            )
                                                                                                            .then(ClientCommands.argument("method", StringArgumentType.word())
                                                                                                                            .suggests((ctx, builder) -> {suggestMethods(builder); return builder.buildFuture();})
                                                                                                                            .executes(ctx -> addAtTarget(ctx, StringArgumentType.getString(ctx, "method")))
                                                                                                            )
                                                                                                            .then(ClientCommands.argument("x", RelativeCoordinateArgumentType.coordinate())
                                                                                                                            .then(ClientCommands.argument("y", RelativeCoordinateArgumentType.coordinate())
                                                                                                                                            .then(ClientCommands.argument("z", RelativeCoordinateArgumentType.coordinate())
                                                                                                                                                            .executes(ctx -> addAtCoordinates(ctx, null))
                                                                                                                                                            .then(ClientCommands.argument("method", StringArgumentType.word())
                                                                                                                                                                            .suggests((ctx, builder) -> {suggestMethods(builder); return builder.buildFuture();})
                                                                                                                                                                            .executes(ctx -> addAtCoordinates(ctx, StringArgumentType.getString(ctx, "method")))))))))))
                                    )
                                    .then(ClientCommands.literal("reset")
                                            .executes(ColorLightCommand::removeAtTarget)

                                            .then(ClientCommands.argument("x", RelativeCoordinateArgumentType.coordinate())
                                                            .then(ClientCommands.argument("y", RelativeCoordinateArgumentType.coordinate())
                                                                            .then(ClientCommands.argument("z", RelativeCoordinateArgumentType.coordinate())
                                                                                            .executes(ColorLightCommand::removeAtCoordinates))))
                                    )
                            )
                            .then(ClientCommands.literal("clear")
                                    .executes(ColorLightCommand::clearAll)
                            )
                            .then(ClientCommands.literal("scripts")
                                    .executes(ColorLightCommand::listScripts)
                            )
            );
        });
    }

    private static void suggestMethods(SuggestionsBuilder builder) {
        for (PropagationMethod method : PropagationMethodRegistry.all()) {
            builder.suggest(method.id().getPath());
        }
    }

    private static int debugDaylight(CommandContext<FabricClientCommandSource> ctx) {
        BlockPos pos = targetPos();
        if (pos == null) {
            var player = Minecraft.getInstance().player;

            if (player == null)
                return 0;

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

        engine.clearAll();

        ctx.getSource().sendFeedback(Translatable.CLEAN_ALL);
        return 1;
    }

    private static int listScripts(CommandContext<FabricClientCommandSource> ctx) {

        for (PropagationMethod method : PropagationMethodRegistry.all()) {
            String source = (method instanceof me.mrhikmen.colorlight.client.core.resourcepack.lua.ScriptedPropagationMethod scripted) ? scripted.source() : "mod (Java)";
            ctx.getSource().sendFeedback(Component.literal(method.id() + " - " + method.displayName() + " [" + source + "]"));
        }

        for (String problem : ScriptRuntime.problems()) {
            ctx.getSource().sendError(Component.literal(problem));
        }
        if (ScriptRuntime.problems().isEmpty()) {
            ctx.getSource().sendFeedback(Component.literal("No script problems."));
        }

        return 1;
    }

    private static int addAtTarget(CommandContext<FabricClientCommandSource> ctx, String methodName) {
        BlockPos pos = targetPos();
        if (pos == null) {
            ctx.getSource().sendError(Translatable.LOOK_AT_BLOCK);
            return 0;
        }
        return addSource(ctx, pos, methodName);
    }

    private static int addAtCoordinates(CommandContext<FabricClientCommandSource> ctx, String methodName) {
        BlockPos pos = resolveCoordinates(ctx);
        if (pos == null)
            return 0;

        return addSource(ctx, pos, methodName);
    }

    private static int addSource(CommandContext<FabricClientCommandSource> ctx, BlockPos pos, String methodName) {
        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null) {
            ctx.getSource().sendError(Translatable.ENGINE_OFF);
            return 0;
        }

        int r = IntegerArgumentType.getInteger(ctx, "r");
        int g = IntegerArgumentType.getInteger(ctx, "g");
        int b = IntegerArgumentType.getInteger(ctx, "b");
        int strength = IntegerArgumentType.getInteger(ctx, "strength");

        Identifier method = null;

        if (methodName != null && !methodName.isBlank()) {
            method = PropagationMethodRegistry.parse(methodName);

            if (!methodName.contains(":") && !PropagationMethodRegistry.contains(method)) {
                Identifier own = PropagationMethodRegistry.parse("colorlight:" + methodName.trim());

                if (own != null)
                    method = own;
            }

            if (!PropagationMethodRegistry.contains(method)) {
                ctx.getSource().sendError(Component.literal("Unknown propagation method '" + methodName + "'. Try /colorlight scripts"));
                return 0;
            }
        }

        engine.removeSource(pos);
        engine.addSource(pos, r, g, b, strength, method);

        ctx.getSource().sendFeedback(Translatable.LIGHT_ADD);
        return 1;
    }

    private static int removeAtTarget(CommandContext<FabricClientCommandSource> ctx) {
        BlockPos pos = targetPos();
        if (pos == null) {ctx.getSource().sendError(Translatable.LOOK_AT_BLOCK);
            return 0;
        }
        return removeSource(ctx, pos);
    }

    private static int removeAtCoordinates(CommandContext<FabricClientCommandSource> ctx) {
        BlockPos pos = resolveCoordinates(ctx);

        if (pos == null)
            return 0;

        return removeSource(ctx, pos);
    }

    private static int removeSource(CommandContext<FabricClientCommandSource> ctx, BlockPos pos) {
        ColorLightEngine engine = ColorLightEngineHolder.get();

        if (engine == null)
            return 0;

        engine.removeSource(pos);
        ctx.getSource().sendFeedback(Translatable.LIGHT_DEL);

        return 1;
    }

    private static BlockPos resolveCoordinates(CommandContext<FabricClientCommandSource> ctx) {
        var player = Minecraft.getInstance().player;
        if (player == null) {ctx.getSource().sendError(Component.literal("Player is not available."));
            return null;
        }

        RelativeCoordinate x = RelativeCoordinateArgumentType.get(ctx, "x");

        RelativeCoordinate y = RelativeCoordinateArgumentType.get(ctx, "y");

        RelativeCoordinate z = RelativeCoordinateArgumentType.get(ctx, "z");

        BlockPos base = player.blockPosition();

        return new BlockPos(x.resolve(base.getX()), y.resolve(base.getY()), z.resolve(base.getZ()));
    }

    private static BlockPos targetPos() {
        HitResult hit = Minecraft.getInstance().hitResult;

        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {

            return blockHit.getBlockPos();
        }
        return null;
    }

    private record RelativeCoordinate(boolean relative, int value) {
        int resolve(int base) {
            return relative ? base + value : value;
        }
    }

    private static final class RelativeCoordinateArgumentType implements ArgumentType<RelativeCoordinate> {

        private static final Collection<String> EXAMPLES = List.of("0", "10", "-10", "~", "~5", "~-5");

        private RelativeCoordinateArgumentType() {
        }

        public static RelativeCoordinateArgumentType coordinate() {
            return new RelativeCoordinateArgumentType();
        }

        @Override
        public RelativeCoordinate parse(StringReader reader) throws CommandSyntaxException {

            boolean relative = false;

            if (reader.canRead() && reader.peek() == '~') {

                relative = true;
                reader.skip();

                if (!reader.canRead() || reader.peek() == ' ') {
                    return new RelativeCoordinate(true, 0);
                }
            }
            int value = reader.readInt();

            return new RelativeCoordinate(relative, value);
        }

        @Override
        public <S> CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {

            builder.suggest("~");
            builder.suggest("~1");
            builder.suggest("~-1");

            return builder.buildFuture();
        }

        @Override
        public Collection<String> getExamples() {
            return EXAMPLES;
        }

        public static RelativeCoordinate get(CommandContext<?> context, String name) {
            return context.getArgument(name, RelativeCoordinate.class);
        }
    }

    private ColorLightCommand() {
    }
}