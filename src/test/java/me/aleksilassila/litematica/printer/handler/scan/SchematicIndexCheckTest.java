//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import com.sun.management.ThreadMXBean;
import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicIndexCheckTest {
    private static volatile long sink;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void unchangedFullChecksAvoidAllocatingAnotherPerChunkManifest() throws Exception {
        var fixture = new SchematicIndexFixture(1024, 1);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(fixture.world);
        var manager = (ChunkManagerSchematic) fixture.world.getChunkSource();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        Runnable sourceRead = () -> sink = manager.getLoadedValueSet().size();
        Runnable check = () -> { if (index.ensureBuilt(fixture.world)) fail("Unchanged source invalidated"); };
        for (int i = 0; i < 1000; i++) { sourceRead.run(); check.run(); }
        long sourceBytes = allocated(bean, sourceRead);
        long checkBytes = allocated(bean, check);
        assertTrue(checkBytes < sourceBytes + 1024L * Long.BYTES,
                () -> "Full validation should reuse its manifest: source=" + sourceBytes + ", check=" + checkBytes);
        assertEquals(1024, SchematicIndexFixture.positionCount(index));
    }

    @Test void iterationReorderingDoesNotInvalidateAnUnchangedSource() throws Exception {
        var chunks = new OrderedChunks();
        var a = SchematicIndexFixture.chunk(0, 1);
        var b = SchematicIndexFixture.chunk(1, 1);
        var c = SchematicIndexFixture.chunk(2, 1);
        chunks.values = List.of(a, b, c);
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        assertTrue(index.ensureBuilt(world));
        var expected = SchematicIndexFixture.positions(index).stream().sorted().toList();
        chunks.values = List.of(c, a, b);
        assertFalse(index.ensureBuilt(world));
        assertFalse(index.ensureBuilt(world));
        assertEquals(expected, SchematicIndexFixture.positions(index).stream().sorted().toList());
        b.setState(ChunkSchematicState.LOADED);
        assertTrue(index.ensureBuilt(world));
        assertFalse(index.isReady());
        chunks.values = List.of(b, c, a);
        assertFalse(index.ensureBuilt(world));
        b.setState(ChunkSchematicState.FILLED);
        assertTrue(index.ensureBuilt(world));
        assertEquals(expected, SchematicIndexFixture.positions(index).stream().sorted().toList());
    }

    @Test void duplicateChunkCannotHideARemovedChunkAtTheSameCount() throws Exception {
        var chunks = new OrderedChunks();
        var a = SchematicIndexFixture.chunk(0, 1);
        var b = SchematicIndexFixture.chunk(1, 1);
        chunks.values = List.of(a, b);
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        chunks.values = List.of(a, a);
        assertTrue(index.ensureBuilt(world));
        assertEquals(List.of(SchematicIndexFixture.firstPosition(0).asLong()), SchematicIndexFixture.positions(index));
        chunks.values = List.of(a, b);
        assertTrue(index.ensureBuilt(world));
        assertEquals(2, SchematicIndexFixture.positionCount(index));
    }

    @Test void unchangedPrefixCannotHideTheLastChunksStateOrReplacement() throws Exception {
        var chunks = new OrderedChunks();
        var all = new java.util.ArrayList<ChunkSchematic>();
        for (int i = 0; i < 128; i++) all.add(SchematicIndexFixture.chunk(i, 1));
        chunks.values = List.copyOf(all);
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        all.get(127).setState(ChunkSchematicState.LOADED);
        assertTrue(index.ensureBuilt(world));
        assertFalse(index.isReady());
        all.set(127, SchematicIndexFixture.chunk(127, 0));
        chunks.values = List.copyOf(all);
        assertTrue(index.ensureBuilt(world));
        assertTrue(index.isReady());
        assertEquals(127, SchematicIndexFixture.positionCount(index));
    }

    @Test void denseFallbackWithFilledChunksMustNotBlockReturningToSparseMode() throws Exception {
        var f = new SchematicIndexFixture(2, 1);
        var index = new SchematicBlockIndex();
        assertTrue(index.ensureBuilt(f.world));
        assertTrue(index.ensureBuilt(f.world, RuntimeTiming.disabled(), "print", false));
        assertFalse(index.isReady());
        assertFalse(index.ensureBuilt(f.world, RuntimeTiming.disabled(), "print", false));
        assertTrue(index.ensureBuilt(f.world));
        assertTrue(index.isReady());
        assertEquals(2, SchematicIndexFixture.positionCount(index));
    }

    private static long allocated(ThreadMXBean bean, Runnable action) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100; i++) action.run();
        return (bean.getThreadAllocatedBytes(thread) - before) / 100;
    }

    private static final class OrderedChunks extends ConcurrentHashMap<Long, ChunkSchematic> {
        private List<ChunkSchematic> values = List.of();
        @Override public Collection<ChunkSchematic> values() { return this.values; }
    }
}
//#endif
