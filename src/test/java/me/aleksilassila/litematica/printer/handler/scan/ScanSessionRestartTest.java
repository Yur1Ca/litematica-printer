package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSessionRestartTest {
    @Test
    void completedPassRestartsOnTheNextClientTick() {
        SectionScanSession session = session();

        assertNull(session.next(null, 20L, () -> false, pos -> true, false));
        assertFalse(session.canScan(20L));
        assertTrue(session.canScan(21L));
    }

    @Test
    void closedSessionCannotBeRevivedByAnOldIterator() {
        SectionScanSession session = session();

        session.close();
        session.invalidate(new BlockPos(0, 0, 0));

        assertFalse(session.canScan(30L));
        assertFalse(session.hasPendingSource(30L));
    }

    private static SectionScanSession session() {
        PrinterBox box = new PrinterBox(-1, -1, -1, 1, 1, 1);
        ScanRegion region = ScanRegion.from(box, null);
        return new SectionScanSession(
                region,
                List.of(box),
                ScanIntent.FLUID,
                new ScanMetricsAccumulator()
        );
    }
}
