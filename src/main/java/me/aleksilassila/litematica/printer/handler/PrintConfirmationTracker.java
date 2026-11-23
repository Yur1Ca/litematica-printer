package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PrintConfirmationTracker {
    private final Map<BlockPos, PendingBlockState> pendingStates = new LinkedHashMap<>();

    void track(BlockPos pos, BlockState expectedState, long sentTick, int timeoutTicks) {
        this.track(pos, expectedState, sentTick, timeoutTicks, null);
    }

    void track(BlockPos pos, BlockState expectedState, long sentTick, int timeoutTicks, @Nullable Object owner) {
        this.pendingStates.put(
                pos.immutable(),
                new PendingBlockState(expectedState, sentTick + timeoutTicks, owner)
        );
    }

    void discard(BlockPos pos, Object owner) {
        PendingBlockState pending = this.pendingStates.get(pos);
        if (owner != null && pending != null && pending.owner() == owner) this.pendingStates.remove(pos);
    }

    boolean isPending(BlockPos pos) {
        return this.pendingStates.containsKey(pos);
    }

    Resolution resolve(BlockPos pos, BlockState authoritativeState) {
        PendingBlockState pending = this.pendingStates.remove(pos);
        if (pending == null) {
            return Resolution.NOT_TRACKED;
        }
        return authoritativeState.equals(pending.expectedState())
                ? Resolution.MATCHED
                : Resolution.MISMATCHED;
    }

    List<BlockPos> expire(long currentTick, int maxChecks) {
        List<BlockPos> expired = new ArrayList<>();
        for (int checked = 0; checked < maxChecks && !this.pendingStates.isEmpty(); checked++) {
            Iterator<Map.Entry<BlockPos, PendingBlockState>> iterator =
                    this.pendingStates.entrySet().iterator();
            Map.Entry<BlockPos, PendingBlockState> entry = iterator.next();
            iterator.remove();
            if (currentTick > entry.getValue().expireTick()) {
                expired.add(entry.getKey());
            } else {
                this.pendingStates.put(entry.getKey(), entry.getValue());
            }
        }
        return expired;
    }

    void clear() {
        this.pendingStates.clear();
    }

    enum Resolution {
        NOT_TRACKED,
        MATCHED,
        MISMATCHED
    }

    private record PendingBlockState(BlockState expectedState, long expireTick, @Nullable Object owner) {
    }
}
