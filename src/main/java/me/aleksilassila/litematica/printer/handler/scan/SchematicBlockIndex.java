package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkManagerSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
//#if MC >= 12111
import fi.dy.masa.litematica.world.ChunkSchematicState;
//#endif
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason;

/**
 * Sparse index of non-air positions in one schematic world.
 *
 * <p>The PRINT scanner only needs schematic non-air positions. Keeping those positions in chunk
 * buckets avoids walking the air volume of a large, sparse placement. The index is deliberately
 * isolated from world intents: MINE, FILL and FLUID must continue to inspect live world state.</p>
 */
final class SchematicBlockIndex implements SchematicPositionQuery {
    private static final int SECTION_SIZE = 16;

    private final SectionPositionIndex nonAirPositions = new SectionPositionIndex();
    private final SchematicSectionSnapshots sectionSnapshots = new SchematicSectionSnapshots();
    private WorldSchematic indexedSchematic;
    private List<ChunkStamp> indexedChunks = List.of();
    private State state = State.STALE;

    enum State {
        STALE,
        WAITING,
        BUILDING,
        READY
    }

    boolean ensureBuilt(WorldSchematic schematic) {
        return this.ensureBuilt(schematic, RuntimeTiming.disabled(), "print");
    }

    boolean ensureBuilt(WorldSchematic schematic, RuntimeTiming timing, String owner) {
        return this.ensureBuilt(schematic, timing, owner, true);
    }

    boolean ensureBuilt(WorldSchematic schematic, RuntimeTiming timing, String owner, boolean buildSparseIndex) {
        long checkStart = timing.start();
        try {
            if (schematic == null) {
                boolean changed = this.state != State.STALE || this.indexedSchematic != null;
                this.clear();
                return changed;
            }
            //#if MC >= 12111
            return buildSparseIndex ? this.ensureBuiltWithStableChunkStates(schematic, timing, owner)
                    : this.waitForDenseFallback(schematic);
            //#else
            // Litematica versions before 1.21.11 do not expose the LOADED -> FILLED transition.
            // Their chunk identity and non-empty flag can become stable before population completes,
            // so sparse indexing cannot prove that its snapshot is complete. Keep the dense cursor.
            //$$ return this.waitForDenseFallback(schematic);
            //#endif
        } finally {
            timing.finish(owner, Stage.INDEX_CHECK, checkStart);
            if (this.state == State.WAITING) timing.count(owner, Reason.INDEX_WAITING, 1);
        }
    }

    private boolean ensureBuiltWithStableChunkStates(WorldSchematic schematic, RuntimeTiming timing, String owner) {
        LoadedChunks loadedChunks = captureLoadedChunks(schematic);
        if (!loadedChunks.allFilled()) {
            boolean changed = this.indexedSchematic != schematic
                    || this.state != State.WAITING
                    || !sameChunkSnapshot(this.indexedChunks, loadedChunks.stamps());
            this.nonAirPositions.clear();
            this.sectionSnapshots.clear();
            this.indexedSchematic = schematic;
            this.indexedChunks = loadedChunks.stamps();
            this.state = State.WAITING;
            return changed;
        }
        if (this.state == State.READY
                && this.indexedSchematic == schematic
                && sameChunkSnapshot(this.indexedChunks, loadedChunks.stamps())) {
            return false;
        }
        if (this.state == State.BUILDING) {
            return false;
        }
        long buildStart = timing.start();
        timing.count(owner, Reason.INDEX_REBUILT, 1);
        try {
            this.build(schematic, loadedChunks);
        } finally {
            timing.finish(owner, Stage.INDEX_BUILD, buildStart);
        }
        return true;
    }

    private boolean waitForDenseFallback(WorldSchematic schematic) {
        LoadedChunks loadedChunks = captureLoadedChunks(schematic);
        boolean changed = this.indexedSchematic != schematic || this.state != State.WAITING
                || !sameChunkSnapshot(this.indexedChunks, loadedChunks.stamps());
        this.nonAirPositions.clear();
        this.sectionSnapshots.clear();
        this.indexedSchematic = schematic;
        this.indexedChunks = loadedChunks.stamps();
        this.state = State.WAITING;
        return changed;
    }

    boolean isReady() {
        return this.state == State.READY;
    }

    @Override
    public long forEachInRange(List<PrinterBox> boxes, ScanRegion region, LongConsumer consumer) {
        return this.nonAirPositions.forEachInRange(boxes, region, consumer);
    }

    SchematicPositionQuery unresolvedPrintQuery(SectionSnapshotStore snapshots) {
        return (boxes, region, consumer) -> this.nonAirPositions.forEachInRange(
                boxes, region, snapshots::excludedPrintPositions, consumer);
    }

    void clear() {
        this.nonAirPositions.clear();
        this.sectionSnapshots.clear();
        this.indexedSchematic = null;
        this.indexedChunks = List.of();
        this.state = State.STALE;
    }

    private void build(WorldSchematic schematic, LoadedChunks loadedChunks) {
        boolean reuse = this.state == State.READY && this.indexedSchematic == schematic;
        this.state = State.BUILDING;
        if (!reuse) {
            this.nonAirPositions.clear();
            this.sectionSnapshots.clear();
        }
        this.indexedSchematic = schematic;

        int bottomY = schematic.getMinY();
        LongOpenHashSet retainedSections = new LongOpenHashSet();
        try (var comparison = this.sectionSnapshots.compare()) {
            for (ChunkSchematic chunk : loadedChunks.chunks()) {
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
                    long sectionKey = ScanGeometry.sectionKey(chunkX, sectionBaseY >> 4, chunkZ);
                    retainedSections.add(sectionKey);
                    if (comparison.unchanged(sectionKey, section)) continue;
                    this.nonAirPositions.removeSection(sectionKey);
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
        }
        this.nonAirPositions.retainSections(retainedSections);
        this.sectionSnapshots.retainSections(retainedSections);
        this.indexedChunks = loadedChunks.stamps();
        this.state = State.READY;
    }

    private static LoadedChunks captureLoadedChunks(WorldSchematic schematic) {
        ChunkManagerSchematic chunkManager = (ChunkManagerSchematic) schematic.getChunkSource();
        List<ChunkSchematic> chunks = new ArrayList<>();
        List<ChunkStamp> stamps = new ArrayList<>();
        boolean allFilled = true;
        //#if MC >= 12111
        for (ChunkSchematic chunk : chunkManager.getLoadedValueSet()) {
        //#else
        //$$ for (ChunkSchematic chunk : chunkManager.getLoadedChunks().values()) {
        //#endif
            if (chunk == null) {
                continue;
            }
            ChunkPos chunkPos = chunk.getPos();
            //#if MC >= 260100
            int chunkX = chunkPos.x();
            int chunkZ = chunkPos.z();
            //#else
            //$$ int chunkX = chunkPos.x;
            //$$ int chunkZ = chunkPos.z;
            //#endif
            //#if MC >= 12111
            boolean filled = chunk.getState().atLeast(ChunkSchematicState.FILLED);
            //#else
            // Older versions never call the sparse build path; keep this false as a defensive
            // fallback if the capture helper is reused by future code.
            //$$ boolean filled = false;
            //#endif
            chunks.add(chunk);
            stamps.add(new ChunkStamp(chunkKey(chunkX, chunkZ), chunk, filled));
            allFilled &= filled;
        }
        stamps.sort(Comparator.comparingLong(ChunkStamp::key));
        return new LoadedChunks(List.copyOf(chunks), List.copyOf(stamps), allFilled);
    }

    static boolean sameChunkSnapshot(List<ChunkStamp> first, List<ChunkStamp> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            ChunkStamp left = first.get(index);
            ChunkStamp right = second.get(index);
            if (left.key() != right.key()
                    || left.identity() != right.identity()
                    || left.filled() != right.filled()) {
                return false;
            }
        }
        return true;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    static record ChunkStamp(long key, Object identity, boolean filled) {
    }

    private record LoadedChunks(List<ChunkSchematic> chunks, List<ChunkStamp> stamps, boolean allFilled) {
    }
}
