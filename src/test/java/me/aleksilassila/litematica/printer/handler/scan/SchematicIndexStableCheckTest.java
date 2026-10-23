//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicIndexStableCheckTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void everyCheckObservesEachLoadedChunkStateExactlyOnce() throws Exception {
        var first = observedChunk(0);
        var second = observedChunk(1);
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(SchematicIndexFixture.key(0), first);
        chunks.put(SchematicIndexFixture.key(1), second);
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        for (int stage = 0; stage < 6; stage++) {
            if (stage == 2) {
                second = observedChunk(1);
                chunks.put(SchematicIndexFixture.key(1), second);
            }
            if (stage == 3) first.setState(ChunkSchematicState.LOADED);
            if (stage == 5) first.setState(ChunkSchematicState.FILLED);
            first.stateReads = second.stateReads = 0;
            index.ensureBuilt(world);
            assertEquals(1, first.stateReads, "first chunk at stage " + stage);
            assertEquals(1, second.stateReads, "second chunk at stage " + stage);
        }
        first.stateReads = second.stateReads = 0;
        assertTrue(index.ensureBuilt(SchematicIndexFixture.world(chunks)));
        assertEquals(1, first.stateReads);
        assertEquals(1, second.stateReads);
    }

    @Test void loadedObservationCannotBeReplacedByALaterFilledReadInTheSameCheck() throws Exception {
        var first = observedChunk(0);
        var second = observedChunk(1);
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(SchematicIndexFixture.key(0), first);
        chunks.put(SchematicIndexFixture.key(1), second);
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        first.stateReads = second.stateReads = 0;
        first.observeLoadedOnce = true;
        assertTrue(index.ensureBuilt(world));
        assertEquals(1, first.stateReads);
        assertEquals(1, second.stateReads);
        assertFalse(index.isReady(), "The first LOADED observation must clear the index even if the chunk then fills");
        assertTrue(SchematicIndexFixture.positions(index).isEmpty());
        assertTrue(index.ensureBuilt(world));
        assertEquals(2, first.stateReads);
        assertEquals(2, second.stateReads);
        assertTrue(index.isReady());
        assertEquals(List.of(SchematicIndexFixture.firstPosition(0).asLong(),
                        SchematicIndexFixture.firstPosition(1).asLong()).stream().sorted().toList(),
                SchematicIndexFixture.positions(index).stream().sorted().toList());
    }

    private static ObservedChunk observedChunk(int ordinal) throws Exception {
        var source = SchematicIndexFixture.chunk(ordinal, 1);
        var firstPosition = SchematicIndexFixture.firstPosition(ordinal);
        return SchematicIndexFixture.chunk(ObservedChunk.class,
                new ChunkPos(firstPosition.getX() >> 4, firstPosition.getZ() >> 4), source.getSections());
    }

    private static final class ObservedChunk extends ChunkSchematic {
        int stateReads;
        boolean observeLoadedOnce;

        // The fixture allocates the shell without executing heavyweight world constructors.
        private ObservedChunk() { super(null, null); }

        @Override public ChunkSchematicState getState() {
            this.stateReads++;
            if (this.observeLoadedOnce && this.stateReads == 1) return ChunkSchematicState.LOADED;
            return super.getState();
        }
    }
}
//#endif
