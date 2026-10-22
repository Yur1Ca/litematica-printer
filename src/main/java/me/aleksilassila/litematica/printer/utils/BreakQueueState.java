package me.aleksilassila.litematica.printer.utils;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Owns queued break targets and their short-lived client prediction markers. */
final class BreakQueueState {
    private final Queue<BlockPos> breakQueue = new LinkedList<>();
    private final Set<BlockPos> queuedBreaks = new HashSet<>();
    private final Map<BlockPos, Integer> recentlyBroken = new HashMap<>();
    private final Map<BlockPos, Integer> pendingBroken = new HashMap<>();
    private BlockPos activePos;
    private boolean forceDelayedDestroy;
    private int externalDestroyLockTicks;

    void add(BlockPos pos) {
        if (pos == null) return;
        BlockPos queuedPos = pos.immutable();
        if (queuedPos.equals(this.activePos)
                || this.recentlyBroken.containsKey(queuedPos)
                || this.pendingBroken.containsKey(queuedPos)
                || !this.queuedBreaks.add(queuedPos)) {
            return;
        }
        this.breakQueue.add(queuedPos);
    }

    BlockPos pollQueued() {
        BlockPos pos = this.breakQueue.poll();
        if (pos != null) {
            this.queuedBreaks.remove(pos);
        }
        return pos;
    }

    boolean hasQueued() {
        return !this.breakQueue.isEmpty();
    }

    boolean hasWork() {
        return this.activePos != null || this.hasQueued();
    }

    BlockPos activePos() {
        return this.activePos;
    }

    void activePos(BlockPos pos) {
        this.activePos = pos == null ? null : pos.immutable();
    }

    void clearActive() {
        this.activePos = null;
        this.forceDelayedDestroy = false;
    }

    void tickMarkers() {
        tickMarkerMap(this.recentlyBroken);
        tickMarkerMap(this.pendingBroken);
        if (this.externalDestroyLockTicks > 0) {
            this.externalDestroyLockTicks--;
        }
    }

    void clearIfDisabled(boolean enabled) {
        if (!enabled) this.cancelPlannedWork();
    }

    void cancelPlannedWork() {
        this.breakQueue.clear();
        this.queuedBreaks.clear();
        this.clearActive();
        this.externalDestroyLockTicks = 0;
    }

    void reset() {
        this.breakQueue.clear();
        this.queuedBreaks.clear();
        this.recentlyBroken.clear();
        this.pendingBroken.clear();
        this.activePos = null;
        this.forceDelayedDestroy = false;
        this.externalDestroyLockTicks = 0;
    }

    boolean isLocked() {
        return this.externalDestroyLockTicks > 0;
    }

    void suppress(int ticks) {
        this.externalDestroyLockTicks = Math.max(this.externalDestroyLockTicks, ticks);
    }

    void markRecentlyBroken(BlockPos pos) {
        if (pos != null) this.recentlyBroken.put(pos.immutable(), 2);
    }

    void markPendingBroken(BlockPos pos, int timeoutTicks) {
        if (pos != null) this.pendingBroken.put(pos.immutable(), Math.max(timeoutTicks, 1));
    }

    void confirmServerBlockUpdate(BlockPos pos) {
        if (pos == null) return;
        this.recentlyBroken.remove(pos);
        this.pendingBroken.remove(pos);
    }

    void clearPendingBroken(BlockPos pos) {
        if (pos != null) this.pendingBroken.remove(pos);
    }

    boolean isRecentlyBroken(BlockPos pos) {
        return pos != null && (this.recentlyBroken.containsKey(pos) || this.pendingBroken.containsKey(pos));
    }

    boolean forceDelayedDestroy() {
        return this.forceDelayedDestroy;
    }

    void forceDelayedDestroy(boolean value) {
        this.forceDelayedDestroy = value;
    }

    private static void tickMarkerMap(Map<BlockPos, Integer> markerMap) {
        if (markerMap.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Integer>> iterator = markerMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            int remainingTicks = entry.getValue() - 1;
            if (remainingTicks <= 0) iterator.remove();
            else entry.setValue(remainingTicks);
        }
    }
}
