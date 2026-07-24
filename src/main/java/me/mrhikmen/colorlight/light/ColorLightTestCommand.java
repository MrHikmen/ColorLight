package me.mrhikmen.colorlight.light;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class ColorLightTestCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            dispatcher.register(ClientCommandManager.literal("colorlighttest")
                    .then(ClientCommandManager.argument("r", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                            .then(ClientCommandManager.argument("g", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                    .then(ClientCommandManager.argument("b", IntegerArgumentType.integer(0, ColorLightUtil.MAX))
                                            .executes(ColorLightTestCommand::addAtTarget)))));

            dispatcher.register(ClientCommandManager.literal("colorlighttestremove")
                    .executes(ColorLightTestCommand::removeAtTarget));

            dispatcher.register(ClientCommandManager.literal("colorlighttestclear")
                    .executes(ColorLightTestCommand::clearAll));
        });
    }

    private static int clearAll(CommandContext<FabricClientCommandSource> ctx) {

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return 0;

        engine.clearAll();

        var player = Minecraft.getInstance().player;
        if (player != null) {
            BlockPos pos = player.blockPosition();
            int radius = 64;

            Minecraft.getInstance().levelRenderer.setBlocksDirty(
                    pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                    pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius
            );
        }

        ctx.getSource().sendFeedback(Component.literal("All sources have been cleared"));
        return 1;
    }

    private static int addAtTarget(CommandContext<FabricClientCommandSource> ctx) {

        BlockPos pos = targetPos();
        if (pos == null) {
            ctx.getSource().sendError(Component.literal("Look at the block"));
            return 0;
        }

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null) {
            ctx.getSource().sendError(Component.literal("The engine has not yet been initialized."));
            return 0;
        }

        int r = IntegerArgumentType.getInteger(ctx, "r");
        int g = IntegerArgumentType.getInteger(ctx, "g");
        int b = IntegerArgumentType.getInteger(ctx, "b");

        engine.addSource(pos, r, g, b);
        markDirtyAround(pos);

        ctx.getSource().sendFeedback(Component.literal(
                "Source added to " + pos.toShortString() + " color = " + r + ", " + g + ", " + b));
        return 1;
    }

    private static int removeAtTarget(CommandContext<FabricClientCommandSource> ctx) {

        BlockPos pos = targetPos();
        if (pos == null) {
            ctx.getSource().sendError(Component.literal("Look at the block"));
            return 0;
        }

        ColorLightEngine engine = ColorLightEngineHolder.get();
        if (engine == null)
            return 0;

        engine.removeSource(pos);
        markDirtyAround(pos);

        ctx.getSource().sendFeedback(Component.literal("Source removed from " + pos.toShortString()));
        return 1;
    }

    private static BlockPos targetPos() {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static void markDirtyAround(BlockPos pos) {

        int radius = ColorLightEngine.MAX_RANGE_BLOCKS + 1;

        Minecraft.getInstance().levelRenderer.setBlocksDirty(
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius
        );
    }

    private ColorLightTestCommand() {
    }
}
