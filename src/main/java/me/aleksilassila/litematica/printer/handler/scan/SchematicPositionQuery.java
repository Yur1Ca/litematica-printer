package me.aleksilassila.litematica.printer.handler.scan;

import java.util.List;
import java.util.function.LongConsumer;
import me.aleksilassila.litematica.printer.printer.PrinterBox;

/** Read-only, client-thread query over packed schematic positions. */
interface SchematicPositionQuery {
    /**
     * Visits a conservative superset of positions in the boxes and distance band, each at most once.
     * Order is unspecified; callers retain exact filtering and sorting. No mutable storage escapes.
     * Returns the number of visited positions for diagnostics.
     */
    long forEachInRange(List<PrinterBox> boxes, ScanRegion region, LongConsumer consumer);
}
