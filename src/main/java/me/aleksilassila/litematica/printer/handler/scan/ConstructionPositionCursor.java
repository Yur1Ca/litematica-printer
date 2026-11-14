package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/** Merges lazy storage columns into global Y/Z/X rows without retaining emitted positions. */
final class ConstructionPositionCursor implements PositionCursor {
    private static final int MIN_PACKED_XZ = -33_554_432;
    private static final int MAX_PACKED_XZ = 33_554_431;
    private static final int MIN_PACKED_Y = -2048;
    private static final int MAX_PACKED_Y = 2047;
    private static final int SECTION_SIZE = 16;

    private final ScanRegion region;
    private final ConstructionOrder order;
    private final PriorityQueue<ColumnNode> heads;
    private long lastPolled;
    private boolean hasPolled;

    ConstructionPositionCursor(List<PrinterBox> boxes, ScanRegion region, ConstructionOrder order) {
        this.region = region;
        this.order = order;
        this.heads = new PriorityQueue<>((first, second) -> order.compare(
                first.cursor.packed(), second.cursor.packed()));
        for (PrinterBox box : boxes) {
            SourceBounds bounds = SourceBounds.clip(box, region);
            if (bounds != null) this.offer(bounds, new ColumnRectangle(
                    bounds.minX >> 4, bounds.maxX >> 4, bounds.minZ >> 4, bounds.maxZ >> 4));
        }
    }

    @Override
    public PollResult poll(BlockPos.MutableBlockPos target) {
        if (!this.prepareHead()) return PollResult.COMPLETE;
        long packed = this.heads.peek().cursor.packed();
        target.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
        this.lastPolled = packed;
        this.hasPolled = true;
        // Equal coordinates from overlapping boxes meet even when another source is not split yet.
        do {
            ColumnNode node = this.heads.remove();
            if (node.cursor.advance()) this.heads.add(node);
        } while (this.prepareHead() && this.heads.peek().cursor.packed() == packed);
        return PollResult.AVAILABLE;
    }

    @Override
    public long peekDistanceSqr() {
        if (this.heads.isEmpty()) return Long.MAX_VALUE;
        // A rectangle's key is its actual first point, so peeking need not expand it.
        long packed = this.heads.peek().cursor.packed();
        long dx = BlockPos.getX(packed) - (long) this.region.centerX();
        long dy = BlockPos.getY(packed) - (long) this.region.centerY();
        long dz = BlockPos.getZ(packed) - (long) this.region.centerZ();
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean isComplete() { return this.heads.isEmpty(); }

    @Override public BlockPos peekPosition() {
        return this.heads.isEmpty() ? null : BlockPos.of(this.heads.peek().cursor.packed());
    }

    /** Skip the last returned storage section, retaining the other columns on the same layer. */
    void skipSection() {
        if (!this.hasPolled) return;
        int sectionX = BlockPos.getX(this.lastPolled) >> 4;
        int sectionY = BlockPos.getY(this.lastPolled) >> 4;
        int sectionZ = BlockPos.getZ(this.lastPolled) >> 4;
        List<ColumnNode> affected = new ArrayList<>();
        List<ColumnNode> extracted = new ArrayList<>();
        for (Iterator<ColumnNode> iterator = this.heads.iterator(); iterator.hasNext();) {
            ColumnNode node = iterator.next();
            if (!node.columns.contains(sectionX, sectionZ)) continue;
            if (node.columns.isColumn()) {
                if (node.cursor.y >> 4 != sectionY) continue;
                iterator.remove();
                affected.add(node);
            } else {
                // An overlapping box may start later in this section and still be unopened.
                ColumnNode column = this.create(node.bounds,
                        new ColumnRectangle(sectionX, sectionX, sectionZ, sectionZ));
                if (column == null || column.cursor.y >> 4 != sectionY) continue;
                iterator.remove();
                extracted.add(node);
                affected.add(column);
            }
        }
        for (ColumnNode node : extracted) {
            ColumnRectangle rectangle = node.columns;
            this.offer(node.bounds, new ColumnRectangle(rectangle.minX, sectionX - 1,
                    rectangle.minZ, rectangle.maxZ));
            this.offer(node.bounds, new ColumnRectangle(sectionX + 1, rectangle.maxX,
                    rectangle.minZ, rectangle.maxZ));
            this.offer(node.bounds, new ColumnRectangle(sectionX, sectionX,
                    rectangle.minZ, sectionZ - 1));
            this.offer(node.bounds, new ColumnRectangle(sectionX, sectionX,
                    sectionZ + 1, rectangle.maxZ));
        }
        for (ColumnNode node : affected) {
            if (node.cursor.skipSection(sectionY)) this.heads.add(node);
        }
    }

    @Override
    public void close() {
        this.heads.clear();
        this.hasPolled = false;
    }

    private boolean prepareHead() {
        while (!this.heads.isEmpty() && !this.heads.peek().columns.isColumn()) {
            ColumnNode node = this.heads.remove();
            ColumnRectangle rectangle = node.columns;
            int width = rectangle.maxX - rectangle.minX, depth = rectangle.maxZ - rectangle.minZ;
            if (width >= depth) {
                int middle = rectangle.minX + width / 2;
                this.offer(node.bounds, new ColumnRectangle(rectangle.minX, middle, rectangle.minZ, rectangle.maxZ));
                this.offer(node.bounds, new ColumnRectangle(middle + 1, rectangle.maxX, rectangle.minZ, rectangle.maxZ));
            } else {
                int middle = rectangle.minZ + depth / 2;
                this.offer(node.bounds, new ColumnRectangle(rectangle.minX, rectangle.maxX, rectangle.minZ, middle));
                this.offer(node.bounds, new ColumnRectangle(rectangle.minX, rectangle.maxX, middle + 1, rectangle.maxZ));
            }
        }
        return !this.heads.isEmpty();
    }

    private void offer(SourceBounds bounds, ColumnRectangle rectangle) {
        ColumnNode node = this.create(bounds, rectangle);
        if (node != null) this.heads.add(node);
    }

    private ColumnNode create(SourceBounds bounds, ColumnRectangle rectangle) {
        if (rectangle.minX > rectangle.maxX || rectangle.minZ > rectangle.maxZ) return null;
        GeometryCursor cursor = new GeometryCursor(bounds, rectangle, this.region, this.order.descendingY());
        return cursor.hasPosition ? new ColumnNode(bounds, rectangle, cursor) : null;
    }

    private static long floorSqrt(long value) {
        long root = (long) Math.sqrt(value);
        // Bands are at most Integer.MAX_VALUE; both corrected squares fit in a signed long.
        while ((root + 1) * (root + 1) <= value) root++;
        while (root * root > value) root--;
        return root;
    }

    private record SourceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static SourceBounds clip(PrinterBox box, ScanRegion region) {
            long band = region.maxDistanceBand();
            if (band < 0) return null;
            int minX = (int) Math.max(Math.max(box.minX, MIN_PACKED_XZ), region.centerX() - band);
            int maxX = (int) Math.min(Math.min(box.maxX, MAX_PACKED_XZ), region.centerX() + band);
            int minY = (int) Math.max(Math.max(box.minY, MIN_PACKED_Y), region.centerY() - band);
            int maxY = (int) Math.min(Math.min(box.maxY, MAX_PACKED_Y), region.centerY() + band);
            int minZ = (int) Math.max(Math.max(box.minZ, MIN_PACKED_XZ), region.centerZ() - band);
            int maxZ = (int) Math.min(Math.min(box.maxZ, MAX_PACKED_XZ), region.centerZ() + band);
            return minX <= maxX && minY <= maxY && minZ <= maxZ
                    ? new SourceBounds(minX, minY, minZ, maxX, maxY, maxZ) : null;
        }
    }

    private record ColumnRectangle(int minX, int maxX, int minZ, int maxZ) {
        boolean isColumn() { return this.minX == this.maxX && this.minZ == this.maxZ; }
        boolean contains(int x, int z) {
            return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
        }
    }

    private record ColumnNode(SourceBounds bounds, ColumnRectangle columns, GeometryCursor cursor) { }

    /** Every retained layer and row has a point; sphere tangencies skip empty spans arithmetically. */
    private static final class GeometryCursor {
        private final ScanRegion region;
        private final boolean descendingY;
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        private final long maxDistanceSqr, minDxSqr;
        private int layerMinZ, layerMaxZ, rowMinX, rowMaxX;
        private int x, y, z;
        private boolean hasPosition;

        GeometryCursor(SourceBounds bounds, ColumnRectangle rectangle, ScanRegion region, boolean descendingY) {
            this.region = region;
            this.descendingY = descendingY;
            this.minX = Math.max(bounds.minX, rectangle.minX * SECTION_SIZE);
            this.maxX = Math.min(bounds.maxX, rectangle.maxX * SECTION_SIZE + SECTION_SIZE - 1);
            this.minZ = Math.max(bounds.minZ, rectangle.minZ * SECTION_SIZE);
            this.maxZ = Math.min(bounds.maxZ, rectangle.maxZ * SECTION_SIZE + SECTION_SIZE - 1);
            this.maxDistanceSqr = (long) region.maxDistanceBand() * region.maxDistanceBand();
            int nearestX = Math.max(this.minX, Math.min(this.maxX, region.centerX()));
            int nearestZ = Math.max(this.minZ, Math.min(this.maxZ, region.centerZ()));
            long dx = nearestX - (long) region.centerX(), dz = nearestZ - (long) region.centerZ();
            this.minDxSqr = dx * dx;
            long remaining = this.maxDistanceSqr - this.minDxSqr - dz * dz;
            long radius = remaining >= 0 ? floorSqrt(remaining) : -1;
            this.minY = (int) Math.max(bounds.minY, region.centerY() - radius);
            this.maxY = (int) Math.min(bounds.maxY, region.centerY() + radius);
            if (remaining >= 0 && this.minY <= this.maxY) {
                this.y = descendingY ? this.maxY : this.minY;
                this.selectLayer();
                this.hasPosition = true;
            }
        }

        long packed() { return BlockPos.asLong(this.x, this.y, this.z); }

        boolean advance() {
            boolean reverse = (this.z & 1) != 0;
            if (this.x != (reverse ? this.rowMinX : this.rowMaxX)) {
                this.x += reverse ? -1 : 1;
            } else if (this.z < this.layerMaxZ) {
                this.z++;
                this.selectRow();
            } else {
                this.y += this.descendingY ? -1 : 1;
                if (this.y < this.minY || this.y > this.maxY) return this.hasPosition = false;
                this.selectLayer();
            }
            return true;
        }

        boolean skipSection(int sectionY) {
            this.y = this.descendingY ? sectionY * SECTION_SIZE - 1 : (sectionY + 1) * SECTION_SIZE;
            if (this.y < this.minY || this.y > this.maxY) return this.hasPosition = false;
            this.selectLayer();
            return true;
        }

        private void selectLayer() {
            long dy = this.y - (long) this.region.centerY();
            long radius = floorSqrt(this.maxDistanceSqr - dy * dy - this.minDxSqr);
            this.layerMinZ = (int) Math.max(this.minZ, this.region.centerZ() - radius);
            this.layerMaxZ = (int) Math.min(this.maxZ, this.region.centerZ() + radius);
            this.z = this.layerMinZ;
            this.selectRow();
        }

        private void selectRow() {
            long dy = this.y - (long) this.region.centerY(), dz = this.z - (long) this.region.centerZ();
            long radius = floorSqrt(this.maxDistanceSqr - dy * dy - dz * dz);
            this.rowMinX = (int) Math.max(this.minX, this.region.centerX() - radius);
            this.rowMaxX = (int) Math.min(this.maxX, this.region.centerX() + radius);
            this.x = (this.z & 1) == 0 ? this.rowMinX : this.rowMaxX;
        }
    }
}
