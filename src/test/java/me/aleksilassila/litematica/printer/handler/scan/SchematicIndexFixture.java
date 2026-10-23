//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/** Headless world shells; all measured chunk, palette, registry and index methods remain real. */
final class SchematicIndexFixture {
    final WorldSchematic world;
    final ConcurrentHashMap<Long, ChunkSchematic> chunks = new ConcurrentHashMap<>();
    final int count;
    final int positionsPerChunk;

    SchematicIndexFixture(int count, int positionsPerChunk) throws Exception {
        this.count = count;
        this.positionsPerChunk = positionsPerChunk;
        this.world = world(this.chunks);
        for (int i = 0; i < count; i++) {
            this.chunks.put(key(i), chunk(i, positionsPerChunk));
        }
    }

    static WorldSchematic world(ConcurrentHashMap<Long, ChunkSchematic> chunks) throws Exception {
        var world = allocate(WorldSchematic.class);
        var manager = allocate(ChunkManagerSchematic.class);
        set(manager, "loadedChunks", chunks);
        set(world, "chunkManagerSchematic", manager);
        // getMinY() uses Litematica's actual no-client-world default (-64).
        set(world, "mc", allocate(Minecraft.class));
        return world;
    }

    static ChunkSchematic chunk(int ordinal, int positions) throws Exception {
        var section = new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        for (int i = 0; i < positions; i++) {
            section.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15,
                    (i % 3 == 0 ? Blocks.COBBLESTONE : Blocks.STONE).defaultBlockState());
        }
        var sections = new LevelChunkSection[24];
        sections[4] = section;
        return chunk(new ChunkPos(x(ordinal), z(ordinal)), sections);
    }

    static ChunkSchematic chunk(ChunkPos position, LevelChunkSection[] sections) throws Exception {
        return chunk(ChunkSchematic.class, position, sections);
    }

    static <T extends ChunkSchematic> T chunk(Class<T> type, ChunkPos position, LevelChunkSection[] sections) throws Exception {
        var chunk = allocate(type);
        set(chunk, "chunkPos", position);
        set(chunk, "sections", sections);
        chunk.setState(ChunkSchematicState.FILLED);
        return chunk;
    }

    static long key(int ordinal) { return (x(ordinal) & 0xFFFFFFFFL) | ((long) z(ordinal) << 32); }
    private static int x(int ordinal) { return (ordinal % 64) - 32; }
    private static int z(int ordinal) { return (ordinal / 64) - 32; }

    static List<Long> positions(SchematicBlockIndex index) {
        var result = new java.util.ArrayList<Long>();
        index.forEachInRange(List.of(new PrinterBox(-1024, -64, -1024, 1024, 320, 1024)),
                new ScanRegion(0, 0, 0, -64, -4, -64, 64, 20, 64, 4096), result::add);
        return result;
    }

    static long positionCount(SchematicBlockIndex index) {
        long[] count = {0};
        index.forEachInRange(List.of(new PrinterBox(-1024, -64, -1024, 1024, 320, 1024)),
                new ScanRegion(0, 0, 0, -64, -4, -64, 64, 20, 64, 4096), p -> count[0]++);
        return count[0];
    }

    static BlockPos firstPosition(int ordinal) { return new BlockPos(x(ordinal) * 16, 0, z(ordinal) * 16); }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Minecraft keeps position/sections in the superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
//#endif
