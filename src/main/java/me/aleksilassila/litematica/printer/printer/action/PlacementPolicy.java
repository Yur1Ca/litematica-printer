package me.aleksilassila.litematica.printer.printer.action;

import java.util.function.IntSupplier;

/** Execution constraints supplied by the producer; a negative reserve means unrestricted. */
public record PlacementPolicy(IntSupplier reserveCount, boolean suppressAnvilScreen, boolean easyPlaceProtocol) {
}
