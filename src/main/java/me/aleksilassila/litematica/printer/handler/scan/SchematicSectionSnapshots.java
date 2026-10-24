package me.aleksilassila.litematica.printer.handler.scan;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Exact palette-content comparisons used only when the schematic index needs rebuilding. */
final class SchematicSectionSnapshots {
    // Bounded extra storage; a missing snapshot always causes a normal section rebuild.
    private static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;
    private final int maxBytes;
    private final Long2ObjectOpenHashMap<byte[]> contents = new Long2ObjectOpenHashMap<>();
    private int retainedBytes;

    SchematicSectionSnapshots() { this(DEFAULT_MAX_BYTES); }

    SchematicSectionSnapshots(int maxBytes) { this.maxBytes = Math.max(0, maxBytes); }

    void clear() {
        this.contents.clear();
        this.retainedBytes = 0;
    }

    void retainSections(LongSet sections) {
        var iterator = this.contents.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!sections.contains(entry.getLongKey())) {
                this.retainedBytes -= entry.getValue().length;
                iterator.remove();
            }
        }
    }

    Comparison compare() { return new Comparison(); }

    final class Comparison implements AutoCloseable {
        private final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        private byte[] scratch = new byte[0];

        boolean unchanged(long sectionKey, LevelChunkSection section) {
            // Chunk identity/FILLED is not proof of unchanged contents: Litematica may edit a
            // retained palette in place. Compare every serialized byte, never just a hash.
            this.buffer.clear();
            section.getStates().write(this.buffer);
            int size = this.buffer.writerIndex();
            if (this.scratch.length < size) this.scratch = new byte[size];
            this.buffer.getBytes(0, this.scratch, 0, size);
            byte[] previous = contents.get(sectionKey);
            if (previous != null && previous.length == size
                    && Arrays.equals(previous, 0, size, this.scratch, 0, size)) return true;
            if (previous != null) {
                contents.remove(sectionKey);
                retainedBytes -= previous.length;
            }
            if (size <= maxBytes - retainedBytes) {
                contents.put(sectionKey, Arrays.copyOf(this.scratch, size));
                retainedBytes += size;
            }
            return false;
        }

        @Override public void close() { this.buffer.release(); }
    }
}
