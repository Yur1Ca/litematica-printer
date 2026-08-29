package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockNetworkSyncTest {
    @Test
    void keepsSingleplayerPathAtTheExistingTiming() {
        assertEquals(16, BedrockNetworkSync.confirmationTimeoutTicks(true, 0));
        assertEquals(1, BedrockNetworkSync.retryDelayTicks(true, 1, 0));
        assertEquals(6, BedrockNetworkSync.retryDelayTicks(true, 6, 0));
    }

    @Test
    void extendsConfirmationAndClampsRemoteRetryDelay() {
        assertEquals(17, BedrockNetworkSync.confirmationTimeoutTicks(true, 1));
        assertEquals(20, BedrockNetworkSync.confirmationTimeoutTicks(true, 4));
        assertEquals(26, BedrockNetworkSync.confirmationTimeoutTicks(true, 10));
        assertEquals(4, BedrockNetworkSync.retryDelayTicks(true, 1, 1));
        assertEquals(10, BedrockNetworkSync.retryDelayTicks(true, 6, 10));
        assertEquals(40, BedrockNetworkSync.retryDelayTicks(true, 6, 60));
    }

    @Test
    void lateUpdateIsObservedPerTargetAndDoesNotBlockAnotherTarget() {
        AtomicLong tick = new AtomicLong(100L);
        BedrockNetworkSync sync = new BedrockNetworkSync(tick::get, () -> 0);
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

    @Test
    void normalConfirmationDoesNotCountAsAdaptiveBackoff() {
        assertEquals(6, BedrockNetworkSync.retryDelayTicks(true, 6, 0));
        assertEquals(6, BedrockNetworkSync.retryDelayTicks(false, 6, 10));
    }

    @Test
    void keepsLateCleanupUpdatesObservableWithoutChangingSingleplayer() {
        assertEquals(0, BedrockNetworkSync.cleanupWatchTicks(false, 16));
        assertEquals(0, BedrockNetworkSync.cleanupWatchTicks(true, 0));
        assertEquals(48, BedrockNetworkSync.cleanupWatchTicks(true, 16));
        assertEquals(168, BedrockNetworkSync.cleanupWatchTicks(true, 56));
        assertEquals(200, BedrockNetworkSync.cleanupWatchTicks(true, 100));
    }
}
