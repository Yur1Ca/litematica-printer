package me.aleksilassila.litematica.printer.core;

import me.aleksilassila.litematica.printer.core.action.ActionRequest;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreContractsTest {
    @Test
    void actionContractValuesAndValidationAreStable() {
        assertEquals(5, ResourceLease.values().length);

        ActionRequest empty = new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, Set.of(), 0L);
        assertTrue(empty.resources().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ActionRequest(
                " ", RuntimeEpoch.INITIAL, Set.of(), 0L));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                null, RuntimeEpoch.INITIAL, Set.of(), 0L));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", null, Set.of(), 0L));
        assertThrows(NullPointerException.class, () -> new ActionRequest(
                "scan", RuntimeEpoch.INITIAL, null, 0L));

        EnumSet<ResourceLease> mutable = EnumSet.of(ResourceLease.LOOK);
        ActionRequest copied = new ActionRequest(
                "print", RuntimeEpoch.INITIAL, mutable, 0L);
        mutable.add(ResourceLease.MAIN_HAND);
        assertEquals(Set.of(ResourceLease.LOOK), copied.resources());
    }

    @Test
    void scanGenerationRejectsInvalidRevisions() {
        RuntimeEpoch epoch = new RuntimeEpoch(3L);
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScanGeneration(epoch, 0, 0, -1));

        assertEquals(new ScanGeneration(epoch, 1, 2, 3), new ScanGeneration(epoch, 1, 2, 3));
    }
}
