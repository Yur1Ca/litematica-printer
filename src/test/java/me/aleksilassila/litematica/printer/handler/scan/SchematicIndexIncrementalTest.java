//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.assertCursorEquals;
import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.reference;
import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.region;
import static org.junit.jupiter.api.Assertions.*;

class SchematicIndexIncrementalTest {
    private static final List<PrinterBox> ALL = List.of(new PrinterBox(-128, -64, -128, 127, 319, 127));

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void replacementKeepsCompleteCandidateOrderAndStableChecksKeepResults() throws Exception {
        var retained = section(BlockPos.ZERO, new BlockPos(15, 15, 15));
        var removed = section(new BlockPos(1, 0, 0));
        var replacement = section(new BlockPos(2, 0, 0));
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(key(-1, -1), chunk(-1, -1, 4, retained));
        chunks.put(key(0, 0), chunk(0, 0, 4, removed));
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        assertTrue(index.ensureBuilt(world));
        assertEquals(4096, retained.reads);
        retained.reads = 0;
        removed.reads = 0;

        chunks.put(key(0, 0), chunk(0, 0, 4, replacement));
        assertTrue(index.ensureBuilt(world));
        var query = region(0, 0, 0, 512);
        assertCursorEquals(reference(List.of(new BlockPos(-16, 0, -16), new BlockPos(-1, 15, -1),
                        new BlockPos(2, 0, 0)), ALL, query), new IndexedPositionCursor(index, ALL, query));
        retained.reads = replacement.reads = 0;
        assertFalse(index.ensureBuilt(world));
        assertEquals(0, retained.reads + replacement.reads);
    }

    @Test void addingAndRemovingChunksKeepsRetainedAndAddedPositions() throws Exception {
        var retained = section(BlockPos.ZERO);
        var added = section(new BlockPos(15, 15, 15));
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(key(-1, 0), chunk(-1, 0, 0, retained));
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        retained.reads = 0;
        chunks.put(key(0, -1), chunk(0, -1, 23, added));
        assertTrue(index.ensureBuilt(world));
        assertCursorEquals(List.of(new BlockPos(-16, -64, 0), new BlockPos(15, 319, -1)),
                new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
        added.reads = 0;
        chunks.remove(key(-1, 0));
        assertTrue(index.ensureBuilt(world));
        assertCursorEquals(List.of(new BlockPos(15, 319, -1)),
                new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
        chunks.clear();
        assertTrue(index.ensureBuilt(world));
        assertTrue(index.isReady());
        assertCursorEquals(List.of(), new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
    }

    @Test void waitingRecoveryAndWorldSwitchMustRebuildPreviouslyRetainedChunks() throws Exception {
        var first = section(BlockPos.ZERO);
        var second = section(BlockPos.ZERO);
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        var loading = chunk(-1, -1, 4, first);
        chunks.put(key(-1, -1), loading);
        chunks.put(key(0, 0), chunk(0, 0, 4, second));
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        first.reads = second.reads = 0;
        loading.setState(ChunkSchematicState.LOADED);
        assertTrue(index.ensureBuilt(world));
        assertFalse(index.isReady());
        assertEquals(0, first.reads + second.reads);
        assertCursorEquals(List.of(), new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
        assertFalse(index.ensureBuilt(world));
        loading.setState(ChunkSchematicState.FILLED);
        assertTrue(index.ensureBuilt(world));
        assertEquals(4096, first.reads);
        assertEquals(4096, second.reads, "WAITING cleared every bucket, so recovery must rebuild every chunk");
        first.reads = second.reads = 0;
        assertTrue(index.ensureBuilt(SchematicIndexFixture.world(chunks)));
        assertEquals(4096, first.reads);
        assertEquals(4096, second.reads, "Another world cannot reuse the READY index even with identical chunks");
    }

    @Test void replacementDropsAllOldHeightSectionsIncludingWhenNewChunkIsEmpty() throws Exception {
        var sections = new LevelChunkSection[24];
        sections[0] = section(BlockPos.ZERO, new BlockPos(15, 15, 15));
        sections[23] = section(BlockPos.ZERO, new BlockPos(15, 15, 15));
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(key(-1, -1), SchematicIndexFixture.chunk(new ChunkPos(-1, -1), sections));
        chunks.put(key(-1, 0), chunk(-1, 0, 4, section(BlockPos.ZERO)));
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        chunks.put(key(-1, -1), SchematicIndexFixture.chunk(new ChunkPos(-1, -1), new LevelChunkSection[24]));
        assertTrue(index.ensureBuilt(world));
        assertCursorEquals(List.of(new BlockPos(-16, 0, 0)),
                new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
    }

    @Test void anotherChunkReplacementPreservesFullRebuildVisibilityOfInPlaceEdits() throws Exception {
        var edited = section(BlockPos.ZERO);
        var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
        chunks.put(key(-1, 0), chunk(-1, 0, 4, edited));
        chunks.put(key(0, 0), chunk(0, 0, 4, section(BlockPos.ZERO)));
        var world = SchematicIndexFixture.world(chunks);
        var index = new SchematicBlockIndex();
        index.ensureBuilt(world);
        // A live palette can change while its chunk object and FILLED state remain identical.
        // The old full rebuild sees that edit when any other chunk subsequently changes.
        edited.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
        edited.setBlockState(1, 0, 0, Blocks.STONE.defaultBlockState());
        chunks.put(key(0, 0), chunk(0, 0, 4, section(new BlockPos(2, 0, 0))));
        assertTrue(index.ensureBuilt(world));
        var fresh = new SchematicBlockIndex();
        fresh.ensureBuilt(world);
        var query = region(0, 0, 0, 512);
        var expected = List.of(new BlockPos(2, 0, 0), new BlockPos(-15, 0, 0));
        assertCursorEquals(expected, new IndexedPositionCursor(fresh, ALL, query));
        assertCursorEquals(expected, new IndexedPositionCursor(index, ALL, query));
    }

    @Test void randomizedLifecycleMatchesFreshBuildAndIndependentCandidateOrder() throws Exception {
        for (int seed = 0; seed < 4; seed++) {
            var random = new Random(0x1C0FFEE + seed);
            var chunks = new ConcurrentHashMap<Long, ChunkSchematic>();
            var models = new HashMap<Long, ModelChunk>();
            var world = SchematicIndexFixture.world(chunks);
            var index = new SchematicBlockIndex();
            for (int step = 0; step < 100; step++) {
                int x = random.nextInt(5) - 2;
                int z = random.nextInt(5) - 2;
                long key = key(x, z);
                switch (step % 10) {
                    case 0, 1, 2, 3 -> {
                        var model = randomChunk(random, x, z);
                        chunks.put(key, model.chunk);
                        models.put(key, model);
                    }
                    case 4 -> {
                        chunks.remove(key);
                        models.remove(key);
                    }
                    case 5 -> {
                        for (var model : models.values()) {
                            model.filled = random.nextBoolean();
                            model.chunk.setState(model.filled ? ChunkSchematicState.FILLED : ChunkSchematicState.LOADED);
                        }
                    }
                    case 6 -> {
                        for (var model : models.values()) {
                            model.filled = true;
                            model.chunk.setState(ChunkSchematicState.FILLED);
                        }
                    }
                    case 7 -> world = SchematicIndexFixture.world(chunks);
                    case 8 -> {
                        index.ensureBuilt(null);
                        assertFalse(index.isReady());
                        assertCursorEquals(List.of(), new IndexedPositionCursor(index, ALL, region(0, 0, 0, 512)));
                    }
                    case 9 -> {
                        if (random.nextBoolean()) index.clear();
                    }
                }
                index.ensureBuilt(world);
                var fresh = new SchematicBlockIndex();
                fresh.ensureBuilt(world);
                boolean ready = models.values().stream().allMatch(model -> model.filled);
                assertEquals(ready, index.isReady(), "seed=" + seed + ", step=" + step);
                var expected = ready ? models.values().stream().flatMap(model -> model.positions.stream()).toList()
                        : List.<BlockPos>of();
                for (int queryIndex = 0; queryIndex < 3; queryIndex++) {
                    var query = queryIndex == 0 ? region(0, 0, 0, 512)
                            : region(random.nextInt(65) - 32, random.nextInt(384) - 64,
                                    random.nextInt(65) - 32, random.nextInt(100));
                    var boxes = queryIndex == 0 ? ALL : List.of(
                            new PrinterBox(-32, -64, -32, 8, 319, 32),
                            new PrinterBox(-8, -32, -16, 32, 319, 16));
                    var ordered = reference(expected, boxes, query);
                    assertCursorEquals(ordered, new IndexedPositionCursor(fresh, boxes, query));
                    assertCursorEquals(ordered, new IndexedPositionCursor(index, boxes, query));
                }
                assertFalse(index.ensureBuilt(world), "Repeated lifecycle check must keep the snapshot");
            }
        }
    }

    private static ModelChunk randomChunk(Random random, int x, int z) throws Exception {
        var sections = new LevelChunkSection[24];
        var positions = new ArrayList<BlockPos>();
        for (int sectionIndex : new int[]{0, 4, 23}) {
            var local = new ArrayList<BlockPos>();
            for (int n = random.nextInt(9); n > 0; n--) {
                var pos = new BlockPos(random.nextInt(16), random.nextInt(16), random.nextInt(16));
                local.add(pos);
                positions.add(new BlockPos(x * 16 + pos.getX(), -64 + sectionIndex * 16 + pos.getY(),
                        z * 16 + pos.getZ()));
            }
            sections[sectionIndex] = section(local.toArray(BlockPos[]::new));
        }
        return new ModelChunk(SchematicIndexFixture.chunk(new ChunkPos(x, z), sections), positions);
    }

    private static ChunkSchematic chunk(int x, int z, int sectionIndex, LevelChunkSection section) throws Exception {
        var sections = new LevelChunkSection[24];
        sections[sectionIndex] = section;
        return SchematicIndexFixture.chunk(new ChunkPos(x, z), sections);
    }

    private static CountingSection section(BlockPos... positions) {
        var section = new CountingSection();
        for (var pos : positions) section.setBlockState(pos.getX(), pos.getY(), pos.getZ(), Blocks.STONE.defaultBlockState());
        return section;
    }

    private static long key(int x, int z) { return (x & 0xFFFFFFFFL) | ((long) z << 32); }

    private static final class CountingSection extends LevelChunkSection {
        int reads;

        CountingSection() {
            super(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        }

        @Override public BlockState getBlockState(int x, int y, int z) {
            this.reads++;
            return super.getBlockState(x, y, z);
        }
    }

    private static final class ModelChunk {
        final ChunkSchematic chunk;
        final List<BlockPos> positions;
        boolean filled = true;

        ModelChunk(ChunkSchematic chunk, List<BlockPos> positions) {
            this.chunk = chunk;
            this.positions = positions;
        }
    }
}
//#endif
