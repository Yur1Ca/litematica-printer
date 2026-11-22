package me.aleksilassila.litematica.printer.integration.containersync;

import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Advance from server-confirmed inventory states, never from local menu prediction. */
public final class ContainerSyncTransfer {
    public interface Port {
        Object context();
        boolean acquire();
        void release();
        void click(int slot, int button);
        void refresh();
        boolean unchanged(List<ItemStack> items, ItemStack cursor);
        /** Keep the original count as a conservative limit unless the port knows the slot rules. */
        default int recoveryCapacity(int slot, ItemStack stack, int originalCount) { return originalCount; }
    }
    /** Only container synchronization opts into batching. Other inventory owners confirm each click. */
    public interface BatchPort extends Port {
        boolean canSendBatch(List<ContainerSyncPlan.Step> steps);
        BooleanSupplier sendBatch(List<ContainerSyncPlan.Step> steps);
    }
    private static final int MAX_BATCH_CLICKS = 16;
    private final Port port;
    private final BatchPort batchPort;
    private final Object context;
    private final ContainerSyncPlan plan;
    private int next;
    private boolean pending, cancelled, finished, waiting;
    private long sentAt;
    private ItemStack cursor = ItemStack.EMPTY;
    private List<ItemStack> unexpected;
    private ItemStack unexpectedCursor;
    private ContainerSyncPlan.Step recovery;
    private boolean cancellationRecovery;
    private int returnLimit;
    private String problem;
    private int batchEnd;
    private BooleanSupplier batchFence;
    private boolean manualClickSent;

    public ContainerSyncTransfer(Port port, ContainerSyncPlan plan) {
        this.port = port;
        this.batchPort = port instanceof BatchPort batch ? batch : null;
        this.context = port.context();
        this.plan = plan;
        if (plan.problem() != null) { this.problem = plan.problem().name(); this.finished = true; }
    }

    public void cancel() { cancelled = true; }
    public boolean finished() { return finished; }
    public boolean completed() { return finished && problem == null && next == plan.steps().size(); }
    public String problem() { return problem; }
    public boolean needsAttention() { return "CURSOR_NEEDS_ATTENTION".equals(problem); }
    public boolean waitingForServer() { return waiting; }

    boolean blockManualClick(boolean consume) {
        if (!needsAttention()) return true;
        if (batchPort == null) return false;
        if (manualClickSent || batchFence != null) return true;
        // Called before vanilla sends the allowed click. Queue its confirmation on the next tick.
        if (consume) manualClickSent = true;
        return false;
    }

    public static boolean newerRevision(int received, int previous) {
        int delta = (received - previous) & 32767;
        return delta > 0 && delta < 16384;
    }

    public void tick(long now, boolean enabled) {
        if (finished) return;
        if (context == null || port.context() != context) { finish("CONTEXT_CHANGED"); return; }
        if (!enabled) cancel();
        if (!port.acquire()) return;
        if (manualClickSent) {
            manualClickSent = false;
            submitBatch(List.of(), now);
        }
        if (batchFence != null) {
            if (unexpected == null || !batchFence.getAsBoolean()) {
                waiting = now - sentAt >= 5000;
                return;
            }
            confirmBatch();
            if (finished || needsAttention()) return;
        }
        if (pending) {
            if (now - sentAt >= 5000) {
                waiting = true;
                if (unexpected != null) {
                    if (unexpectedCursor.isEmpty()) { finish("SERVER_CHANGED"); return; }
                    // Return only our own stack, bounded by the original source count.
                    var step = plan.steps().get(next);
                    ItemStack owned = next == 0 ? step.cursor() : plan.steps().get(next - 1).cursor();
                    int back = step.returnSlot();
                    if (recovery == null && !needsAttention() && !owned.isEmpty() && ContainerSyncPlan.same(owned, unexpectedCursor)
                            && unexpectedCursor.getCount() <= owned.getCount()) {
                        recovery = recoveryStep(unexpected, back, unexpectedCursor);
                        if (recovery != null) {
                            unexpected = null;
                            port.click(back, 0);
                        } else problem = "CURSOR_NEEDS_ATTENTION";
                    } else problem = "CURSOR_NEEDS_ATTENTION";
                }
                // A timeout is not permission to repeat the original click.
                sentAt = now;
                if (needsAttention()) port.refresh();
            }
            return;
        }
        if (next == plan.steps().size()) {
            var expected = next == 0 ? plan.initial() : plan.steps().get(next - 1).items();
            finish(port.unchanged(expected, ItemStack.EMPTY) ? null : "SERVER_CHANGED");
            return;
        }
        if (cancelled && cursor.isEmpty()) { finish(null); return; }
        var before = next == 0 ? plan.initial() : plan.steps().get(next - 1).items();
        if (!port.unchanged(before, cursor)) {
            // Do not execute an old plan after an intervening slot update. Ask for a fresh snapshot.
            pending = true;
            cancelled = true;
            sentAt = now;
            if (batchPort == null) port.refresh();
            else submitBatch(List.of(), now);
            return;
        }
        if (cancelled && !cursor.isEmpty()) {
            int back = plan.steps().get(next - 1).returnSlot();
            recovery = recoveryStep(before, back, cursor);
            if (recovery == null || (batchPort != null && !batchPort.canSendBatch(List.of(recovery)))) {
                recovery = null;
                problem = "CURSOR_NEEDS_ATTENTION";
                pending = true; unexpected = null; sentAt = now;
                port.refresh();
                return;
            }
            cancellationRecovery = true;
            pending = true; unexpected = null; sentAt = now;
            if (batchPort != null) submitBatch(List.of(recovery), now);
            else port.click(back, 0);
            return;
        }
        if (batchPort != null) {
            sendNextBatch(before, now);
            return;
        }
        var step = plan.steps().get(next);
        if (cursor.isEmpty()) returnLimit = before.get(step.returnSlot()).getCount();
        pending = true;
        unexpected = null;
        sentAt = now;
        port.click(step.slot(), step.button());
        me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log(
                "container-sync click step=" + next + " slot=" + step.slot() + " button=" + step.button());
    }

    public void observe(Object context, List<ItemStack> items, ItemStack carried) {
        if (finished || !pending || manualClickSent || this.context != context || port.context() != context) return;
        if (batchFence != null) {
            // Vanilla can send corrections mid-batch. Keep only the latest full snapshot
            // and let the ordered marker prove every submitted click has been processed.
            unexpected = ContainerSyncPlan.copy(items);
            unexpectedCursor = carried.copy();
            return;
        }
        if (needsAttention()) {
            // Once handed back to the user, never resume a partially matching automatic plan.
            if (carried.isEmpty()) finish("SERVER_CHANGED");
            return;
        }
        var expected = recovery == null ? plan.steps().get(next) : recovery;
        if (ContainerSyncPlan.matches(expected.items(), items) && ItemStack.matches(expected.cursor(), carried)) {
            cursor = carried.copy();
            waiting = false;
            pending = false;
            unexpected = null;
            if (recovery != null) finish(cancellationRecovery ? null : "SERVER_CHANGED");
            else {
                me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics.log(
                        "container-sync confirmed step=" + next);
                next++;
            }
        } else if (items.size() == expected.items().size()) {
            unexpected = ContainerSyncPlan.copy(items);
            unexpectedCursor = carried.copy();
        }
    }

    private void sendNextBatch(List<ItemStack> before, long now) {
        batchEnd = Math.min(next + MAX_BATCH_CLICKS, plan.steps().size());
        // Prefer completed moves, so cancellation typically finds an empty cursor.
        for (int end = batchEnd; end > next; end--) {
            if (plan.steps().get(end - 1).cursor().isEmpty()) { batchEnd = end; break; }
        }
        var steps = plan.steps().subList(next, batchEnd);
        if (!batchPort.canSendBatch(steps)) {
            // The background UI lets the user select a different hotbar slot between batches.
            // A previously confirmed cursor may still need one return to its original source.
            batchEnd = next;
            cancelled = true;
            problem = "SERVER_CHANGED";
            if (cursor.isEmpty()) finish(problem);
            return;
        }
        // If a batch has to split a single long move, preserve its original source limit
        // across successive batches for a possible cancellation return.
        if (cursor.isEmpty()) returnLimit = before.get(plan.steps().get(next).returnSlot()).getCount();
        submitBatch(steps, now);
    }

    private void submitBatch(List<ContainerSyncPlan.Step> steps, long now) {
        pending = true;
        sentAt = now;
        unexpected = null;
        batchFence = batchPort.sendBatch(steps);
    }

    private void confirmBatch() {
        batchFence = null;
        waiting = false;
        var received = unexpected;
        unexpected = null;
        if (needsAttention()) {
            if (unexpectedCursor.isEmpty()) finish("SERVER_CHANGED");
            return;
        }
        var expected = recovery;
        if (expected == null && batchEnd > next) expected = plan.steps().get(batchEnd - 1);
        if (expected == null || !ContainerSyncPlan.matches(expected.items(), received)
                || !ItemStack.matches(expected.cursor(), unexpectedCursor)
                || !port.unchanged(expected.items(), expected.cursor())) {
            // A rejected middle click can change what later clicks picked up. An unknown
            // cursor belongs to the user; never return it to a guessed source slot.
            if (unexpectedCursor.isEmpty()) finish("SERVER_CHANGED");
            else problem = "CURSOR_NEEDS_ATTENTION";
            return;
        }
        if (recovery != null) {
            finish(cancellationRecovery ? null : "SERVER_CHANGED");
            return;
        }
        next = batchEnd;
        cursor = unexpectedCursor;
        pending = false;
    }

    private ContainerSyncPlan.Step recoveryStep(List<ItemStack> items, int back, ItemStack carried) {
        ItemStack there = items.get(back);
        int count = there.getCount() + carried.getCount();
        if (!there.isEmpty() && !ContainerSyncPlan.same(there, carried)
                || carried.getCount() > returnLimit
                || count > Math.min(port.recoveryCapacity(back, carried, returnLimit), carried.getMaxStackSize())) return null;
        var restored = new java.util.ArrayList<>(ContainerSyncPlan.copy(items));
        ItemStack merged = carried.copy();
        merged.setCount(count);
        restored.set(back, merged);
        return new ContainerSyncPlan.Step(back, 0, back, restored, ItemStack.EMPTY);
    }

    private void finish(String reason) {
        if (reason != null) problem = reason;
        finished = true;
        port.release();
    }
}
