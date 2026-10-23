package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrockAttemptObservationTest {
    private static final BlockPos TARGET = new BlockPos(1, 2, 3);

    @Test void observesFirstFootprintUpdateAndOneConfirmedOutcome() {
        var f = new Fixture();
        f.sync.beginAttempt(TARGET, Set.of(TARGET, TARGET.above()));
        f.tick.set(102);
        f.sync.onServerBlockUpdate(TARGET.east());
        f.tick.set(103);
        f.sync.onServerBlockUpdate(TARGET.above());
        f.tick.set(105);
        f.sync.onServerBlockUpdate(TARGET);
        f.sync.confirmed(TARGET);
        f.sync.confirmed(TARGET);
        f.sync.discarded(TARGET, false);

        assertEquals(1, f.count(BEDROCK_ATTEMPTS));
        assertEquals(1, f.count(BEDROCK_FIRST_UPDATES));
        assertEquals(3, f.count(BEDROCK_FIRST_UPDATE_TICKS));
        assertEquals(1, f.count(BEDROCK_CONFIRMED_ATTEMPTS));
        assertEquals(1, f.count(BEDROCK_FINISHED_ATTEMPTS));
        assertEquals(5, f.count(BEDROCK_ATTEMPT_TICKS));
        assertEquals(0, f.count(BEDROCK_DISCARDED_ATTEMPTS));
        assertEquals(0, f.sync.pendingCount());
    }

    @Test void retryClosesOneAttemptAndSamePositionStartsWithANewClock() {
        var f = new Fixture();
        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        f.tick.set(104);
        f.sync.recordRetry(TARGET);
        f.sync.recordRetry(TARGET);
        assertEquals(1, f.sync.pendingCount(), "Keep the existing HUD pending-observation lifetime");
        f.tick.set(106);
        f.sync.onServerBlockUpdate(TARGET);
        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        f.tick.set(107);
        f.sync.onServerBlockUpdate(TARGET);
        f.tick.set(109);
        f.sync.confirmed(TARGET);

        assertEquals(2, f.count(BEDROCK_ATTEMPTS));
        assertEquals(1, f.count(BEDROCK_RETRIES));
        assertEquals(1, f.count(BEDROCK_CONFIRMED_ATTEMPTS));
        assertEquals(0, f.count(BEDROCK_DISCARDED_ATTEMPTS));
        assertEquals(2, f.count(BEDROCK_FINISHED_ATTEMPTS));
        assertEquals(7, f.count(BEDROCK_ATTEMPT_TICKS));
        assertEquals(1, f.count(BEDROCK_FIRST_UPDATES), "Updates after a closed attempt do not extend its measurements");
        assertEquals(1, f.count(BEDROCK_FIRST_UPDATE_TICKS));
    }

    @Test void replacementAndRepeatedDiscardDoNotLoseOrDuplicateAnOutcome() {
        var f = new Fixture();
        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        f.tick.set(102);
        f.sync.beginAttempt(TARGET, Set.of(TARGET.above()));
        f.tick.set(105);
        f.sync.discarded(TARGET, true);
        f.sync.discarded(TARGET, true);
        f.sync.recordRetry(TARGET);

        assertEquals(2, f.count(BEDROCK_ATTEMPTS));
        assertEquals(2, f.count(BEDROCK_DISCARDED_ATTEMPTS));
        assertEquals(2, f.count(BEDROCK_FINISHED_ATTEMPTS));
        assertEquals(5, f.count(BEDROCK_ATTEMPT_TICKS));
        assertEquals(0, f.count(BEDROCK_RETRIES));
    }

    @Test void timeoutIsAnObservationAndDoesNotDoubleFinishALateConfirmation() {
        var f = new Fixture();
        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        f.tick.set(116);
        f.sync.recordTimeout(TARGET);
        f.sync.recordTimeout(TARGET);
        f.tick.set(120);
        f.sync.onServerBlockUpdate(TARGET);
        f.sync.confirmed(TARGET);

        assertEquals(1, f.count(BEDROCK_ATTEMPT_TIMEOUTS));
        assertEquals(1, f.count(BEDROCK_CONFIRMED_ATTEMPTS));
        assertEquals(1, f.count(BEDROCK_FINISHED_ATTEMPTS));
        assertEquals(20, f.count(BEDROCK_ATTEMPT_TICKS));
        assertEquals(20, f.count(BEDROCK_FIRST_UPDATE_TICKS));
    }

    @Test void resetDropsOldAttemptCallbacksBeforeReusingThePosition() {
        var f = new Fixture();
        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        // Runtime diagnostics flush the old epoch before resetting owned components.
        f.timing.reset();
        f.sync.reset();
        f.tick.set(200);
        f.sync.onServerBlockUpdate(TARGET);
        f.sync.confirmed(TARGET);
        f.sync.recordRetry(TARGET);
        f.sync.recordTimeout(TARGET);
        assertTrue(f.timing.snapshot().counters().isEmpty());

        f.sync.beginAttempt(TARGET, Set.of(TARGET));
        f.tick.set(202);
        f.sync.confirmed(TARGET);
        assertEquals(1, f.count(BEDROCK_ATTEMPTS));
        assertEquals(1, f.count(BEDROCK_FINISHED_ATTEMPTS));
        assertEquals(2, f.count(BEDROCK_ATTEMPT_TICKS));
    }

    @Test void disabledProfilingKeepsExistingTrackingWithoutSamples() {
        var tick = new AtomicLong(100);
        var timing = RuntimeTiming.disabled();
        var sync = new BedrockNetworkSync(tick::get, () -> 0, timing);
        sync.beginAttempt(TARGET, Set.of(TARGET));
        assertEquals(1, sync.pendingCount());
        tick.set(104);
        sync.recordRetry(TARGET);
        assertEquals(1, sync.pendingCount());
        sync.confirmed(TARGET);
        assertEquals(0, sync.pendingCount());
        assertTrue(timing.snapshot().counters().isEmpty());
        assertTrue(timing.snapshot().samples().isEmpty());
    }

    private static final class Fixture {
        final AtomicLong tick = new AtomicLong(100);
        final RuntimeTiming timing = new RuntimeTiming(true, () -> 0);
        final BedrockNetworkSync sync = new BedrockNetworkSync(tick::get, () -> 0, timing);

        long count(RuntimeTiming.Reason reason) {
            return this.timing.snapshot().counters().stream()
                    .filter(counter -> counter.owner().equals("bedrock") && counter.reason() == reason)
                    .mapToLong(RuntimeTiming.Counter::count).sum();
        }
    }
}
