package me.aleksilassila.litematica.printer.handler;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ModuleScanContinuationTest {
    private static final PrinterBox BOX = new PrinterBox(-1, -1, -1, 1, 1, 1);
    private static final FeatureModuleBase.IterationOutcome EMPTY_COMPLETE =
            new FeatureModuleBase.IterationOutcome(false, false, false, true, false);
    private static final FeatureModuleBase.IterationOutcome PAUSED =
            new FeatureModuleBase.IterationOutcome(true, false, false, false, true);

    private int previousLazyThreshold;
    private FakeHost host;
    private ModuleScanCoordinator coordinator;

    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach void setUp() {
        this.previousLazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        Configs.Core.LAZY_ENTER_TICKS.setIntegerValue(1);
        this.host = new FakeHost();
        var engine = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> null, new ScanCache());
        this.coordinator = new ModuleScanCoordinator(this.host, null, engine);
    }

    @AfterEach void restoreConfig() {
        Configs.Core.LAZY_ENTER_TICKS.setIntegerValue(this.previousLazyThreshold);
    }

    @Test void budgetPausedLazyProbeContinuesOnEveryFollowingTick() {
        this.enterLazy();
        this.host.outcome = PAUSED;
        this.waitForNextProbe();
        assertEquals(ScanState.LAZY, this.coordinator.state());

        int callsBeforeContinuation = this.host.iterations;
        assertTrue(this.coordinator.run(BOX));
        assertEquals(callsBeforeContinuation + 1, this.host.iterations,
                "A paused probe must resume on the next admission instead of waiting for another probe interval");
        assertEquals(ScanState.LAZY, this.coordinator.state());

        assertTrue(this.coordinator.run(BOX));
        assertEquals(callsBeforeContinuation + 2, this.host.iterations,
                "Every remaining slice of the same probe must keep making progress");
        assertEquals(ScanState.LAZY, this.coordinator.state());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void activityWakesAProbeEvenWhenItsRemainingSourceIsPaused(boolean didWork) {
        this.enterLazy();
        this.host.outcome = new FeatureModuleBase.IterationOutcome(true, didWork, true, false, true);

        this.waitForNextProbe();

        assertEquals(ScanState.FULL, this.coordinator.state(),
                "A candidate discovered before the budget pause is real activity");
        int callsBeforeNextTick = this.host.iterations;
        this.host.outcome = PAUSED;
        assertTrue(this.coordinator.run(BOX));
        assertEquals(callsBeforeNextTick + 1, this.host.iterations);
    }

    @Test void completedEmptyContinuationStartsANewProbeInterval() {
        this.enterLazy();
        this.host.outcome = PAUSED;
        this.waitForNextProbe();

        int callsBeforeCompletion = this.host.iterations;
        this.host.outcome = EMPTY_COMPLETE;
        this.coordinator.run(BOX);
        assertEquals(callsBeforeCompletion + 1, this.host.iterations,
                "The final slice must run immediately after the paused slice");
        assertEquals(ScanState.LAZY, this.coordinator.state());

        this.waitForNextProbe();
        assertEquals(ScanState.LAZY, this.coordinator.state());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resetAndFullScanRequestsCancelAnOldProbeContinuation(boolean reset) {
        this.enterLazy();
        this.host.outcome = PAUSED;
        this.waitForNextProbe();

        if (reset) {
            this.coordinator.reset();
        } else {
            this.coordinator.requestFullScan();
        }
        assertEquals(ScanState.FULL, this.coordinator.state());
        int callsBeforeFullPass = this.host.iterations;
        this.host.outcome = EMPTY_COMPLETE;
        this.coordinator.run(BOX);
        assertEquals(callsBeforeFullPass + 1, this.host.iterations);
        assertEquals(ScanState.LAZY, this.coordinator.state());

        this.waitForNextProbe();
    }

    private void enterLazy() {
        this.coordinator.run(BOX);
        assertEquals(1, this.host.iterations);
        assertEquals(ScanState.LAZY, this.coordinator.state());
    }

    private void waitForNextProbe() {
        int callsBeforeProbe = this.host.iterations;
        for (int tick = 1; tick < 40; tick++) {
            assertFalse(this.coordinator.run(BOX));
            assertEquals(callsBeforeProbe, this.host.iterations,
                    "An idle scanner must keep the normal interval before starting a new probe");
        }
        this.coordinator.run(BOX);
        assertEquals(callsBeforeProbe + 1, this.host.iterations);
    }

    private static final class FakeHost implements ModuleScanCoordinator.Host {
        private FeatureModuleBase.IterationOutcome outcome = EMPTY_COMPLETE;
        private int iterations;

        @Override public List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox) { return List.of(BOX); }
        @Override public PrinterBox scanSourceBox(PrinterBox interactionBox) { return interactionBox; }
        @Override public FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox) {
            assertEquals(BOX, interactionBox);
            this.iterations++;
            return this.outcome;
        }
        @Override public boolean hasRunnableTargets() { return false; }
        @Override public boolean hasWaitingTargets() { return false; }
        @Override public boolean usesDirtyRegionWakeup() { return false; }
        @Override public double playerX() { return 0; }
        @Override public double playerEyeY() { return 0; }
        @Override public double playerZ() { return 0; }
    }
}
