package me.aleksilassila.litematica.printer.handler;

<<<<<<< HEAD
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.RttReplayController;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HudStatsManager {
    public static final HudStatsManager INSTANCE = new HudStatsManager();
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    private static final int PRINT_CONFIRM_TIMEOUT_TICKS = 80;
    private static final int FALLBACK_CONFIRM_CHECKS_PER_MODE = 8;

    private final EnumMap<Mode, ModeStats> stats = new EnumMap<>(Mode.class);
    private final Map<BlockPos, PendingBlockState> pendingPrintStates = new LinkedHashMap<>();
    private final Map<BlockPos, Long> pendingMineTargets = new LinkedHashMap<>();
    private final Map<BlockPos, PendingStateChange> pendingFillTargets = new LinkedHashMap<>();
    private final Map<BlockPos, PendingStateChange> pendingFluidTargets = new LinkedHashMap<>();
    private long lastFallbackFlushTick = Long.MIN_VALUE;

    private HudStatsManager() {
        for (Mode mode : Mode.values()) {
            this.stats.put(mode, new ModeStats());
        }
    }

    public void resetAll() {
        this.pendingPrintStates.clear();
        this.pendingMineTargets.clear();
        this.pendingFillTargets.clear();
        this.pendingFluidTargets.clear();
        for (Mode mode : Mode.values()) {
            this.resetMode(mode);
        }
    }

    public void resetMode(Mode mode) {
        switch (mode) {
            case PRINT -> this.pendingPrintStates.clear();
            case MINE -> this.pendingMineTargets.clear();
            case FILL -> this.pendingFillTargets.clear();
            case FLUID -> this.pendingFluidTargets.clear();
            default -> {
            }
        }
        this.stats.get(mode).reset();
    }

    public void recordRateUnit(Mode mode, int count) {
        if (count <= 0) {
            return;
        }
        this.stats.get(mode).recordRateUnit(count);
    }

    public void recordFailure(Mode mode, String reason) {
        this.stats.get(mode).recordFailure(reason);
    }

    public void recordDeferred(Mode mode, String reason) {
        this.stats.get(mode).recordDeferred(reason);
    }

    public void recordStatus(Mode mode, String reason) {
        this.stats.get(mode).recordStatus(reason);
    }

    public void trackExpectedBlockState(Mode mode, BlockPos pos, BlockState expectedState) {
        if (mode != Mode.PRINT || pos == null || expectedState == null) {
            return;
        }
<<<<<<< HEAD
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        this.pendingPrintStates.put(
                pos.immutable(),
                new PendingBlockState(expectedState, now, now + PRINT_CONFIRM_TIMEOUT_TICKS)
        );
    }

    public boolean isPrintPlacementPending(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        PendingBlockState pending = this.pendingPrintStates.get(pos);
        if (pending == null) {
            return false;
        }
        if (ClientPlayerTickManager.getCurrentHandlerTime() > pending.expireTick()) {
            this.pendingPrintStates.remove(pos);
            return false;
        }
        return true;
=======
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.level.getBlockState(pos).equals(expectedState)) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        this.pendingPrintStates.put(pos.immutable(), new PendingBlockState(expectedState, now + PRINT_CONFIRM_TIMEOUT_TICKS));
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    public void trackExpectedMineClear(Mode mode, BlockPos pos) {
        if (mode != Mode.MINE || pos == null) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        this.pendingMineTargets.put(pos.immutable(), now + PRINT_CONFIRM_TIMEOUT_TICKS);
    }

    public void trackExpectedBlockChange(Mode mode, BlockPos pos, BlockState originalState) {
        if (pos == null || originalState == null) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        PendingStateChange pending = new PendingStateChange(originalState, now + PRINT_CONFIRM_TIMEOUT_TICKS);
        if (mode == Mode.FILL) {
            this.pendingFillTargets.put(pos.immutable(), pending);
        } else if (mode == Mode.FLUID) {
            this.pendingFluidTargets.put(pos.immutable(), pending);
        }
    }

    public void tick() {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (this.lastFallbackFlushTick == now) {
            return;
        }
        this.lastFallbackFlushTick = now;
        this.flushConfirmedActions(now, FALLBACK_CONFIRM_CHECKS_PER_MODE);
    }

    public void confirmBlockUpdate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        BlockState currentState = client.level.getBlockState(pos);

<<<<<<< HEAD
        PendingBlockState printPending = this.pendingPrintStates.remove(pos);
        if (printPending != null && currentState.equals(printPending.expectedState())) {
=======
        PendingBlockState printPending = this.pendingPrintStates.get(pos);
        if (printPending != null && currentState.equals(printPending.expectedState())) {
            this.pendingPrintStates.remove(pos);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            this.stats.get(Mode.PRINT).recordConfirmedUnit(now, 1);
        }

        if (currentState.isAir() && this.pendingMineTargets.remove(pos) != null) {
            this.stats.get(Mode.MINE).recordConfirmedUnit(now, 1);
        }

        this.confirmStateChange(now, Mode.FILL, pos, currentState, this.pendingFillTargets);
        this.confirmStateChange(now, Mode.FLUID, pos, currentState, this.pendingFluidTargets);
    }

    public Snapshot snapshot(Mode mode) {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        return this.stats.get(mode).snapshot(now);
    }

    private void confirmStateChange(
            long now,
            Mode mode,
            BlockPos pos,
            BlockState currentState,
            Map<BlockPos, PendingStateChange> pendingTargets
    ) {
        PendingStateChange pending = pendingTargets.get(pos);
        if (pending != null && !currentState.equals(pending.originalState())) {
            pendingTargets.remove(pos);
            this.stats.get(mode).recordConfirmedUnit(now, 1);
        }
    }

    private void flushConfirmedActions(long now, int maxChecksPerMode) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        flushConfirmedPrintPlacements(client, now, maxChecksPerMode);
        flushConfirmedMineClears(client, now, maxChecksPerMode);
        flushConfirmedBlockChanges(client, now, Mode.FILL, this.pendingFillTargets, maxChecksPerMode);
        flushConfirmedBlockChanges(client, now, Mode.FLUID, this.pendingFluidTargets, maxChecksPerMode);
    }

    private void flushConfirmedPrintPlacements(Minecraft client, long now, int maxChecks) {
<<<<<<< HEAD
        int confirmationFloorTicks = this.getPrintConfirmationFloorTicks();
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        for (int checked = 0; checked < maxChecks && !this.pendingPrintStates.isEmpty(); checked++) {
            Iterator<Map.Entry<BlockPos, PendingBlockState>> iterator = this.pendingPrintStates.entrySet().iterator();
            Map.Entry<BlockPos, PendingBlockState> entry = iterator.next();
            BlockPos pos = entry.getKey();
            PendingBlockState pending = entry.getValue();
            iterator.remove();
            if (now > pending.expireTick()) {
                continue;
            }
<<<<<<< HEAD
            if (now - pending.sentTick() < confirmationFloorTicks) {
                this.pendingPrintStates.put(pos, pending);
                continue;
            }
            if (client.level.getBlockState(pos).equals(pending.expectedState())) {
                this.stats.get(Mode.PRINT).recordConfirmedUnit(now, 1);
=======
            if (client.level.getBlockState(pos).equals(pending.expectedState())) {
                this.stats.get(Mode.PRINT).recordConfirmedUnit(now, 1);
            } else {
                this.pendingPrintStates.put(pos, pending);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
        }
    }

<<<<<<< HEAD
    private int getPrintConfirmationFloorTicks() {
        int safetyPercent = Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()
                ? Configs.Placement.RTT_SAFETY_PERCENT.getIntegerValue()
                : 100;
        return Math.max(
                2,
                RttReplayController.INSTANCE.getExtraIntervalTicks(safetyPercent)
        );
    }

=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    private void flushConfirmedMineClears(Minecraft client, long now, int maxChecks) {
        for (int checked = 0; checked < maxChecks && !this.pendingMineTargets.isEmpty(); checked++) {
            Iterator<Map.Entry<BlockPos, Long>> iterator = this.pendingMineTargets.entrySet().iterator();
            Map.Entry<BlockPos, Long> entry = iterator.next();
            BlockPos pos = entry.getKey();
            long expireTick = entry.getValue();
            iterator.remove();
            if (now > expireTick) {
                continue;
            }
            if (client.level.getBlockState(pos).isAir()) {
                this.stats.get(Mode.MINE).recordConfirmedUnit(now, 1);
            } else {
                this.pendingMineTargets.put(pos, expireTick);
            }
        }
    }

    private void flushConfirmedBlockChanges(
            Minecraft client,
            long now,
            Mode mode,
            Map<BlockPos, PendingStateChange> pendingTargets,
            int maxChecks
    ) {
        for (int checked = 0; checked < maxChecks && !pendingTargets.isEmpty(); checked++) {
            Iterator<Map.Entry<BlockPos, PendingStateChange>> iterator = pendingTargets.entrySet().iterator();
            Map.Entry<BlockPos, PendingStateChange> entry = iterator.next();
            BlockPos pos = entry.getKey();
            PendingStateChange pending = entry.getValue();
            iterator.remove();
            if (now > pending.expireTick()) {
                continue;
            }
            if (!client.level.getBlockState(pos).equals(pending.originalState())) {
                this.stats.get(mode).recordConfirmedUnit(now, 1);
            } else {
                pendingTargets.put(pos, pending);
            }
        }
    }

    public enum Mode {
        TOTAL,
        PRINT,
        MINE,
        FILL,
        FLUID,
        BEDROCK
    }

    public record Snapshot(
            long finished,
            long total,
            double progress,
            double ratePerSecond,
            double completedRatePerSecond,
            double failuresPerSecond,
            double deferredPerSecond,
            long lifetimeUnits,
            long lifetimeFailures,
            long lifetimeDeferred,
            String lastReason
    ) {
    }

    private static final class ModeStats {
        private final RollingCounter rateCounter = new RollingCounter();
        private final RollingCounter completedCounter = new RollingCounter();
        private final RollingCounter failureCounter = new RollingCounter();
        private final RollingCounter deferredCounter = new RollingCounter();

        private long finished;
        private long total;
        private double progress;
        private long lifetimeUnits;
        private long lifetimeFailures;
        private long lifetimeDeferred;
        private String lastReason = "空闲";

        private void reset() {
            this.finished = 0;
            this.total = 0;
            this.progress = 0.0D;
            this.lifetimeUnits = 0;
            this.lifetimeFailures = 0;
            this.lifetimeDeferred = 0;
            this.lastReason = "空闲";
            this.rateCounter.reset();
            this.completedCounter.reset();
            this.failureCounter.reset();
            this.deferredCounter.reset();
        }

        private void recordRateUnit(int count) {
            this.total += count;
            this.rateCounter.add(count);
            this.lifetimeUnits += count;
            this.updateProgress();
            this.lastReason = "运行中";
        }

        private void recordConfirmedUnit(long tick, int count) {
            this.finished += count;
            this.completedCounter.add(count);
            this.updateProgress();
            this.lastReason = "运行中";
        }

        private void recordFailure(String reason) {
            this.failureCounter.add(1);
            this.lifetimeFailures++;
            this.lastReason = normalizeReason(reason);
        }

        private void recordDeferred(String reason) {
            this.deferredCounter.add(1);
            this.lifetimeDeferred++;
            this.lastReason = normalizeReason(reason);
        }

        private void recordStatus(String reason) {
            this.lastReason = normalizeReason(reason);
        }

        private Snapshot snapshot(long now) {
            return new Snapshot(
                    this.finished,
                    this.total,
                    this.progress,
                    this.rateCounter.sumRecent(),
                    this.completedCounter.sumRecent(),
                    this.failureCounter.sumRecent(),
                    this.deferredCounter.sumRecent(),
                    this.lifetimeUnits,
                    this.lifetimeFailures,
                    this.lifetimeDeferred,
                    this.lastReason
            );
        }

        private void updateProgress() {
            this.progress = this.total > 0 ? (double) this.finished / (double) this.total : 0.0D;
        }

        private static String normalizeReason(String reason) {
            return reason == null || reason.isBlank() ? "运行中" : reason;
        }
    }

<<<<<<< HEAD
    private record PendingBlockState(BlockState expectedState, long sentTick, long expireTick) {
=======
    private record PendingBlockState(BlockState expectedState, long expireTick) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    private record PendingStateChange(BlockState originalState, long expireTick) {
    }

    private static final class RollingCounter {
        private static final int MAX_EVENTS = 32768;
        private final long[] timestamps = new long[MAX_EVENTS];
        private final int[] values = new int[MAX_EVENTS];
        private int head;
        private int size;
        private int total;

        private RollingCounter() {
            this.reset();
        }

        private void reset() {
            this.head = 0;
            this.size = 0;
            this.total = 0;
        }

        private void add(int delta) {
            long now = System.nanoTime();
            this.discardExpired(now);
            if (this.size >= MAX_EVENTS) {
                this.discardOldest();
            }
            int index = (this.head + this.size) % MAX_EVENTS;
            this.timestamps[index] = now;
            this.values[index] = delta;
            this.size++;
            this.total += delta;
        }

        private double sumRecent() {
            this.discardExpired(System.nanoTime());
            return this.total;
        }

        private void discardExpired(long now) {
            while (this.size > 0) {
                long timestamp = this.timestamps[this.head];
                if (now - timestamp < RATE_WINDOW_NANOS) {
                    break;
                }
                this.discardOldest();
            }
        }

        private void discardOldest() {
            if (this.size <= 0) {
                return;
            }
            this.total -= this.values[this.head];
            this.timestamps[this.head] = 0L;
            this.values[this.head] = 0;
            this.head = (this.head + 1) % MAX_EVENTS;
            this.size--;
        }
    }
}
