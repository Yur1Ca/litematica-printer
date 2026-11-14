package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;

import java.util.function.BooleanSupplier;

/** Bounded per-session LRU cache for the expensive schematic-chunk candidate lookup. */
final class ChunkCandidateCache {
    private static final int DEFAULT_MAX_ENTRIES = 1024;
    private final Long2ByteLinkedOpenHashMap values = new Long2ByteLinkedOpenHashMap();
    private final int maxEntries;

    ChunkCandidateCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    ChunkCandidateCache(int maxEntries) {
        this.maxEntries = Math.max(0, maxEntries);
        this.values.defaultReturnValue((byte) -1);
    }

    boolean getOrCompute(int chunkX, int chunkZ, BooleanSupplier source) {
        long key = key(chunkX, chunkZ);
        byte cached = this.values.getAndMoveToLast(key);
        if (cached == -1) {
            cached = (byte) (source.getAsBoolean() ? 1 : 0);
            if (this.maxEntries > 0) {
                if (this.values.size() >= this.maxEntries) this.values.removeFirstByte();
                this.values.putAndMoveToLast(key, cached);
            }
        }
        return cached == 1;
    }

    void invalidate(int chunkX, int chunkZ) {
        this.values.remove(key(chunkX, chunkZ));
    }

    void clear() {
        this.values.clear();
    }

    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 ^ chunkZ & 0xffffffffL;
    }
}
