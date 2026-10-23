package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason;
import me.aleksilassila.litematica.printer.core.scan.OrderedPositions;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import me.aleksilassila.litematica.printer.core.scan.ScanOrderShadow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Distance-ordered cursor over the sparse non-air schematic index. */
final class IndexedPositionCursor implements PositionCursor {
    private final OrderedPositions positions;
    private int index;

    IndexedPositionCursor(
            SchematicPositionQuery schematicIndex,
            List<PrinterBox> sourceBoxes,
            ScanRegion region
    ) {
        this(schematicIndex, sourceBoxes, region, RuntimeTiming.disabled(), "print");
    }

    IndexedPositionCursor(SchematicPositionQuery schematicIndex, List<PrinterBox> sourceBoxes,
                          ScanRegion region, RuntimeTiming timing, String owner) {
        this(schematicIndex, sourceBoxes, region, timing, owner, null, null);
    }

    IndexedPositionCursor(SchematicPositionQuery schematicIndex, List<PrinterBox> sourceBoxes,
                          ScanRegion region, RuntimeTiming timing, String owner,
                          ScanOrderShadow shadow, ScanHandle handle) {
        LongArrayList candidates = new LongArrayList();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long filterStart = timing.start();
        long visited;
        try {
            visited = schematicIndex.forEachInRange(sourceBoxes, region, packed -> {
                pos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                if (ScanGeometry.containsAny(sourceBoxes, pos)
                        && ScanGeometry.distanceSqr(pos, region.centerX(), region.centerY(), region.centerZ())
                        <= (long) region.maxDistanceBand() * region.maxDistanceBand()) {
                    candidates.add(packed);
                }
            });
        } finally {
            timing.finish(owner, Stage.INDEX_FILTER, filterStart);
        }
        timing.count(owner, Reason.INDEX_POSITIONS_VISITED, visited);
        timing.count(owner, Reason.INDEX_CANDIDATES, candidates.size());
        long copyStart = timing.start();
        long[] snapshot;
        try {
            snapshot = candidates.toLongArray();
        } finally {
            timing.finish(owner, Stage.INDEX_COPY, copyStart);
        }
        long sortStart = timing.start();
        try {
            this.positions = new OrderedPositions(snapshot, region.centerX(), region.centerY(), region.centerZ());
        } finally {
            timing.finish(owner, Stage.INDEX_SORT, sortStart);
        }
        if (shadow != null) {
            shadow.submit(handle, snapshot, this.positions, region.centerX(), region.centerY(), region.centerZ());
        }
    }

    @Override
    public PollResult poll(BlockPos.MutableBlockPos target) {
        if (this.index >= this.positions.size()) {
            return PollResult.COMPLETE;
        }
        long packed = this.positions.positionAt(this.index++);
        target.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
        return PollResult.AVAILABLE;
    }

    @Override
    public long peekDistanceSqr() {
        if (this.index >= this.positions.size()) {
            return Long.MAX_VALUE;
        }
        return this.positions.distanceAt(this.index);
    }

    @Override
    public boolean isComplete() {
        return this.index >= this.positions.size();
    }
}
