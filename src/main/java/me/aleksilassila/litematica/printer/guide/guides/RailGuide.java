package me.aleksilassila.litematica.printer.guide.guides;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 铁轨。
 */
public class RailGuide extends Guide {
    private static final int MAX_REPAIR_ATTEMPTS = 3;
    private static final int PENDING_REPAIR_TICKS = 40;
    private static final Map<RailRepairKey, Integer> repairAttempts = new HashMap<>();
    private static final Map<RailRepairKey, PendingRepair> pendingRepairs = new HashMap<>();
    private static final Map<RailRepairKey, StableRepair> stableRepairs = new HashMap<>();

    public RailGuide(SchematicBlockContext context) {
        super(context);
    }

    public static void clearRepairState() {
        repairAttempts.clear();
        pendingRepairs.clear();
        stableRepairs.clear();
    }

    /** Re-arm only after a correct shape survives the existing repair observation window.
     * This visits repaired rails only; a completed scan need not repeatedly yield correct blocks. */
    public static void tickRepairState(ClientLevel level, WorldSchematic schematic) {
        if (!Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue()) {
            clearRepairState();
            return;
        }
        var iterator = stableRepairs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            StableRepair stable = entry.getValue();
            RailGuide guide = stable.guide;
            if (guide.level != level || guide.schematic != schematic || finishStableRepair(entry.getKey(), stable)) {
                iterator.remove();
            }
        }
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        clearPendingRepairState(blockPos);
        stableRepairs.keySet().removeIf(key -> key.matches(level.dimension(), blockPos));

        Optional<RailShape> railShape = getRailShape(requiredState);

        if (railShape.isEmpty()) return Result.PASS;

        Action action = new Action();
        switch (railShape.get()) {
            case EAST_WEST, ASCENDING_EAST -> action.setLookDirection(Direction.EAST);
            case NORTH_SOUTH, ASCENDING_NORTH -> action.setLookDirection(Direction.NORTH);
            case ASCENDING_WEST -> action.setLookDirection(Direction.WEST);
            case ASCENDING_SOUTH -> action.setLookDirection(Direction.SOUTH);
            default -> {
            }
        }
        return Result.success(action);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (!Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue()) {
            clearRepairState(blockPos);
            return Result.SKIP;
        }

        Optional<RailShape> requiredShape = getRailShape(requiredState);
        Optional<RailShape> currentShape = getRailShape(currentState);
        if (requiredShape.isEmpty() || currentShape.isEmpty()) {
            return Result.SKIP;
        }
        if (requiredShape.equals(currentShape)) {
            observeCorrectShape(requiredShape.get());
            return Result.SKIP;
        }

        RailRepairKey key = repairKey(requiredShape.get());
        stableRepairs.remove(key);
        if (!railConnectionsReady(requiredShape.get())
                || isRepairPending(key)
                || repairAttempts.getOrDefault(key, 0) >= MAX_REPAIR_ATTEMPTS
                || !InteractionUtils.canBreakBlock(blockPos)
                || !InteractionUtils.breakRestriction(currentState)) {
            return Result.SKIP;
        }

        PendingRepair pending = new PendingRepair();
        pendingRepairs.put(key, pending);
        if (!InteractionUtils.getRuntime().add(key.pos(), () -> stillNeedsRepair(key, pending), () -> {
            if (pendingRepairs.get(key) != pending) return;
            repairAttempts.merge(key, 1, Integer::sum);
            pending.startedAt = level.getGameTime();
        })) {
            pendingRepairs.remove(key, pending);
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        clearPendingRepairState(blockPos);
        getRailShape(requiredState).ifPresent(this::observeCorrectShape);
        return Result.PASS;
    }

    private void observeCorrectShape(RailShape shape) {
        RailRepairKey key = repairKey(shape);
        if (!repairAttempts.containsKey(key)) return;
        if (!Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue() || !isMatchingShape(key)) {
            stableRepairs.remove(key);
            return;
        }
        StableRepair stable = stableRepairs.computeIfAbsent(key, ignored -> new StableRepair(this));
        if (finishStableRepair(key, stable)) stableRepairs.remove(key);
    }

    private static boolean finishStableRepair(RailRepairKey key, StableRepair stable) {
        RailGuide guide = stable.guide;
        if (!guide.isMatchingShape(key)) return true;
        // Keep observing a correct rail while old work drains. The completed scan may never yield it again.
        if (InteractionUtils.getRuntime().isBreakPending(key.pos())
                || RuntimeAccess.get().hudStats().isPrintPlacementPending(key.pos())) {
            stable.since = null;
            return false;
        }
        long now = guide.level.getGameTime();
        if (stable.since == null || now < stable.since) stable.since = now;
        if (now - stable.since < PENDING_REPAIR_TICKS) return false;
        repairAttempts.remove(key);
        pendingRepairs.remove(key);
        return true;
    }

    private boolean isMatchingShape(RailRepairKey key) {
        return client.level == level && level.hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)
                && schematic.getBlockState(blockPos).equals(requiredState)
                && level.getBlockState(blockPos).is(requiredBlock)
                && getRailShape(level.getBlockState(blockPos)).filter(key.requiredShape()::equals).isPresent();
    }

    private boolean stillNeedsRepair(RailRepairKey key, PendingRepair pending) {
        if (!Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue() || !ConfigUtils.isEnable() || !ConfigUtils.isPrintMode()
                || client.level != level || pendingRepairs.get(key) != pending
                || !level.hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)
                || !schematic.getBlockState(blockPos).equals(requiredState)) return false;
        BlockState actual = level.getBlockState(blockPos);
        return actual.is(requiredBlock) && getRailShape(actual).filter(shape -> shape != key.requiredShape()).isPresent()
                && railConnectionsReady(key.requiredShape());
    }

    private Optional<RailShape> getRailShape(BlockState state) {
        return getProperty(state, BlockStateProperties.RAIL_SHAPE)
                .or(() -> getProperty(state, BlockStateProperties.RAIL_SHAPE_STRAIGHT));
    }

    private boolean isRepairPending(RailRepairKey key) {
        if (InteractionUtils.getRuntime().isBreakPending(key.pos())) return true;
        PendingRepair pending = pendingRepairs.get(key);
        if (pending == null) {
            return false;
        }
        if (pending.startedAt != null && level.getGameTime() - pending.startedAt < PENDING_REPAIR_TICKS) {
            return true;
        }
        pendingRepairs.remove(key);
        return false;
    }

    private void clearRepairState(BlockPos pos) {
        ResourceKey<Level> dimension = level.dimension();
        repairAttempts.keySet().removeIf(key -> key.matches(dimension, pos));
        pendingRepairs.keySet().removeIf(key -> key.matches(dimension, pos));
        stableRepairs.keySet().removeIf(key -> key.matches(dimension, pos));
    }

    private void clearPendingRepairState(BlockPos pos) {
        ResourceKey<Level> dimension = level.dimension();
        pendingRepairs.keySet().removeIf(key -> key.matches(dimension, pos));
    }

    private RailRepairKey repairKey(RailShape shape) {
        return new RailRepairKey(level.dimension(), blockPos.immutable(), shape);
    }

    private boolean railConnectionsReady(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> connectionReady(blockPos.north()) && connectionReady(blockPos.south());
            case EAST_WEST -> connectionReady(blockPos.west()) && connectionReady(blockPos.east());
            case ASCENDING_EAST -> connectionReady(blockPos.west()) && connectionReady(blockPos.east().above());
            case ASCENDING_WEST -> connectionReady(blockPos.west().above()) && connectionReady(blockPos.east());
            case ASCENDING_NORTH -> connectionReady(blockPos.north().above()) && connectionReady(blockPos.south());
            case ASCENDING_SOUTH -> connectionReady(blockPos.north()) && connectionReady(blockPos.south().above());
            case SOUTH_EAST -> connectionReady(blockPos.south()) && connectionReady(blockPos.east());
            case SOUTH_WEST -> connectionReady(blockPos.south()) && connectionReady(blockPos.west());
            case NORTH_WEST -> connectionReady(blockPos.north()) && connectionReady(blockPos.west());
            case NORTH_EAST -> connectionReady(blockPos.north()) && connectionReady(blockPos.east());
        };
    }

    private boolean connectionReady(BlockPos pos) {
        BlockState schematicState = schematic.getBlockState(pos);
        return !BaseRailBlock.isRail(schematicState) || BaseRailBlock.isRail(level.getBlockState(pos));
    }

    private record RailRepairKey(ResourceKey<Level> dimension, BlockPos pos, RailShape requiredShape) {
        private boolean matches(ResourceKey<Level> dimension, BlockPos pos) {
            return this.dimension.equals(dimension) && this.pos.equals(pos);
        }
    }

    private static final class PendingRepair {
        private Long startedAt;
    }

    private static final class StableRepair {
        private final RailGuide guide;
        private Long since;

        private StableRepair(RailGuide guide) {
            this.guide = guide;
        }
    }
}
