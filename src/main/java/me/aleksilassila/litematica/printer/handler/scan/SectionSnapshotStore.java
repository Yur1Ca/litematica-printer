package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Compact incremental classification cache.
 *
 * <p>The scanner only consumes classification flags. Retaining two 4096-entry BlockState arrays
 * per visited section made large schematic selections retain hundreds of megabytes, so this
 * store keeps one byte per position and intent plus a known-zero bitset. A nonzero flag
 * already proves observation; the bitset distinguishes observed zero from unknown. Payload
 * bytes and section count are bounded independently, and eviction returns every fact in the
 * evicted section to unknown.</p>
 */
final class SectionSnapshotStore {
    private static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_MAX_SECTIONS = 4096;
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int PRINT_EXTRA_SLOT = ScanIntent.values().length;
    private static final int CACHE_SLOTS = PRINT_EXTRA_SLOT + 1;
    private static final int OBSERVED_WORDS = SECTION_VOLUME / Long.SIZE;
    private static final int SLOT_BYTES = SECTION_VOLUME + OBSERVED_WORDS * Long.BYTES;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final Long2ObjectLinkedOpenHashMap<MutableSection> sections = new Long2ObjectLinkedOpenHashMap<>();
    private final int maxBytes;
    private final int maxSections;
    private int retainedBytes;

    SectionSnapshotStore() {
        this(DEFAULT_MAX_BYTES);
    }

    SectionSnapshotStore(int maxBytes) {
        this(maxBytes, DEFAULT_MAX_SECTIONS);
    }

    SectionSnapshotStore(int maxBytes, int maxSections) {
        this.maxBytes = Math.max(0, maxBytes);
        this.maxSections = Math.max(0, maxSections);
    }

    byte classify(BlockPos pos, ScanIntent intent, boolean breakExtraBlocks, WorldObservationPort source) {
        long key = sectionKey(pos);
        MutableSection section = this.sections.getAndMoveToLast(key);
        int index = index(pos);
        int cacheSlot = cacheSlot(intent, breakExtraBlocks);
        if (section != null && section.isObserved(cacheSlot, index)) {
            return section.flags[cacheSlot][index];
        }
        // Classify before allocating or evicting: a failed observation must not publish facts.
        byte flags = source.classify(intent, pos, breakExtraBlocks);
        this.cache(key, cacheSlot, index, flags);
        return flags;
    }

    boolean hasPotentialOrUnknown(long sectionKey, ScanIntent intent, boolean breakExtraBlocks) {
        MutableSection section = this.sections.getAndMoveToLast(sectionKey);
        int slot = cacheSlot(intent, breakExtraBlocks);
        return section == null || section.observedCounts[slot] < SECTION_VOLUME
                || section.nonZeroCounts[slot] != 0;
    }

    boolean isKnownZero(BlockPos pos, ScanIntent intent, boolean breakExtraBlocks) {
        MutableSection section = this.sections.getAndMoveToLast(sectionKey(pos));
        return section != null && section.isKnownZero(cacheSlot(intent, breakExtraBlocks), index(pos));
    }

    int retainedBytes() {
        return this.retainedBytes;
    }

    int retainedSectionCount() {
        return this.sections.size();
    }

    void invalidateWorld(BlockPos pos) {
        if (pos == null) return;
        this.invalidateCell(pos);
        for (Direction direction : DIRECTIONS) this.invalidateCell(pos.relative(direction));
    }

    void invalidateChunk(int chunkX, int chunkZ, int minY, int maxY) {
        if (minY > maxY) return;
        for (int sectionY = ScanGeometry.sectionCoord(minY); sectionY <= ScanGeometry.sectionCoord(maxY); sectionY++) {
            this.invalidateSection(ScanGeometry.sectionKey(chunkX, sectionY, chunkZ));
            // Only the touching face of an adjacent chunk can depend on the replaced blocks.
            this.invalidateFace(chunkX - 1, sectionY, chunkZ, true, 15);
            this.invalidateFace(chunkX + 1, sectionY, chunkZ, true, 0);
            this.invalidateFace(chunkX, sectionY, chunkZ - 1, false, 15);
            this.invalidateFace(chunkX, sectionY, chunkZ + 1, false, 0);
        }
    }

    void clear() {
        this.sections.clear();
        this.retainedBytes = 0;
    }

    void invalidateSection(long sectionKey) {
        MutableSection section = this.sections.remove(sectionKey);
        if (section != null) this.retainedBytes -= section.payloadBytes;
    }

    void invalidateSection(long sectionKey, ScanIntent intent, boolean breakExtraBlocks) {
        MutableSection section = this.sections.get(sectionKey);
        if (section == null) return;
        this.retainedBytes -= section.clearSlot(cacheSlot(intent, breakExtraBlocks));
        if (section.payloadBytes == 0) this.sections.remove(sectionKey);
    }

    void invalidatePrintChunk(int chunkX, int chunkZ, int minY, int maxY) {
        if (minY > maxY) return;
        for (int sectionY = ScanGeometry.sectionCoord(minY); sectionY <= ScanGeometry.sectionCoord(maxY); sectionY++) {
            long key = ScanGeometry.sectionKey(chunkX, sectionY, chunkZ);
            this.invalidateSection(key, ScanIntent.PRINT, false);
            this.invalidateSection(key, ScanIntent.PRINT, true);
        }
    }

    void invalidatePrint() {
        var iterator = this.sections.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            MutableSection section = iterator.next().getValue();
            this.retainedBytes -= section.clearSlot(cacheSlot(ScanIntent.PRINT, false));
            this.retainedBytes -= section.clearSlot(cacheSlot(ScanIntent.PRINT, true));
            if (section.payloadBytes == 0) iterator.remove();
        }
    }

    /** Borrowed, client-thread-only mask: only observed zero flags may be excluded from PRINT. */
    long[] excludedPrintPositions(long sectionKey) {
        MutableSection section = this.sections.getAndMoveToLast(sectionKey);
        return section == null ? null : section.knownZero[ScanIntent.PRINT.ordinal()];
    }

    private void cache(long key, int slot, int index, byte flags) {
        // A budget smaller than one complete slot behaves as an uncached observation port.
        if (this.maxBytes < SLOT_BYTES || this.maxSections == 0) return;
        MutableSection section = this.sections.getAndMoveToLast(key);
        if (section == null || section.flags[slot] == null) {
            while (this.retainedBytes > this.maxBytes - SLOT_BYTES
                    || section == null && this.sections.size() >= this.maxSections) {
                MutableSection evicted = this.sections.removeFirst();
                this.retainedBytes -= evicted.payloadBytes;
                if (evicted == section) section = null;
            }
            if (section == null) {
                section = new MutableSection();
                this.sections.putAndMoveToLast(key, section);
            }
            section.ensureSlot(slot);
            this.retainedBytes += SLOT_BYTES;
        }
        section.put(slot, index, flags);
    }

    private void invalidateCell(BlockPos pos) {
        MutableSection section = this.sections.get(sectionKey(pos));
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
        return intent == ScanIntent.PRINT && breakExtraBlocks ? PRINT_EXTRA_SLOT : intent.ordinal();
    }

    private static int index(BlockPos pos) {
        return (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | pos.getX() & 15;
    }

    private static long sectionKey(BlockPos pos) {
        return ScanGeometry.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private static final class MutableSection {
        private final byte[][] flags = new byte[CACHE_SLOTS][];
        private final long[][] knownZero = new long[CACHE_SLOTS][];
        private final int[] observedCounts = new int[CACHE_SLOTS];
        private final int[] nonZeroCounts = new int[CACHE_SLOTS];
        private int payloadBytes;

        private void ensureSlot(int slot) {
            if (this.flags[slot] == null) {
                this.flags[slot] = new byte[SECTION_VOLUME];
                this.knownZero[slot] = new long[OBSERVED_WORDS];
                this.payloadBytes += SLOT_BYTES;
            }
        }

        private int clearSlot(int slot) {
            if (this.flags[slot] == null) return 0;
            this.flags[slot] = null;
            this.knownZero[slot] = null;
            this.observedCounts[slot] = 0;
            this.nonZeroCounts[slot] = 0;
            this.payloadBytes -= SLOT_BYTES;
            return SLOT_BYTES;
        }

        private boolean isObserved(int slot, int index) {
            if (this.flags[slot] == null) return false;
            if (this.flags[slot][index] != 0) return true;
            return this.isKnownZero(slot, index);
        }

        private boolean isKnownZero(int slot, int index) {
            return this.knownZero[slot] != null
                    && (this.knownZero[slot][index >>> 6] & 1L << (index & 63)) != 0L;
        }

        private void put(int slot, int index, byte value) {
            if (!this.isObserved(slot, index)) this.observedCounts[slot]++;
            if (this.flags[slot][index] != 0) this.nonZeroCounts[slot]--;
            this.flags[slot][index] = value;
            if (value != 0) this.nonZeroCounts[slot]++;
            long bit = 1L << (index & 63);
            if (value == 0) this.knownZero[slot][index >>> 6] |= bit;
            else this.knownZero[slot][index >>> 6] &= ~bit;
        }

        private void invalidate(int index) {
            long clearMask = ~(1L << (index & 63));
            int word = index >>> 6;
            for (int slot = 0; slot < CACHE_SLOTS; slot++) {
                if (this.isObserved(slot, index)) {
                    this.observedCounts[slot]--;
                    if (this.flags[slot][index] != 0) this.nonZeroCounts[slot]--;
                    this.knownZero[slot][word] &= clearMask;
                    this.flags[slot][index] = 0;
                }
            }
        }

    }
}
