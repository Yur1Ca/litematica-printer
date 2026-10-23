//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicIndexLifecycleTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void completeBuildReadsActualSectionPalettesAndStableChecksKeepResults() throws Exception {
        var fixture = new SchematicIndexFixture(2, 1);
        var index = new SchematicBlockIndex();
        assertTrue(index.ensureBuilt(fixture.world));
        assertTrue(index.isReady());
        var expected = List.of(SchematicIndexFixture.firstPosition(0).asLong(),
                SchematicIndexFixture.firstPosition(1).asLong()).stream().sorted().toList();
        assertEquals(expected, SchematicIndexFixture.positions(index).stream().sorted().toList());
        assertFalse(index.ensureBuilt(fixture.world));
        assertEquals(expected, SchematicIndexFixture.positions(index).stream().sorted().toList());
    }

    @Test void replacingAChunkAtTheSameCoordinatesDropsItsOldContents() throws Exception {
        var fixture = new SchematicIndexFixture(2, 1);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(fixture.world);
        fixture.chunks.put(SchematicIndexFixture.key(0), SchematicIndexFixture.chunk(0, 0));
        assertTrue(index.ensureBuilt(fixture.world));
        assertEquals(List.of(SchematicIndexFixture.firstPosition(1).asLong()), SchematicIndexFixture.positions(index));
        assertFalse(index.ensureBuilt(fixture.world));
    }

    @Test void loadingChunkClearsReadyDataUntilEveryChunkIsFilled() throws Exception {
        var fixture = new SchematicIndexFixture(2, 1);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(fixture.world);
        var chunk = fixture.chunks.get(SchematicIndexFixture.key(0));
        chunk.setState(ChunkSchematicState.LOADED);
        assertTrue(index.ensureBuilt(fixture.world));
        assertFalse(index.isReady());
        assertTrue(SchematicIndexFixture.positions(index).isEmpty());
        assertFalse(index.ensureBuilt(fixture.world));
        chunk.setState(ChunkSchematicState.FILLED);
        assertTrue(index.ensureBuilt(fixture.world));
        assertTrue(index.isReady());
        assertEquals(2, SchematicIndexFixture.positionCount(index));
    }

    @Test void anotherWorldWithIdenticalChunkObjectsStillStartsANewSnapshot() throws Exception {
        var fixture = new SchematicIndexFixture(1, 1);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(fixture.world);
        var other = SchematicIndexFixture.world(new ConcurrentHashMap<>(fixture.chunks));
        assertTrue(index.ensureBuilt(other));
        assertFalse(index.ensureBuilt(other));
        assertEquals(1, SchematicIndexFixture.positionCount(index));
        assertTrue(index.ensureBuilt(null));
        assertFalse(index.isReady());
        assertTrue(SchematicIndexFixture.positions(index).isEmpty());
        assertTrue(index.ensureBuilt(fixture.world));
    }

    @Test void chunkRemovalAndAdditionCannotKeepPositionsFromThePreviousSnapshot() throws Exception {
        var fixture = new SchematicIndexFixture(1, 1);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(fixture.world);
        fixture.chunks.clear();
        assertTrue(index.ensureBuilt(fixture.world));
        assertTrue(SchematicIndexFixture.positions(index).isEmpty());
        fixture.chunks.put(SchematicIndexFixture.key(2), SchematicIndexFixture.chunk(2, 1));
        assertTrue(index.ensureBuilt(fixture.world));
        assertEquals(List.of(SchematicIndexFixture.firstPosition(2).asLong()), SchematicIndexFixture.positions(index));
    }
}
//#endif
