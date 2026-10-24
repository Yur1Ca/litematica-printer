package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Compact incremental classification cache.
 *
 * <p>The scanner only consumes classification flags. Retaining two 4096-entry BlockState arrays
 * per visited section made large schematic selections retain hundreds of megabytes, so this
 * store keeps one byte per position and intent plus a known-zero bitset. A nonzero flag
 * already proves observation; the bitset distinguishes observed zero from unknown.</p>
 */
final class SectionSnapshotStore {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Long2ObjectOpenHashMap<MutableSection> sections = new Long2ObjectOpenHashMap<>();

    byte classify(BlockPos pos, ScanIntent intent, boolean breakExtraBlocks, WorldObservationPort source) {
        MutableSection section = this.section(pos);
        int index = index(pos);
        int cacheSlot = cacheSlot(intent, breakExtraBlocks);
        if (!section.isObserved(cacheSlot, index)) {
            section.put(cacheSlot, index, source.classify(intent, pos, breakExtraBlocks));
        }
        return section.flags(cacheSlot, index);
    }

    void invalidateWorld(BlockPos pos) {
        if (pos == null) return;
        this.invalidateCell(pos);
        for (Direction direction : DIRECTIONS) this.invalidateCell(pos.relative(direction));
    }

    void invalidateChunk(int chunkX, int chunkZ, int minY, int maxY) {
        for (int sectionY = ScanGeometry.sectionCoord(minY); sectionY <= ScanGeometry.sectionCoord(maxY); sectionY++) {
            this.sections.remove(ScanGeometry.sectionKey(chunkX, sectionY, chunkZ));
            // Only the touching face of an adjacent chunk can depend on the replaced blocks.
            this.invalidateFace(chunkX - 1, sectionY, chunkZ, true, 15);
            this.invalidateFace(chunkX + 1, sectionY, chunkZ, true, 0);
            this.invalidateFace(chunkX, sectionY, chunkZ - 1, false, 15);
            this.invalidateFace(chunkX, sectionY, chunkZ + 1, false, 0);
        }
    }

    void clear() {
        this.sections.clear();
    }

    void invalidatePrint() {
        for (MutableSection section : this.sections.values()) {
            section.clearSlot(cacheSlot(ScanIntent.PRINT, false));
            section.clearSlot(cacheSlot(ScanIntent.PRINT, true));
        }
    }

    int cachedSectionCount() {
        return this.sections.size();
    }

    /** Borrowed, client-thread-only mask: only observed zero flags may be excluded from PRINT. */
    long[] excludedPrintPositions(long sectionKey) {
        MutableSection section = this.sections.get(sectionKey);
        return section == null ? null : section.knownZero[ScanIntent.PRINT.ordinal()];
    }

    private MutableSection section(BlockPos pos) {
        long key = ScanGeometry.sectionKey(
                ScanGeometry.sectionCoord(pos.getX()),
                ScanGeometry.sectionCoord(pos.getY()),
                ScanGeometry.sectionCoord(pos.getZ())
        );
        return this.sections.computeIfAbsent(key, ignored -> new MutableSection());
    }

    private void invalidateCell(BlockPos pos) {
        long key = ScanGeometry.sectionKey(
                ScanGeometry.sectionCoord(pos.getX()),
                ScanGeometry.sectionCoord(pos.getY()),
                ScanGeometry.sectionCoord(pos.getZ())
        );
        MutableSection section = this.sections.get(key);
        if (section != null) section.invalidate(index(pos));
    }

    private void invalidateFace(int sectionX, int sectionY, int sectionZ, boolean xFace, int localCoord) {
        MutableSection section = this.sections.get(ScanGeometry.sectionKey(sectionX, sectionY, sectionZ));
        if (section == null) return;
        for (int y = 0; y < 16; y++) {
            for (int other = 0; other < 16; other++) {
                int index = xFace ? y << 8 | other << 4 | localCoord : y << 8 | localCoord << 4 | other;
                section.invalidate(index);
            }
        }
    }

    private static int cacheSlot(ScanIntent intent, boolean breakExtraBlocks) {
        return intent == ScanIntent.PRINT && breakExtraBlocks ? ScanIntent.values().length : intent.ordinal();
    }

    private static int index(BlockPos pos) {
        return (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
    }

    private static final class MutableSection {
        private static final int SECTION_VOLUME = 16 * 16 * 16;
        private static final int CACHE_SLOTS = ScanIntent.values().length + 1;
        private static final int OBSERVED_WORDS = SECTION_VOLUME / Long.SIZE;
        private final byte[][] flags = new byte[CACHE_SLOTS][];
        private final long[][] knownZero = new long[CACHE_SLOTS][];

        private void ensureSlot(int slot) {
            if (this.flags[slot] == null) {
                this.flags[slot] = new byte[SECTION_VOLUME];
                this.knownZero[slot] = new long[OBSERVED_WORDS];
            }
        }

        private void clearSlot(int slot) {
            this.flags[slot] = null;
            this.knownZero[slot] = null;
        }

        private boolean isObserved(int slot, int index) {
            if (this.flags[slot] == null) return false;
            if (this.flags[slot][index] != 0) return true;
            long bit = 1L << (index & 63);
            int word = index >>> 6;
            return (this.knownZero[slot][word] & bit) != 0L;
        }

        private void put(int slot, int index, byte value) {
            this.ensureSlot(slot);
            this.flags[slot][index] = value;
            long bit = 1L << (index & 63);
            if (value == 0) this.knownZero[slot][index >>> 6] |= bit;
            else this.knownZero[slot][index >>> 6] &= ~bit;
        }

        private byte flags(int slot, int index) {
            return this.flags[slot][index];
        }

        private void invalidate(int index) {
            long clearMask = ~(1L << (index & 63));
            int word = index >>> 6;
            for (int slot = 0; slot < CACHE_SLOTS; slot++) {
                if (this.knownZero[slot] != null) {
                    this.knownZero[slot][word] &= clearMask;
                    this.flags[slot][index] = 0;
                }
            }
        }

    }
}
