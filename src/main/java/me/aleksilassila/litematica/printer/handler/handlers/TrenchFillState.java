package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Target, retry and rate history for mine trench filling. Minecraft interactions stay in MineHandler. */
final class TrenchFillState {
    private final Set<BlockPos> fillTargets = new LinkedHashSet<>();
    private final Set<BlockPos> waterloggedTargets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> inFlight = new HashMap<>();
    private final Map<BlockPos, Object> queuedAttempts = new HashMap<>();
    private final Map<BlockPos, Long> retryAt = new HashMap<>();
    private final Set<BlockPos> materialWaiting = new LinkedHashSet<>();
    private List<PrinterBox> scopeBoxes = List.of();
    private int sentThisTick;
    private long budgetTick = Long.MIN_VALUE;
    private long lastSentTick = Long.MIN_VALUE;

    void clear() {
        this.fillTargets.clear();
        this.waterloggedTargets.clear();
        this.inFlight.clear();
        this.queuedAttempts.clear();
        this.retryAt.clear();
        this.materialWaiting.clear();
        this.scopeBoxes = List.of();
        this.sentThisTick = 0;
        this.budgetTick = Long.MIN_VALUE;
        this.lastSentTick = Long.MIN_VALUE;
    }

    void beginTick(long now) {
        if (this.budgetTick != now) {
            this.budgetTick = now;
            this.sentThisTick = 0;
        }
    }

    boolean hasWork() {
        return !this.fillTargets.isEmpty() || !this.waterloggedTargets.isEmpty() || !this.inFlight.isEmpty();
    }

    int targetCount() {
        return this.fillTargets.size() + this.waterloggedTargets.size();
    }

    boolean hasFillTarget(BlockPos pos) {
        return this.fillTargets.contains(pos);
    }

    boolean hasWaterloggedTarget(BlockPos pos) {
        return this.waterloggedTargets.contains(pos);
    }

    boolean hasWaterloggedTargets() {
        return !this.waterloggedTargets.isEmpty();
    }

    boolean canAttempt(BlockPos pos, long now) {
        return !this.inFlight.containsKey(pos) && this.retryAt.getOrDefault(pos, Long.MIN_VALUE) <= now;
    }

    List<BlockPos> readyWaterloggedTargets(Predicate<BlockPos> reachable) {
        List<BlockPos> ready = new ArrayList<>();
        for (BlockPos pos : this.waterloggedTargets) {
            if (reachable.test(pos)) ready.add(pos);
        }
        return ready;
    }

    List<BlockPos> readyFillTargets(long now, Predicate<BlockPos> isFluid,
                                    Predicate<BlockPos> reachable) {
        List<BlockPos> ready = new ArrayList<>();
        for (BlockPos pos : this.fillTargets) {
            if (this.canAttempt(pos, now) && isFluid.test(pos) && reachable.test(pos)) ready.add(pos);
        }
        return ready;
    }

    boolean canSend(long now, int interval, int limit) {
        this.beginTick(now);
        if (limit > 0 && this.sentThisTick >= limit) return false;
        if (this.sentThisTick > 0) return true;
        return interval <= 0 || this.lastSentTick == Long.MIN_VALUE
                || now - this.lastSentTick >= interval;
    }

    void updateTargets(List<PrinterBox> sourceBoxes, Set<BlockPos> discovered,
                       Set<BlockPos> waterlogged, long now, Predicate<BlockPos> isFluid) {
        if (!sourceBoxes.equals(this.scopeBoxes)) {
            this.scopeBoxes = List.copyOf(sourceBoxes);
            this.fillTargets.removeIf(pos -> !this.inFlight.containsKey(pos));
            this.waterloggedTargets.clear();
            this.retryAt.clear();
            this.materialWaiting.clear();
        }
        this.waterloggedTargets.removeIf(pos -> !waterlogged.contains(pos));
        this.waterloggedTargets.addAll(waterlogged);
        this.fillTargets.removeIf(pos -> !discovered.contains(pos) && !this.inFlight.containsKey(pos));
        discovered.stream().sorted(Comparator.comparingInt(BlockPos::getY)).forEach(this.fillTargets::add);
        this.inFlight.entrySet().removeIf(entry -> !this.queuedAttempts.containsKey(entry.getKey())
                && (!isFluid.test(entry.getKey()) || now - entry.getValue() > 40L));
        this.retryAt.entrySet().removeIf(entry -> !this.fillTargets.contains(entry.getKey()) || entry.getValue() <= now);
        this.materialWaiting.removeIf(pos -> !this.retryAt.containsKey(pos));
    }

    void onBlockUpdated(BlockPos pos, boolean stillFluid) {
        // An update before dispatch (including local prediction) cannot complete a queued click.
        if (this.queuedAttempts.containsKey(pos)) return;
        if (this.inFlight.remove(pos) != null && !stillFluid) this.fillTargets.remove(pos);
        this.retryAt.remove(pos);
        this.materialWaiting.remove(pos);
    }

    void removeFillTarget(BlockPos pos) {
        this.fillTargets.remove(pos);
        this.retryAt.remove(pos);
        this.materialWaiting.remove(pos);
    }

    void removeWaterloggedTarget(BlockPos pos) {
        this.waterloggedTargets.remove(pos);
    }

    void defer(BlockPos pos, long retryTick) {
        this.retryAt.put(pos.immutable(), retryTick);
        this.materialWaiting.remove(pos);
    }

    void deferForMaterial(BlockPos pos, long retryTick) {
        this.defer(pos, retryTick);
        this.materialWaiting.add(pos.immutable());
    }

    void onMaterialsAvailable() {
        this.materialWaiting.forEach(this.retryAt::remove);
        this.materialWaiting.clear();
    }

    Object reserveQueued(BlockPos pos, long now) {
        Object attempt = new Object();
        this.queuedAttempts.put(pos.immutable(), attempt);
        this.inFlight.put(pos.immutable(), now);
        this.retryAt.remove(pos);
        this.materialWaiting.remove(pos);
        return attempt;
    }

    void completeQueued(BlockPos pos, Object attempt, long now, boolean sent) {
        if (attempt == null || this.queuedAttempts.get(pos) != attempt) return;
        this.queuedAttempts.remove(pos);
        if (sent) {
            this.markInFlight(pos, now, true);
        } else {
            this.inFlight.remove(pos);
            this.defer(pos, now + 2L);
        }
    }

    void markInFlight(BlockPos pos, long now, boolean sent) {
        this.queuedAttempts.remove(pos);
        this.inFlight.put(pos.immutable(), now);
        if (sent) {
            this.beginTick(now);
            this.sentThisTick++;
            this.lastSentTick = now;
        }
    }

    int sentThisTick() {
        return this.sentThisTick;
    }

    long lastSentTick() {
        return this.lastSentTick;
    }
}
