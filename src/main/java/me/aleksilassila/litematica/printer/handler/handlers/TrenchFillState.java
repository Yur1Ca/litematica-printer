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

/** Target, retry and rate history for mine trench filling and breaking. Minecraft interactions stay in MineHandler. */
final class TrenchFillState {
    static final int MAX_TARGETS = 1024;
    private static final int MAX_COLD_REPLACEMENTS_PER_TICK = 64;
    private final Set<BlockPos> fillTargets = new LinkedHashSet<>();
    private final Set<BlockPos> breakTargets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> inFlight = new HashMap<>();
    private final Map<BlockPos, Object> queuedAttempts = new HashMap<>();
    private final Map<BlockPos, Long> retryAt = new HashMap<>();
    private final Set<BlockPos> materialWaiting = new LinkedHashSet<>();
    private List<PrinterBox> scopeBoxes = List.of();
    private int sentThisTick;
    private long budgetTick = Long.MIN_VALUE;
    private long lastSentTick = Long.MIN_VALUE;
    private long replacementTick = Long.MIN_VALUE;
    private int replacedThisTick;

    void clear() {
        this.fillTargets.clear();
        this.breakTargets.clear();
        this.inFlight.clear();
        this.queuedAttempts.clear();
        this.retryAt.clear();
        this.materialWaiting.clear();
        this.scopeBoxes = List.of();
        this.sentThisTick = 0;
        this.budgetTick = Long.MIN_VALUE;
        this.lastSentTick = Long.MIN_VALUE;
        this.replacementTick = Long.MIN_VALUE;
        this.replacedThisTick = 0;
    }

    void beginTick(long now) {
        if (this.budgetTick != now) {
            this.budgetTick = now;
            this.sentThisTick = 0;
        }
    }

    boolean hasWork() {
        return !this.fillTargets.isEmpty() || !this.breakTargets.isEmpty() || !this.inFlight.isEmpty();
    }

    int targetCount() {
        return this.fillTargets.size() + this.breakTargets.size();
    }

    int remainingCapacity() { return Math.max(0, MAX_TARGETS - this.targetCount()); }

    /** Reserves no ownership: cold targets stay retained until a replacement actually arrives. */
    int discoveryAllowance(long now, Predicate<BlockPos> reachable, Predicate<BlockPos> protectedTarget) {
        this.beginReplacementTick(now);
        int free = this.remainingCapacity();
        if (free > 0) return free;
        return this.coldTargets(reachable, protectedTarget, now).size();
    }

    private void beginReplacementTick(long now) {
        if (this.replacementTick != now) {
            this.replacementTick = now;
            this.replacedThisTick = 0;
        }
    }

    private List<BlockPos> coldTargets(Predicate<BlockPos> reachable, Predicate<BlockPos> protectedTarget, long now) {
        int limit = MAX_COLD_REPLACEMENTS_PER_TICK - this.replacedThisTick;
        List<BlockPos> cold = new ArrayList<>();
        if (limit <= 0) return cold;
        for (BlockPos pos : this.fillTargets) {
            if (this.inFlight.containsKey(pos) || this.queuedAttempts.containsKey(pos) || protectedTarget.test(pos)) continue;
            if (!reachable.test(pos) || this.retryAt.getOrDefault(pos, Long.MIN_VALUE) > now
                    || this.materialWaiting.contains(pos)) {
                cold.add(pos);
                if (cold.size() == limit) return cold;
            }
        }
        for (BlockPos pos : this.breakTargets) {
            if (!this.inFlight.containsKey(pos) && !this.queuedAttempts.containsKey(pos)
                    && !protectedTarget.test(pos) && !reachable.test(pos)) {
                cold.add(pos);
                if (cold.size() == limit) return cold;
            }
        }
        return cold;
    }

    /** Refreshes the bounded retained set, without treating an unfinished source slice as complete. */
    void refreshScope(List<PrinterBox> sourceBoxes, long now, Predicate<BlockPos> isFluid,
                      Predicate<BlockPos> isTrenchBreakTarget) {
        if (!sourceBoxes.equals(this.scopeBoxes)) {
            this.scopeBoxes = List.copyOf(sourceBoxes);
            this.fillTargets.removeIf(pos -> !this.inFlight.containsKey(pos));
            this.breakTargets.clear();
            this.retryAt.clear();
            this.materialWaiting.clear();
        }
        this.fillTargets.removeIf(pos -> !this.inFlight.containsKey(pos)
                && (!this.containsScope(pos) || !isFluid.test(pos)));
        this.breakTargets.removeIf(pos -> !this.containsScope(pos) || !isTrenchBreakTarget.test(pos));
        this.inFlight.entrySet().removeIf(entry -> !this.queuedAttempts.containsKey(entry.getKey())
                && (!isFluid.test(entry.getKey()) || now - entry.getValue() > 40L));
        this.retryAt.entrySet().removeIf(entry -> !this.fillTargets.contains(entry.getKey()) || entry.getValue() <= now);
        this.materialWaiting.removeIf(pos -> !this.retryAt.containsKey(pos));
    }

    boolean retains(BlockPos pos) {
        return this.fillTargets.contains(pos) || this.breakTargets.contains(pos);
    }

    void retainBatch(Set<BlockPos> discovered, Set<BlockPos> breakTargets) {
        for (BlockPos pos : breakTargets) {
            if (this.remainingCapacity() == 0) break;
            this.breakTargets.add(pos.immutable());
        }
        discovered.stream().sorted(Comparator.comparingInt(BlockPos::getY)).forEach(pos -> {
            if (this.remainingCapacity() > 0) this.fillTargets.add(pos.immutable());
        });
        List<BlockPos> sorted = new ArrayList<>(this.fillTargets);
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        this.fillTargets.clear();
        this.fillTargets.addAll(sorted);
    }

    void retainBatch(Set<BlockPos> discovered, Set<BlockPos> breakTargets, long now,
                     Predicate<BlockPos> reachable, Predicate<BlockPos> protectedTarget) {
        this.beginReplacementTick(now);
        Set<BlockPos> incoming = new LinkedHashSet<>(discovered);
        incoming.addAll(breakTargets);
        incoming.removeIf(this::retains);
        int needed = Math.max(0, incoming.size() - this.remainingCapacity());
        if (needed > 0) {
            for (BlockPos pos : this.coldTargets(reachable, protectedTarget, now)) {
                if (needed-- == 0) break;
                this.removeFillTarget(pos);
                this.removeBreakTarget(pos);
                this.replacedThisTick++;
            }
        }
        this.retainBatch(discovered, breakTargets);
    }

    private boolean containsScope(BlockPos pos) {
        for (PrinterBox box : this.scopeBoxes) {
            if (box.contains(pos) || box.contains(pos.above()) || box.contains(pos.north())
                    || box.contains(pos.south()) || box.contains(pos.east()) || box.contains(pos.west())) return true;
        }
        return false;
    }

    boolean hasFillTarget(BlockPos pos) {
        return this.fillTargets.contains(pos);
    }

    boolean hasBreakTarget(BlockPos pos) {
        return this.breakTargets.contains(pos);
    }

    boolean hasBreakTargets() {
        return !this.breakTargets.isEmpty();
    }

    boolean canAttempt(BlockPos pos, long now) {
        return !this.inFlight.containsKey(pos) && this.retryAt.getOrDefault(pos, Long.MIN_VALUE) <= now;
    }

    List<BlockPos> readyBreakTargets(Predicate<BlockPos> reachable) {
        List<BlockPos> ready = new ArrayList<>();
        for (BlockPos pos : this.breakTargets) {
            if (reachable.test(pos)) ready.add(pos);
        }
        return ready;
    }

    BlockPos peekPriorityTarget(long now, Predicate<BlockPos> reachable) {
        for (BlockPos pos : this.breakTargets) {
            if (reachable.test(pos)) return pos;
        }
        for (BlockPos pos : this.fillTargets) {
            if (this.canAttempt(pos, now) && reachable.test(pos)) return pos;
        }
        return null;
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
                       Set<BlockPos> breakTargets, long now, Predicate<BlockPos> isFluid) {
        if (!sourceBoxes.equals(this.scopeBoxes)) {
            this.scopeBoxes = List.copyOf(sourceBoxes);
            this.fillTargets.removeIf(pos -> !this.inFlight.containsKey(pos));
            this.breakTargets.clear();
            this.retryAt.clear();
            this.materialWaiting.clear();
        }
        this.breakTargets.removeIf(pos -> !breakTargets.contains(pos));
        this.breakTargets.addAll(breakTargets);
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

    void removeBreakTarget(BlockPos pos) {
        this.breakTargets.remove(pos);
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
