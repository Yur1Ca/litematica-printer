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
        assertTrue(index.ensureBuilt(null));
        assertFalse(index.ensureBuilt(null));
        assertFalse(index.isReady());
        assertEquals(0, index.forEachInRange(List.of(new me.aleksilassila.litematica.printer.printer.PrinterBox(
                -1, -1, -1, 1, 1, 1)), IndexedPositionQueryParityTest.region(0, 0, 0, 2),
                pos -> fail("Unavailable index retained a position")));
    }

    @Test
    void extraBlockDenseModeObservesChunkChangesAndCanReturnToSparseIndexing() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var fixture = new SchematicIndexFixture(1, 1);
        var index = new SchematicBlockIndex();
        var timing = me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.disabled();
        assertTrue(index.ensureBuilt(fixture.world));
        assertTrue(index.isReady());
        assertEquals(1, SchematicIndexFixture.positionCount(index));

        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());
        assertEquals(0, SchematicIndexFixture.positionCount(index),
                "Dense mode must discard the former sparse snapshot");

        fixture.chunks.put(SchematicIndexFixture.key(0), SchematicIndexFixture.chunk(0, 2));
        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false),
                "Dense PRINT must observe a replacement at the same coordinates");
        assertFalse(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());

        fixture.chunks.put(SchematicIndexFixture.key(1), SchematicIndexFixture.chunk(1, 1));
        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());
        fixture.chunks.remove(SchematicIndexFixture.key(0));
        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());

        fixture.chunks.clear();
        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());
        fixture.chunks.put(SchematicIndexFixture.key(2), SchematicIndexFixture.chunk(2, 1));
        assertTrue(index.ensureBuilt(fixture.world, timing, "print", false));
        assertFalse(index.isReady());

        assertTrue(index.ensureBuilt(fixture.world));
        assertTrue(index.isReady());
        assertFalse(index.ensureBuilt(fixture.world));
        assertEquals(List.of(SchematicIndexFixture.firstPosition(2).asLong()), SchematicIndexFixture.positions(index));
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
