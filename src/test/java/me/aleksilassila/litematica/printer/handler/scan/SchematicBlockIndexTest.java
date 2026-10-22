package me.aleksilassila.litematica.printer.handler.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class SchematicBlockIndexTest {
    @Test
    void unavailableSchematicClearsAllSpatialQueryData() throws Exception {
        var index = SparseScanLifecycleTest.readyIndex(List.of(net.minecraft.core.BlockPos.ZERO));
        //#if MC < 12111
        //$$ var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        //$$ field.setAccessible(true);
        //$$ var unsafe = (sun.misc.Unsafe) field.get(null);
        //$$ var schematic = (fi.dy.masa.litematica.world.WorldSchematic) unsafe.allocateInstance(
        //$$         fi.dy.masa.litematica.world.WorldSchematic.class);
        //$$ assertTrue(index.ensureBuilt(schematic));
        //$$ assertFalse(index.ensureBuilt(schematic));
        //#else
        assertTrue(index.ensureBuilt(null));
        assertFalse(index.ensureBuilt(null));
        //#endif
        assertFalse(index.isReady());
        assertEquals(0, index.forEachInRange(List.of(new me.aleksilassila.litematica.printer.printer.PrinterBox(
                -1, -1, -1, 1, 1, 1)), IndexedPositionQueryParityTest.region(0, 0, 0, 2),
                pos -> fail("Unavailable index retained a position")));
    }

    @Test
    void sameCountWithDifferentChunkCoordinatesInvalidatesTheSnapshot() {
        Object firstChunk = new Object();
        Object secondChunk = new Object();

        List<SchematicBlockIndex.ChunkStamp> before = List.of(
                new SchematicBlockIndex.ChunkStamp(1L, firstChunk, true),
                new SchematicBlockIndex.ChunkStamp(2L, secondChunk, true)
        );
        List<SchematicBlockIndex.ChunkStamp> after = List.of(
                new SchematicBlockIndex.ChunkStamp(2L, secondChunk, true),
                new SchematicBlockIndex.ChunkStamp(3L, new Object(), true)
        );

        assertFalse(SchematicBlockIndex.sameChunkSnapshot(before, after));
    }

    @Test
    void replacementAtTheSameCoordinatesInvalidatesTheSnapshot() {
        Object original = new Object();
        List<SchematicBlockIndex.ChunkStamp> before = List.of(
                new SchematicBlockIndex.ChunkStamp(7L, original, true)
        );
        List<SchematicBlockIndex.ChunkStamp> after = List.of(
                new SchematicBlockIndex.ChunkStamp(7L, new Object(), true)
        );

        assertFalse(SchematicBlockIndex.sameChunkSnapshot(before, after));
    }

    @Test
    void fillingAnExistingChunkInvalidatesTheWaitingSnapshot() {
        Object chunk = new Object();
        List<SchematicBlockIndex.ChunkStamp> waiting = List.of(
                new SchematicBlockIndex.ChunkStamp(11L, chunk, false)
        );
        List<SchematicBlockIndex.ChunkStamp> filled = List.of(
                new SchematicBlockIndex.ChunkStamp(11L, chunk, true)
        );

        assertFalse(SchematicBlockIndex.sameChunkSnapshot(waiting, filled));
        assertTrue(SchematicBlockIndex.sameChunkSnapshot(filled, filled));
    }
}
