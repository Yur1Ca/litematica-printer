package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/** Owns residue positions and late-update watches; the placer owns actual mining attempts. */
final class BedrockCleanupCoordinator {
    private static final int CLEANUP_WATCH_TICKS = 40;
    private final Minecraft client;
    private final BooleanSupplier mutationsDeferred;
    private final BedrockPlacer placer;
    private final BedrockCleanupQueue queue = new BedrockCleanupQueue();
    private final Set<BlockPos> blocked = new LinkedHashSet<>();
    private final Map<BlockPos, Long> recentAuthoritativeCleanup = new HashMap<>();
    private final NavigableMap<Long, Set<BlockPos>> watchDeadlines = new TreeMap<>();
    private final Map<BlockPos, CleanupCycle> cycles = new HashMap<>();
    private final Map<BlockPos, Set<BlockPos>> positionOwners = new HashMap<>();

    BedrockCleanupCoordinator(Minecraft client, BooleanSupplier mutationsDeferred, BedrockPlacer placer) {
        this.client = client;
        this.mutationsDeferred = mutationsDeferred;
        this.placer = placer;
    }

    void reset() {
        this.queue.clear();
        this.blocked.clear();
        this.recentAuthoritativeCleanup.clear();
        this.watchDeadlines.clear();
        this.cycles.clear();
        this.positionOwners.clear();
    }

    void beginTick() {
        this.blocked.clear();
        long now = this.currentTick();
        while (!this.watchDeadlines.isEmpty() && this.watchDeadlines.firstKey() < now) {
            var expired = this.watchDeadlines.pollFirstEntry();
            for (BlockPos pos : expired.getValue()) {
                this.recentAuthoritativeCleanup.remove(pos);
                this.releasePositionIfUnowned(pos);
            }
        }
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
    }

    /** Retain late-update ownership without crossing an inventory or interaction boundary. */
    void enqueueAuthoritative(BlockPos pos) {
        if (pos == null) return;
        this.add(pos, false);
    }

    /** Retired machines keep their slot through reachable cleanup, with shared positions queued once. */
    void enqueueCycle(BlockPos owner, Set<BlockPos> positions, boolean horizontal) {
        if (owner == null || positions == null || positions.isEmpty()) return;
        BlockPos stableOwner = owner.immutable();
        for (BlockPos pos : positions) {
            if (pos == null) continue;
            this.enqueueAuthoritative(pos);
            if (!this.queue.contains(pos) && !this.recentAuthoritativeCleanup.containsKey(pos)) continue;
            var cycle = this.cycles.computeIfAbsent(stableOwner, ignored -> new CleanupCycle());
            cycle.horizontal |= horizontal;
            BlockPos stablePos = pos.immutable();
            cycle.positions.add(stablePos);
            this.positionOwners.computeIfAbsent(stablePos, ignored -> new LinkedHashSet<>()).add(stableOwner);
        }
    }

    /** A successfully admitted machine owns future updates for its reused positions. */
    void transferOwnership(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return;
        for (BlockPos pos : positions) {
            if (pos == null || this.placer.isCleanupPendingAt(pos)) continue;
            this.queue.remove(pos);
            this.blocked.remove(pos);
            this.unwatch(pos);
            this.releasePositionIfUnowned(pos);
        }
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
        return cycle == null || this.client.level != null && cycle.positions.stream().allMatch(pos ->
                BedrockEnvironment.isChunkLoaded(this.client.level, pos)
                        && !BedrockTargetBlocks.isCleanupResidue(this.client.level.getBlockState(pos))
                        && !this.placer.isCleanupPendingAt(pos));
    }

    private boolean isReachableCycle(CleanupCycle cycle) {
        return this.client.level != null && this.client.player != null && cycle.positions.stream().anyMatch(pos ->
                this.queue.contains(pos) && BedrockEnvironment.canInteract(pos) && this.isReachableResidue(pos));
    }

    private int countReachableCycles(boolean verticalOnly) {
        ClientLevel level = this.client.level;
        if (level == null || this.client.player == null) return 0;
        Set<BlockPos> reachable = new LinkedHashSet<>();
        for (BlockPos pos : this.queue.nearby(this.client)) {
            Set<BlockPos> owners = this.positionOwners.get(pos);
            if (owners == null || !this.isReachableResidue(pos)) continue;
            for (BlockPos owner : owners) {
                if (!verticalOnly || !this.cycles.get(owner).horizontal) reachable.add(owner);
            }
        }
        return reachable.size();
    }

    private boolean isReachableResidue(BlockPos pos) {
        return !this.placer.isCleanupRetryPausedAt(pos)
                && BedrockEnvironment.isChunkLoaded(this.client.level, pos)
                && BedrockTargetBlocks.isCleanupResidue(this.client.level.getBlockState(pos));
    }

    private static final class CleanupCycle {
        private final Set<BlockPos> positions = new LinkedHashSet<>();
        private boolean horizontal;
    }

    /** A position can release its old cycles only after both work and late-update ownership end. */
    private void releasePositionIfUnowned(BlockPos pos) {
        if (this.queue.contains(pos) || this.recentAuthoritativeCleanup.containsKey(pos)) return;
        Set<BlockPos> owners = this.positionOwners.remove(pos);
        if (owners == null) return;
        for (BlockPos owner : owners) {
            CleanupCycle cycle = this.cycles.get(owner);
            cycle.positions.remove(pos);
            if (cycle.positions.isEmpty()) this.cycles.remove(owner);
        }
    }

    /** Clear current work without forgetting placements already in flight during the watch window. */
    void remove(BlockPos pos) {
        this.queue.remove(pos);
        this.blocked.remove(pos);
        this.releasePositionIfUnowned(pos);
    }

    void markBlocked(BlockPos pos) {
        if (pos != null) {
            this.blocked.add(pos);
        }
    }

    void process(Predicate<BlockPos> reserved, int limit) {
        if (this.queue.isEmpty() || limit <= 0 || this.mutationsDeferred.getAsBoolean()) {
            return;
        }
        ClientLevel level = this.client.level;
        if (level == null) return;
        var candidates = new ArrayList<CleanupCandidate>();
        for (BlockPos pos : this.queue.nearby(this.client)) {
            if (!BedrockEnvironment.isChunkLoaded(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                this.remove(pos);
                continue;
            }
            if (reserved.test(pos)) {
                this.markBlocked(pos);
                continue;
            }
            candidates.add(new CleanupCandidate(pos, this.priority(pos, state), this.queue.order(pos)));
        }
        candidates.sort(Comparator.comparingInt(CleanupCandidate::priority).thenComparingLong(CleanupCandidate::order));
        this.placer.breakCleanupBlocks(candidates.stream().map(CleanupCandidate::pos).toList(), limit);
    }

    private record CleanupCandidate(BlockPos pos, int priority, long order) { }

    int samplePressure(ClientLevel level, Predicate<BlockPos> reserved) {
        int pressure = 0;
        if (level == null) return 0;
        for (BlockPos pos : this.queue.nearby(this.client)) {
            if (!BedrockEnvironment.isChunkLoaded(level, pos) || reserved.test(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                this.remove(pos);
                continue;
            }
            if (!this.placer.isCleanupRetryPausedAt(pos)) {
                pressure += pressureWeight(state);
            }
        }
        return pressure;
    }

    void onServerBlockUpdate(BlockPos pos) {
        Long deadline = pos == null ? null : this.recentAuthoritativeCleanup.get(pos);
        if (deadline == null && !this.queue.contains(pos)) {
            return;
        }
        ClientLevel level = this.client.level;
        if (level == null) {
            return;
        }
        // An update can arrive before beginTick has pruned expired watches.
        if (deadline != null && deadline < this.currentTick()) {
            this.unwatch(pos);
            this.releasePositionIfUnowned(pos);
            if (!this.queue.contains(pos)) return;
        }
        if (!BedrockEnvironment.isChunkLoaded(level, pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
            this.remove(pos);
            return;
        }
        this.queue.add(pos.immutable());
    }

    private void watch(BlockPos pos) {
        long now = this.currentTick();
        if (now == Long.MIN_VALUE) return;
        this.unwatch(pos);
        BlockPos stable = pos.immutable();
        long deadline = now + CLEANUP_WATCH_TICKS;
        this.recentAuthoritativeCleanup.put(stable, deadline);
        this.watchDeadlines.computeIfAbsent(deadline, ignored -> new LinkedHashSet<>()).add(stable);
    }

    private void unwatch(BlockPos pos) {
        Long deadline = this.recentAuthoritativeCleanup.remove(pos);
        if (deadline == null) return;
        Set<BlockPos> bucket = this.watchDeadlines.get(deadline);
        bucket.remove(pos);
        if (bucket.isEmpty()) this.watchDeadlines.remove(deadline);
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
