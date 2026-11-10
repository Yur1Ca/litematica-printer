package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockNetworkSyncTest {
    @Test
    void lateUpdateIsObservedPerTargetAndDoesNotBlockAnotherTarget() {
        AtomicLong tick = new AtomicLong(100L);
        BedrockNetworkSync sync = new BedrockNetworkSync(tick::get);
        BlockPos first = new BlockPos(1, 2, 3);
        BlockPos second = new BlockPos(10, 2, 3);

        sync.beginAttempt(first, Set.of(first, first.above()));
        sync.beginAttempt(second, Set.of(second));
        assertEquals(2, sync.pendingCount());

        sync.recordTimeout(first);
        tick.set(120L);
        sync.onServerBlockUpdate(first.above());

        assertEquals(1, sync.serverUpdateTimeouts());
        assertEquals("late_update", sync.lastResult());
        sync.confirmed(second);
        assertEquals(1, sync.pendingCount());
        sync.confirmed(first);
        assertEquals(0, sync.pendingCount());
        assertEquals("confirmed", sync.lastResult());
    }
}
