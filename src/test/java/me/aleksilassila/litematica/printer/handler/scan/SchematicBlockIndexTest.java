package me.aleksilassila.litematica.printer.handler.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicBlockIndexTest {
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
