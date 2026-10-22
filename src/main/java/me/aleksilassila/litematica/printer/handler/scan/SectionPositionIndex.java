package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.List;
import java.util.function.LongConsumer;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

/** Position storage owned by one schematic index; mutation stays on the client thread. */
final class SectionPositionIndex implements SchematicPositionQuery {
    // Most sections of a sparse placement may contain only one position. Store that first
    // position directly, allocating a set only when the section actually needs more entries.
    private final Long2LongOpenHashMap firstPositions = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> additionalPositions = new Long2ObjectOpenHashMap<>();

    void add(long position) {
        int x = BlockPos.getX(position) >> 4;
        int y = BlockPos.getY(position) >> 4;
        int z = BlockPos.getZ(position) >> 4;
        long key = ScanGeometry.sectionKey(x, y, z);
        if (!this.firstPositions.containsKey(key)) {
            this.firstPositions.put(key, position);
            return;
        }
        if (this.firstPositions.get(key) == position) return;
        LongOpenHashSet additional = this.additionalPositions.get(key);
        if (additional == null) {
            additional = new LongOpenHashSet(2);
            this.additionalPositions.put(key, additional);
        }
        additional.add(position);
    }

    void clear() {
        this.firstPositions.clear();
        this.additionalPositions.clear();
    }

    @Override
    public long forEachInRange(List<PrinterBox> boxes, ScanRegion region, LongConsumer consumer) {
        if (this.firstPositions.isEmpty() || boxes.isEmpty()) return 0;
        SectionBounds bounds = SectionBounds.intersect(boxes, region);
        if (bounds == null) return 0;
        long visited = 0;
        // A huge sparse selection must not turn into a walk through billions of empty sections.
        // Local queries use direct lookup; wider queries inspect only existing bucket headers.
        if (bounds.volumeAtMost(this.firstPositions.size())) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    for (int x = bounds.minX; x <= bounds.maxX; x++) {
                        long key = ScanGeometry.sectionKey(x, y, z);
                        if (this.firstPositions.containsKey(key)) {
                            long first = this.firstPositions.get(key);
                            // A query outside packed-coordinate limits must not alias a valid key.
                            if ((BlockPos.getX(first) >> 4) == x && (BlockPos.getY(first) >> 4) == y
                                    && (BlockPos.getZ(first) >> 4) == z && intersects(x, y, z, boxes)) {
                                visited += this.visit(key, first, consumer);
                            }
                        }
                    }
                }
            }
        } else {
            var iterator = this.firstPositions.long2LongEntrySet().fastIterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long first = entry.getLongValue();
                int x = BlockPos.getX(first) >> 4;
                int y = BlockPos.getY(first) >> 4;
                int z = BlockPos.getZ(first) >> 4;
                if (bounds.contains(x, y, z) && intersects(x, y, z, boxes)) {
                    visited += this.visit(entry.getLongKey(), first, consumer);
                }
            }
        }
        return visited;
    }

    private static boolean intersects(int x, int y, int z, List<PrinterBox> boxes) {
        long blockX = (long) x << 4;
        long blockY = (long) y << 4;
        long blockZ = (long) z << 4;
        for (PrinterBox box : boxes) {
            if (blockX <= box.maxX && blockX + 15 >= box.minX
                    && blockY <= box.maxY && blockY + 15 >= box.minY
                    && blockZ <= box.maxZ && blockZ + 15 >= box.minZ) return true;
        }
        return false;
    }

    private long visit(long key, long first, LongConsumer consumer) {
        consumer.accept(first);
        LongOpenHashSet additional = this.additionalPositions.get(key);
        if (additional == null) return 1;
        var iterator = additional.iterator();
        while (iterator.hasNext()) consumer.accept(iterator.nextLong());
        return 1L + additional.size();
    }

    private record SectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static SectionBounds intersect(List<PrinterBox> boxes, ScanRegion region) {
            long radius = Math.abs((long) region.maxDistanceBand());
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (PrinterBox box : boxes) {
                long x0 = Math.max(box.minX, (long) region.centerX() - radius);
                long y0 = Math.max(box.minY, (long) region.centerY() - radius);
                long z0 = Math.max(box.minZ, (long) region.centerZ() - radius);
                long x1 = Math.min(box.maxX, (long) region.centerX() + radius);
                long y1 = Math.min(box.maxY, (long) region.centerY() + radius);
                long z1 = Math.min(box.maxZ, (long) region.centerZ() + radius);
                if (x0 > x1 || y0 > y1 || z0 > z1) continue;
                minX = Math.min(minX, (int) (x0 >> 4));
                minY = Math.min(minY, (int) (y0 >> 4));
                minZ = Math.min(minZ, (int) (z0 >> 4));
                maxX = Math.max(maxX, (int) (x1 >> 4));
                maxY = Math.max(maxY, (int) (y1 >> 4));
                maxZ = Math.max(maxZ, (int) (z1 >> 4));
            }
            return minX > maxX ? null : new SectionBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        boolean volumeAtMost(int limit) {
            long width = (long) this.maxX - this.minX + 1;
            long height = (long) this.maxY - this.minY + 1;
            long depth = (long) this.maxZ - this.minZ + 1;
            // Division avoids overflow for selections spanning the entire coordinate range.
            return width <= limit / height && width * height <= limit / depth;
        }

        boolean contains(int x, int y, int z) {
            return x >= this.minX && x <= this.maxX
                    && y >= this.minY && y <= this.maxY
                    && z >= this.minZ && z <= this.maxZ;
        }
    }
}
