package me.aleksilassila.litematica.printer.core.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatureLifecycleTest {
    @Test void stoppingOneFeatureInvalidatesOnlyItsUnsentWork() {
        FeatureLifecycle lifecycle = new FeatureLifecycle();
        RuntimeEpoch epoch = new RuntimeEpoch(1);
        lifecycle.observe("print", true);
        lifecycle.observe("fill", true);
        var print = lifecycle.capture("print", epoch);
        var fill = lifecycle.capture("fill", epoch);
        assertTrue(lifecycle.observe("fill", false));
        assertTrue(lifecycle.canSend(print, epoch));
        assertFalse(lifecycle.canSend(fill, epoch));
        lifecycle.observe("fill", true);
        assertFalse(lifecycle.canSend(fill, epoch), "re-enable must not revive old work");
        assertTrue(lifecycle.canSend(lifecycle.capture("fill", epoch), epoch));
    }

    @Test void sessionChangeRejectsOldWorkEvenWhenOwnerGenerationMatches() {
        FeatureLifecycle lifecycle = new FeatureLifecycle();
        RuntimeEpoch epoch = new RuntimeEpoch(4);
        lifecycle.observe("mine", true);
        var work = lifecycle.capture("mine", epoch);
        assertFalse(lifecycle.canSend(work, epoch.next()));
        lifecycle.reset();
        lifecycle.observe("mine", true);
        assertFalse(lifecycle.canSend(work, epoch.next()));
    }

    @Test void UnchangedFeatureDoesNotRestartItsGeneration() {
        FeatureLifecycle lifecycle = new FeatureLifecycle();
        RuntimeEpoch epoch = new RuntimeEpoch(1);
        lifecycle.observe("fluid", true);
        var work = lifecycle.capture("fluid", epoch);
        for (int tick = 0; tick < 300; tick++) {
            assertFalse(lifecycle.observe("fluid", true));
            assertTrue(lifecycle.canSend(work, epoch));
        }
    }
}
