package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.interaction.StrippableBlockPort;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Owns multi-stage print workflows independently from selection scan progress.
 *
 * <p>Server updates wake only the affected target and its neighbours. A deadline remains as a
 * fallback for missed packets, so a workflow can never depend on player movement or a full scan.</p>
 */
public final class PrintWorkflowScheduler {
    private static final int MAX_UNSTARTED_TASKS = 1024;
    private static final int MAX_STARTED_TASKS = 128;
    private final LongSupplier tickClock;
    private final StrippableBlockPort strippableBlocks;
    private final Map<BlockPos, PrintTask> tasks = new LinkedHashMap<>();
    private final Set<BlockPos> unstartedTargets = new LinkedHashSet<>();
    private final LongSet startedKeys = new LongOpenHashSet();
    private final LongSet readyKeys = new LongOpenHashSet();
    private final LongSet wakeKeys = new LongOpenHashSet();
    private final LongSet materialBlockedKeys = new LongOpenHashSet();
    private final Long2LongMap materialRetryAfter = new Long2LongOpenHashMap();
    private long lastRefreshTick = Long.MIN_VALUE;

    public PrintWorkflowScheduler(LongSupplier tickClock, StrippableBlockPort strippableBlocks) {
        this.tickClock = tickClock;
        this.strippableBlocks = strippableBlocks;
    }

    public boolean hasActiveWorkflow() {
        return !this.tasks.isEmpty();
    }

    public int pendingTaskCount() { return this.tasks.size(); }

    public boolean hasReadyWorkflow() {
        return !this.readyKeys.isEmpty();
    }

    public void retainWithin(List<PrinterBox> sourceBoxes) {
        List<BlockPos> outside = new ArrayList<>();
        for (BlockPos pos : this.unstartedTargets) {
            PrintTask task = this.tasks.get(pos);
            if (task != null && !task.hasStarted()
                    && sourceBoxes.stream().noneMatch(box -> box.contains(pos))) outside.add(pos);
        }
        outside.forEach(this::remove);
    }

    /** Release idle delivery references without cancelling a queue, confirmation or native hold. */
    public List<BlockPos> retainWithin(List<PrinterBox> sourceBoxes, Predicate<BlockPos> liveOwnership) {
        List<BlockPos> outside = new ArrayList<>();
        for (PrintTask task : this.tasks.values()) {
            BlockPos pos = task.pos();
            if (sourceBoxes.stream().noneMatch(box -> box.contains(pos))
                    && !task.hasInFlightOwnership() && !liveOwnership.test(pos)) outside.add(pos);
        }
        outside.forEach(this::remove);
        return outside;
    }

    public void clear() {
        this.tasks.clear();
        this.unstartedTargets.clear();
        this.startedKeys.clear();
        this.readyKeys.clear();
        this.wakeKeys.clear();
        this.materialBlockedKeys.clear();
        this.materialRetryAfter.clear();
        this.lastRefreshTick = Long.MIN_VALUE;
    }

    public void wake(BlockPos updatedPos) {
        if (updatedPos == null || this.tasks.isEmpty()) return;
        this.wakeIfActive(updatedPos);
        for (Direction direction : Direction.values()) {
            this.wakeIfActive(updatedPos.relative(direction));
        }
    }

    private void wakeIfActive(BlockPos pos) {
        if (this.tasks.containsKey(pos)) this.wakeKeys.add(pos.asLong());
    }

    public void tick(@Nullable ClientLevel level, @Nullable WorldSchematic schematic) {
        long now = this.tickClock.getAsLong();
        if (this.lastRefreshTick == now && this.wakeKeys.isEmpty()) return;
        this.lastRefreshTick = now;
        if (level == null || schematic == null) {
            this.clear();
            return;
        }
        Iterator<PrintTask> iterator = this.tasks.values().iterator();
        while (iterator.hasNext()) {
            PrintTask task = iterator.next();
            // Deferred queue callbacks may update ownership without returning through this scheduler.
            this.trackOwnership(task);
            long key = task.pos().asLong();
            boolean materialRetryDue = this.materialBlockedKeys.contains(key)
                    && this.materialRetryAfter.getOrDefault(key, Long.MAX_VALUE) <= now;
            boolean due = this.wakeKeys.remove(key) || materialRetryDue || task.nextCheckTick() <= now;
            if (!due) continue;
            if (!task.shouldKeep(level, schematic)) {
                iterator.remove();
                this.readyKeys.remove(key);
                this.clearMaterialWait(key);
                this.unstartedTargets.remove(task.pos());
                this.startedKeys.remove(key);
                continue;
            }
            if (materialRetryDue) this.clearMaterialWait(key);
            this.updateReady(task, level, schematic);
        }
        this.trimUnstarted();
        this.wakeKeys.clear();
    }

    public List<BlockPos> readyTargetPositions() {
        if (this.readyKeys.isEmpty()) return List.of();
        List<BlockPos> positions = new ArrayList<>(this.readyKeys.size());
        for (PrintTask task : this.tasks.values()) {
            if (this.readyKeys.contains(task.pos().asLong())) positions.add(task.pos());
        }
        return positions;
    }

    public boolean isActiveTaskPos(@Nullable BlockPos pos) {
        return pos != null && this.tasks.containsKey(pos);
    }

    public boolean isBreakingTaskPos(@Nullable BlockPos pos) {
        return pos != null && this.tasks.get(pos) instanceof WaterPrintTask water && water.isBreaking();
    }

    public PrintTaskBuildResult buildAction(SchematicBlockContext context) {
        PrintTask task = this.tasks.get(context.blockPos);
        if (task == null) {
            task = PrintTasks.tryCreate(context, this.tickClock, this.strippableBlocks);
            if (task == null) return PrintTaskBuildResult.PASS;
            this.tasks.put(task.pos(), task);
            this.trackOwnership(task);
            this.trimUnstarted();
        }
        if (!task.shouldKeep(context.level, context.schematic)) {
            this.remove(task.pos());
            return PrintTaskBuildResult.PASS;
        }
        if (this.materialBlockedKeys.contains(task.pos().asLong())) {
            return PrintTaskBuildResult.SKIP;
        }
        this.trackOwnership(task);
        if (!task.hasStarted() && this.startedKeys.size() >= MAX_STARTED_TASKS) {
            // SKIP is handled: ordinary placement must not bypass workflow admission, and
            // buildAction may itself start native mining before returning an action handle.
            return PrintTaskBuildResult.SKIP;
        }
        PrintTaskBuildResult result = task.buildAction(context);
        this.trackOwnership(task);
        this.trimUnstarted();
        if (!result.hasAction()) {
            this.updateReady(task, context.level, context.schematic);
        }
        return result;
    }

    @Nullable
    public PrintTaskAction createActionHandle(SchematicBlockContext context, Action action) {
        PrintTask task = this.tasks.get(context.blockPos);
        return task == null ? null : task.createActionHandle(context, action);
    }

    public void onActionSuccess(PrintTaskAction actionHandle, SchematicBlockContext context, Action action) {
        actionHandle.onSuccess(context, action);
        this.afterAction(context);
    }

    public void onActionQueued(PrintTaskAction actionHandle, SchematicBlockContext context, Action action) {
        actionHandle.onQueued(context, action);
        this.afterAction(context);
    }

    public void onActionFailure(PrintTaskAction actionHandle, SchematicBlockContext context, Action action) {
        actionHandle.onFailure(context, action);
        this.afterAction(context);
    }

    public void onActionCancelled(PrintTaskAction actionHandle, SchematicBlockContext context, Action action) {
        actionHandle.onCancelled(context, action);
        this.afterAction(context);
    }

    /**
     * Inventory and look synchronization did not submit the stage action. Keep the exact target
     * runnable so it is retried without waiting for the selection scanner to encounter it again.
     */
    public void onActionDeferred(SchematicBlockContext context) {
        PrintTask task = this.tasks.get(context.blockPos);
        if (task == null) return;
        this.updateReady(task, context.level, context.schematic);
    }

    /** Pauses only the affected workflow, with a slow fallback for eligibility changes without a gain. */
    public void onMaterialUnavailable(SchematicBlockContext context) {
        this.onMaterialUnavailable(context, false);
    }

    public void onMaterialUnavailable(SchematicBlockContext context, boolean pending) {
        PrintTask task = this.tasks.get(context.blockPos);
        if (task == null) return;
        long key = task.pos().asLong();
        this.readyKeys.remove(key);
        this.wakeKeys.remove(key);
        this.materialBlockedKeys.add(key);
        this.materialRetryAfter.put(key, this.tickClock.getAsLong() + (pending ? 1L : 20L));
    }

    public void onInventoryAvailabilityChanged() {
        this.wakeKeys.addAll(this.materialBlockedKeys);
        this.materialBlockedKeys.clear();
        this.materialRetryAfter.clear();
    }

    private void afterAction(SchematicBlockContext context) {
        PrintTask task = this.tasks.get(context.blockPos);
        if (task == null) return;
        this.trackOwnership(task);
        this.trimUnstarted();
        this.clearMaterialWait(task.pos().asLong());
        this.readyKeys.remove(task.pos().asLong());
        this.wakeKeys.add(task.pos().asLong());
    }

    private void updateReady(PrintTask task, ClientLevel level, WorldSchematic schematic) {
        long key = task.pos().asLong();
        if (this.materialBlockedKeys.contains(key)
                || task.isWaitingForWorldUpdate(level, schematic)) this.readyKeys.remove(key);
        else this.readyKeys.add(key);
    }

    private void remove(BlockPos pos) {
        this.tasks.remove(pos);
        this.unstartedTargets.remove(pos);
        this.startedKeys.remove(pos.asLong());
        this.readyKeys.remove(pos.asLong());
        this.wakeKeys.remove(pos.asLong());
        this.clearMaterialWait(pos.asLong());
    }

    private void clearMaterialWait(long key) {
        this.materialBlockedKeys.remove(key);
        this.materialRetryAfter.remove(key);
    }

    private void trackOwnership(PrintTask task) {
        if (task.hasStarted()) {
            this.unstartedTargets.remove(task.pos());
            this.startedKeys.add(task.pos().asLong());
            return;
        }
        this.startedKeys.remove(task.pos().asLong());
        this.unstartedTargets.add(task.pos());
    }

    private void trimUnstarted() {
        while (this.unstartedTargets.size() > MAX_UNSTARTED_TASKS) {
            BlockPos oldest = this.unstartedTargets.iterator().next();
            PrintTask task = this.tasks.get(oldest);
            if (task != null && task.hasStarted()) this.trackOwnership(task);
            else this.remove(oldest);
        }
    }
}
