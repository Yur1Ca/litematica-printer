package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/** Owns residue positions and late-update watches; the placer owns actual mining attempts. */
final class BedrockCleanupCoordinator {
    private static final int CLEANUP_WATCH_TICKS = 40;
    private final Minecraft client;
    private final BooleanSupplier mutationsDeferred;
    private final BedrockPlacer placer;
    private final Set<BlockPos> queue = new LinkedHashSet<>();
    private final Set<BlockPos> blocked = new LinkedHashSet<>();
    private final Map<BlockPos, Long> recentAuthoritativeCleanup = new HashMap<>();
    private final Map<BlockPos, CleanupCycle> cycles = new HashMap<>();
    private boolean orderDirty;

    BedrockCleanupCoordinator(Minecraft client, BooleanSupplier mutationsDeferred, BedrockPlacer placer) {
        this.client = client;
        this.mutationsDeferred = mutationsDeferred;
        this.placer = placer;
    }

    void reset() {
        this.queue.clear();
        this.blocked.clear();
        this.recentAuthoritativeCleanup.clear();
        this.cycles.clear();
        this.orderDirty = false;
    }

    void beginTick() {
        this.blocked.clear();
        long now = this.currentTick();
        this.recentAuthoritativeCleanup.entrySet().removeIf(entry -> entry.getValue() < now);
        this.cycles.values().removeIf(cycle -> cycle.positions().stream().noneMatch(pos ->
                this.queue.contains(pos) || this.recentAuthoritativeCleanup.containsKey(pos)));
    }

    boolean isEmpty() {
        return this.queue.isEmpty();
    }

    int size() {
        return this.queue.size();
    }

    boolean contains(BlockPos pos) {
        return this.queue.contains(pos);
    }

    void add(BlockPos pos, boolean predictRemoval) {
        if (pos == null) {
            return;
        }
        if (!predictRemoval) this.watch(pos);
        ClientLevel level = this.client.level;
        if (level == null) return;
        // An unloaded client chunk returns AIR without acknowledging any server removal.
        // Retain the owned footprint until it can be observed in a loaded chunk again.
        if (BedrockEnvironment.isChunkLoaded(level, pos)
                && !BedrockTargetBlocks.isCleanupResidue(level.getBlockState(pos))) {
            this.remove(pos);
            return;
        }
        this.queue.add(pos.immutable());
        this.orderDirty = true;
    }

    /** Retain late-update ownership without crossing an inventory or interaction boundary. */
    void enqueueAuthoritative(BlockPos pos) {
        if (pos == null) return;
        this.add(pos, false);
    }

    /** Retired machines keep their slot through reachable cleanup, with shared positions queued once. */
    void enqueueCycle(BlockPos owner, Set<BlockPos> positions, boolean horizontal) {
        if (owner == null || positions == null || positions.isEmpty()) return;
        var previous = this.cycles.get(owner);
        Set<BlockPos> footprint = previous == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(previous.positions());
        for (BlockPos pos : positions) {
            if (pos == null) continue;
            footprint.add(pos.immutable());
            this.enqueueAuthoritative(pos);
        }
        if (!footprint.isEmpty()) this.cycles.put(owner.immutable(),
                new CleanupCycle(footprint, horizontal || (previous != null && previous.horizontal())));
    }

    /** A successfully admitted machine owns future updates for its reused positions. */
    void transferOwnership(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return;
        var transferred = new LinkedHashSet<BlockPos>();
        for (BlockPos pos : positions) {
            if (pos == null || this.placer.isCleanupPendingAt(pos)) continue;
            transferred.add(pos);
            this.queue.remove(pos);
            this.blocked.remove(pos);
            this.recentAuthoritativeCleanup.remove(pos);
        }
        if (transferred.isEmpty()) return;
        var iterator = this.cycles.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var remaining = new LinkedHashSet<>(entry.getValue().positions());
            remaining.removeAll(transferred);
            if (remaining.isEmpty()) iterator.remove();
            else entry.setValue(new CleanupCycle(remaining, entry.getValue().horizontal()));
        }
        this.orderDirty = true;
    }

    int reachableCycleCount() {
        return this.countReachableCycles(false);
    }

    int verticalCycleCount() {
        return this.countReachableCycles(true);
    }

    boolean hasReachableCycle(BlockPos owner) {
        var cycle = this.cycles.get(owner);
        return cycle != null && this.isReachableCycle(cycle);
    }

    boolean isCycleClear(BlockPos owner) {
        var cycle = this.cycles.get(owner);
        return cycle == null || this.client.level != null && cycle.positions().stream().allMatch(pos ->
                BedrockEnvironment.isChunkLoaded(this.client.level, pos)
                        && !BedrockTargetBlocks.isCleanupResidue(this.client.level.getBlockState(pos))
                        && !this.placer.isCleanupPendingAt(pos));
    }

    private boolean isReachableCycle(CleanupCycle cycle) {
        return this.client.level != null && this.client.player != null && cycle.positions().stream().anyMatch(pos ->
                this.queue.contains(pos) && BedrockEnvironment.isChunkLoaded(this.client.level, pos)
                        && BedrockEnvironment.canInteract(pos)
                        && BedrockTargetBlocks.isCleanupResidue(this.client.level.getBlockState(pos)));
    }

    private int countReachableCycles(boolean verticalOnly) {
        ClientLevel level = this.client.level;
        if (level == null || this.client.player == null) return 0;
        int count = 0;
        for (CleanupCycle cycle : this.cycles.values()) {
            if (verticalOnly && cycle.horizontal()) continue;
            if (this.isReachableCycle(cycle)) count++;
        }
        return count;
    }

    private record CleanupCycle(Set<BlockPos> positions, boolean horizontal) { }

    /** Clear current work without forgetting placements already in flight during the watch window. */
    void remove(BlockPos pos) {
        this.queue.remove(pos);
        this.blocked.remove(pos);
        this.orderDirty = true;
    }

    void markBlocked(BlockPos pos) {
        if (pos != null) {
            this.blocked.add(pos);
            this.orderDirty = true;
        }
    }

    void process(Predicate<BlockPos> reserved, int limit) {
        if (this.queue.isEmpty() || limit <= 0 || this.mutationsDeferred.getAsBoolean()) {
            return;
        }
        this.reorder();
        var candidates = new ArrayList<BlockPos>();
        Iterator<BlockPos> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            ClientLevel level = this.client.level;
            if (level == null) {
                break;
            }
            if (!BedrockEnvironment.isChunkLoaded(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                this.blocked.remove(pos);
                continue;
            }
            if (reserved.test(pos)) {
                this.markBlocked(pos);
                continue;
            }
            candidates.add(pos);
        }
        this.placer.breakCleanupBlocks(candidates, limit);
    }

    int samplePressure(ClientLevel level, Predicate<BlockPos> reserved) {
        int pressure = 0;
        Iterator<BlockPos> iterator = this.queue.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (pos == null || !BedrockEnvironment.isChunkLoaded(level, pos) || reserved.test(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                this.blocked.remove(pos);
                continue;
            }
            if (BedrockEnvironment.canInteract(pos)
                    && !state.isAir() && BedrockTargetBlocks.isCleanupResidue(state)) {
                pressure += pressureWeight(state);
            }
        }
        return pressure;
    }

    void onServerBlockUpdate(BlockPos pos) {
        Long deadline = pos == null ? null : this.recentAuthoritativeCleanup.get(pos);
        if (deadline == null) {
            return;
        }
        ClientLevel level = this.client.level;
        if (level == null) {
            return;
        }
        // An update can arrive before beginTick has pruned expired watches.
        if (deadline < this.currentTick()) {
            this.recentAuthoritativeCleanup.remove(pos);
            return;
        }
        if (!BedrockEnvironment.isChunkLoaded(level, pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
            this.remove(pos);
            return;
        }
        this.queue.add(pos.immutable());
        this.orderDirty = true;
    }

    private void watch(BlockPos pos) {
        long now = this.currentTick();
        if (now == Long.MIN_VALUE) return;
        this.recentAuthoritativeCleanup.put(
                pos.immutable(), now + CLEANUP_WATCH_TICKS);
    }

    private long currentTick() {
        return this.client.level == null ? Long.MIN_VALUE : this.client.level.getGameTime();
    }

    int schedulingPenalty(BlockState state) {
        if (state.is(Blocks.MOVING_PISTON)) {
            return 100;
        }
        if (state.is(Blocks.SLIME_BLOCK)) {
            return 80;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.PISTON)) {
            return 70;
        }
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return 55;
        }
        return 40;
    }

    private void reorder() {
        ClientLevel level = this.client.level;
        if (!this.orderDirty || this.queue.size() < 2 || level == null) {
            return;
        }
        List<BlockPos> ordered = new ArrayList<>(this.queue);
        ordered.sort((left, right) -> Integer.compare(
                this.priority(left, level.getBlockState(left)),
                this.priority(right, level.getBlockState(right))
        ));
        this.queue.clear();
        this.queue.addAll(ordered);
        this.orderDirty = false;
    }

    private int priority(BlockPos pos, BlockState state) {
        int priority = this.blocked.contains(pos) ? -10 : 0;
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return priority;
        }
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.PISTON)) {
            return priority + 1;
        }
        if (state.is(Blocks.MOVING_PISTON)) {
            return priority + 2;
        }
        if (state.is(Blocks.SLIME_BLOCK)) {
            return priority + 3;
        }
        return priority + 4;
    }

    private static int pressureWeight(BlockState state) {
        return state.is(Blocks.MOVING_PISTON) || state.is(Blocks.SLIME_BLOCK) ? 2 : 1;
    }
}
