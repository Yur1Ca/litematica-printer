package me.aleksilassila.litematica.printer.handler;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
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
    private FakeHost host;
    private ModuleScanCoordinator coordinator;

    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach void setUp() {
        this.host = new FakeHost();
        this.coordinator = new ModuleScanCoordinator(this.host, null);
    }

    @Test void completedWorksetsAreAdmittedEveryTickWithoutAnOuterIdleDelay() {
        for (int tick = 0; tick < 60; tick++) {
            assertFalse(this.coordinator.run(BOX));
            assertEquals(tick + 1, this.host.iterations,
                    "The persistent source owns sleep and repair; the coordinator must not delay it");
            assertEquals(ScanState.LAZY, this.coordinator.state());
        }
    }

    @Test void continuousLocalUpdatesCannotNarrowOrStarveTheFullWorkset() {
        ScanEngine engine = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> null, new ScanCache());
        for (int tick = 0; tick < 220; tick++) {
            if (tick % 10 == 0) engine.invalidate(new BlockPos(1, 0, 1));
            this.coordinator.run(BOX);
            assertEquals(tick + 1, this.host.iterations,
                    "Local updates cannot suppress admission or replace the complete scope with a dirty box");
        }
    }

    @Test void aPausedPassContinuesOnEveryFollowingTick() {
        this.coordinator.run(BOX);
        this.host.outcome = PAUSED;
        for (int tick = 0; tick < 4; tick++) {
            assertTrue(this.coordinator.run(BOX));
            assertEquals(tick + 2, this.host.iterations);
            assertEquals(ScanState.FULL, this.coordinator.state());
        }
    }

    @Test void aSleepingSnapshotDoesNotNeedToCompleteAnotherPassToRemainIdle() {
        this.coordinator.run(BOX);
        this.host.outcome = new FeatureModuleBase.IterationOutcome(false, false, false, false, false);
        assertFalse(this.coordinator.run(BOX));
        assertEquals(ScanState.LAZY, this.coordinator.state(),
                "A persistent sleeping source produces no new completed-pass metric on each admission");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void activityRemainsVisibleEvenWhenItsSourceIsPaused(boolean didWork) {
        this.coordinator.run(BOX);
        this.host.outcome = new FeatureModuleBase.IterationOutcome(true, didWork, true, false, true);
        assertTrue(this.coordinator.run(BOX));
        assertEquals(ScanState.FULL, this.coordinator.state());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resetAndFullScanRequestsResumeImmediately(boolean reset) {
        this.coordinator.run(BOX);
        if (reset) this.coordinator.reset(); else this.coordinator.requestFullScan();
        assertEquals(ScanState.FULL, this.coordinator.state());
        assertFalse(this.coordinator.run(BOX));
        assertEquals(2, this.host.iterations);
        assertEquals(ScanState.LAZY, this.coordinator.state());
    }

    @Test void anEmptySelectionClearsTheHudBoxAndCanExpandAgain() {
        AtomicReference<PrinterBox> external = new AtomicReference<>();
        this.coordinator = new ModuleScanCoordinator(this.host, external);
        this.coordinator.run(BOX);
        assertEquals(BOX, external.get());
        this.host.boxes = List.of();
        assertFalse(this.coordinator.run(BOX));
        assertNull(external.get());
        assertEquals(1, this.host.iterations);
        this.host.boxes = List.of(BOX);
        this.coordinator.run(BOX);
        assertEquals(BOX, external.get());
        assertEquals(2, this.host.iterations);
    }

    private static final class FakeHost implements ModuleScanCoordinator.Host {
        private FeatureModuleBase.IterationOutcome outcome = EMPTY_COMPLETE;
        private List<PrinterBox> boxes = List.of(BOX);
        private int iterations;

        @Override public List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox) { return this.boxes; }
        @Override public FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox) {
            assertEquals(BOX, interactionBox, "Every admission must retain the complete interaction scope");
            this.iterations++;
            return this.outcome;
        }
    }
}
