package me.mrhikmen.colorlight.compat.lambdynlights;

import me.mrhikmen.colorlight.ColorLightClient;
import me.mrhikmen.colorlight.config.BlockSettings;
import me.mrhikmen.colorlight.config.ColorLightConfig;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngine;
import me.mrhikmen.colorlight.core.light.engine.ColorLightEngineHolder;
import me.mrhikmen.colorlight.core.light.engine.ColorLightPropagationMode;
import me.mrhikmen.colorlight.core.util.ColorLightRenderUtil;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ColorLightEntityLightTicker {
    private record QuantizedPos(long ex, long ey, long ez) {
        static QuantizedPos of(double x, double y, double z) {
            return new QuantizedPos(Math.round(x * 8.0), Math.round(y * 8.0), Math.round(z * 8.0));
        }
    }

    private record TrackedSource(QuantizedPos qpos, List<BlockPos> keys, BlockPos anchor, BlockSettings settings) {
    }

    private static final Map<Integer, TrackedSource> ACTIVE_SOURCES = new HashMap<>();

    public static void register() {
        if (!ColorLightLambDynLightsCompat.isPresent())
            return;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientLevel level = client.level;
            if (level == null || client.player == null)
                return;
            ColorLightEngine engine = ColorLightEngineHolder.get();
            if (engine == null)
                return;
            if (!ColorLightClient.config.ENTITY_TRACKING_ENABLED) {
                clearAllTracked(engine);
                return;
            }

            BlockPos playerPos = client.player.blockPosition();
            int checkRadiusBlocks = checkRadiusBlocks(client.options.renderDistance().get());
            for (Entity entity : level.entitiesForRendering()) {
                if (!isTrackableSource(entity))
                    continue;

                if (entity.blockPosition().distManhattan(playerPos) > checkRadiusBlocks)
                    continue;

                processEntity(engine, entity);
            }
            ACTIVE_SOURCES.keySet().removeIf(id -> {
                Entity e = level.getEntity(id);
                boolean shouldRemove = (e == null)
                        || e.blockPosition().distManhattan(playerPos) > checkRadiusBlocks;
                if (shouldRemove) {
                    TrackedSource tracked = ACTIVE_SOURCES.get(id);
                    removeTracked(engine, tracked);
                }
                return shouldRemove;
            });
        });
    }

    private static boolean isTrackableSource(Entity entity) {
        return entity instanceof LivingEntity
                || entity instanceof ItemEntity
                || entity instanceof ItemFrame
                || entity instanceof AbstractMinecart;
    }

    private static int checkRadiusBlocks(int clientRenderDistanceChunks) {
        ColorLightConfig config = ColorLightClient.config;
        int chunks = config.ENTITY_CHECK_FOLLOW_RENDER_DISTANCE
                ? clientRenderDistanceChunks
                : config.ENTITY_CHECK_RADIUS_CHUNKS;
        return chunks * 16;
    }

    private static void clearAllTracked(ColorLightEngine engine) {
        if (ACTIVE_SOURCES.isEmpty())
            return;

        for (TrackedSource tracked : ACTIVE_SOURCES.values()) {
            removeTracked(engine, tracked);
        }
        ACTIVE_SOURCES.clear();
    }

    private static void processEntity(ColorLightEngine engine, Entity entity) {
        BlockSettings settings = findGlowingBlockSettings(entity);

        if (settings == null) {
            removeIfTracked(engine, entity.getId());
            return;
        }

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        QuantizedPos qpos = QuantizedPos.of(x, y, z);

        TrackedSource previous = ACTIVE_SOURCES.get(entity.getId());

        if (previous != null && previous.qpos().equals(qpos) && previous.settings() == settings)
            return;

        if (previous != null) {
            for (BlockPos key : previous.keys()) {
                engine.removeSource(key);
            }
        }
        BlockPos anchor = entity.blockPosition();
        List<BlockPos> keys = engine.addBlendedSource(x, y, z, settings.r, settings.g, settings.b,
                Math.min(7, settings.light), ColorLightPropagationMode.SMOOTH);
        ACTIVE_SOURCES.put(entity.getId(), new TrackedSource(qpos, keys, anchor, settings));

        markDirtyAround(engine, anchor);
        if (previous != null && !previous.anchor().equals(anchor)) {
            markDirtyAround(engine, previous.anchor());
        }
    }

    private static BlockSettings findGlowingBlockSettings(Entity entity) {
        if (entity instanceof LivingEntity living) {
            BlockSettings fromMain = settingsOf(living.getMainHandItem());
            if (fromMain != null)
                return fromMain;
            return settingsOf(living.getOffhandItem());
        }

        if (entity instanceof ItemEntity itemEntity) {
            return settingsOf(itemEntity.getItem());
        }

        if (entity instanceof ItemFrame itemFrame) {
            return settingsOf(itemFrame.getItem());
        }

        if (entity instanceof AbstractMinecart minecart) {
            return settingsOf(minecart.getDisplayBlockState());
        }

        return null;
    }

    private static BlockSettings settingsOf(ItemStack stack) {
        if (stack.isEmpty())
            return null;
        if (!(stack.getItem() instanceof BlockItem blockItem))
            return null;

        return settingsOf(blockItem.getBlock().defaultBlockState());
    }

    private static BlockSettings settingsOf(BlockState state) {
        if (state == null || state.isAir())
            return null;

        Block block = state.getBlock();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId == null)
            return null;

        for (BlockSettings entry : ColorLightClient.config.blocks) {
            if (entry.enable && entry.getBlock().equals(blockId)) {
                return entry;
            }
        }
        return null;
    }

    private static void removeIfTracked(ColorLightEngine engine, int entityId) {
        TrackedSource tracked = ACTIVE_SOURCES.remove(entityId);
        if (tracked != null) {
            removeTracked(engine, tracked);
        }
    }

    private static void removeTracked(ColorLightEngine engine, TrackedSource tracked) {
        for (BlockPos key : tracked.keys()) {
            engine.removeSource(key);
        }
        markDirtyAround(engine, tracked.anchor());
    }

    private static void markDirtyAround(ColorLightEngine engine, BlockPos pos) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.level == null || pos == null)
            return;

        int radius = engine.getMaxRangeBlocks() + 1;
        ColorLightRenderUtil.setBlocksDirty(client.level,
                pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                pos.getX() + radius, pos.getY() + radius, pos.getZ() + radius
        );
    }

    private ColorLightEntityLightTicker() {
    }
}