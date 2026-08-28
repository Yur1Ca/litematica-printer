package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleCandidateSourceTest {
    @Test
    void printCandidatesRestartAfterACompletedPass() {
        assertEquals(
                ScanEngine.PassPolicy.RESTART,
                ModuleCandidateSource.cachedPassPolicy(ScanIntent.PRINT)
        );
    }

    @Test
    void nonPrintCachedSourcesRemainInvalidationDriven() {
        assertEquals(
                ScanEngine.PassPolicy.INVALIDATIONS_ONLY,
                ModuleCandidateSource.cachedPassPolicy(ScanIntent.FILL)
        );
    }
}
