package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Sparse index of non-air positions in one schematic world.
 *
 * <p>The PRINT scanner only needs schematic non-air positions. Keeping those positions in chunk
 * buckets avoids walking the air volume of a large, sparse placement. The index is deliberately
 * isolated from world intents: MINE, FILL and FLUID must continue to inspect live world state.</p>
 */
final class SchematicBlockIndex {
    private static final int SECTION_SIZE = 16;

    private final LongOpenHashSet nonAirPositions = new LongOpenHashSet();
    private WorldSchematic indexedSchematic;
    private int indexedChunkCount;
    private State state = State.STALE;

    enum State {
        STALE,
        BUILDING,
        READY
    }

    boolean ensureBuilt(WorldSchematic schematic) {
        if (schematic == null) {
            boolean changed = this.state != State.STALE || this.indexedSchematic != null;
            this.clear();
            return changed;
        }
        int loadedChunkCount = getLoadedChunkCount(schematic);
        if (this.state == State.READY
                && this.indexedSchematic == schematic
                && this.indexedChunkCount == loadedChunkCount) {
            return false;
        }
        if (this.state == State.BUILDING) {
            return false;
        }
        this.build(schematic, loadedChunkCount);
        return true;
    }

    boolean isReady() {
        return this.state == State.READY;
    }

    LongOpenHashSet positions() {
        return this.nonAirPositions;
    }

    void clear() {
        this.nonAirPositions.clear();
        this.indexedSchematic = null;
        this.indexedChunkCount = 0;
        this.state = State.STALE;
    }

    private void build(WorldSchematic schematic, int loadedChunkCount) {
        this.state = State.BUILDING;
        this.nonAirPositions.clear();
        this.indexedSchematic = schematic;

        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        int bottomY = schematic.getMinY();
        int processedChunks = 0;
        //#if MC >= 12111
        for (ChunkSchematic chunk : chunkManager.getLoadedValueSet()) {
        //#else
        //$$ for (ChunkSchematic chunk : chunkManager.getLoadedChunks().values()) {
        //#endif
            if (chunk == null) {
                continue;
            }
            processedChunks++;
            ChunkPos chunkPos = chunk.getPos();
            //#if MC >= 260100
            int chunkX = chunkPos.x();
            int chunkZ = chunkPos.z();
            //#else
            //$$ int chunkX = chunkPos.x;
            //$$ int chunkZ = chunkPos.z;
            //#endif
            LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                int sectionBaseY = bottomY + (sectionIndex << 4);
                for (int y = 0; y < SECTION_SIZE; y++) {
                    for (int z = 0; z < SECTION_SIZE; z++) {
                        for (int x = 0; x < SECTION_SIZE; x++) {
                            if (!section.getBlockState(x, y, z).isAir()) {
                                this.nonAirPositions.add(BlockPos.asLong(
                                        (chunkX << 4) + x,
                                        sectionBaseY + y,
                                        (chunkZ << 4) + z
                                ));
                            }
                        }
                    }
                }
            }
        }
        this.indexedChunkCount = processedChunks;
        this.state = State.READY;
    }

    private static int getLoadedChunkCount(WorldSchematic schematic) {
        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        //#if MC >= 12111
        return chunkManager.getLoadedValueSet().size();
        //#else
        //$$ return chunkManager.getLoadedChunks().size();
        //#endif
    }
}
