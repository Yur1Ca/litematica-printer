package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Distance-ordered cursor over the sparse non-air schematic index. */
final class IndexedPositionCursor implements PositionCursor {
    private final List<BlockPos> positions;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
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
        this.centerX = region.centerX();
        this.centerY = region.centerY();
        this.centerZ = region.centerZ();
        List<BlockPos> candidates = new ArrayList<>();
        long filterStart = timing.start();
        long visited;
        try {
            visited = schematicIndex.forEachInRange(sourceBoxes, region, packed -> {
                BlockPos pos = BlockPos.of(packed);
                if (ScanGeometry.containsAny(sourceBoxes, pos)
                        && ScanGeometry.distanceSqr(pos, region.centerX(), region.centerY(), region.centerZ())
                        <= (long) region.maxDistanceBand() * region.maxDistanceBand()) {
                    candidates.add(pos);
                }
            });
        } finally {
            timing.finish(owner, Stage.INDEX_FILTER, filterStart);
        }
        timing.count(owner, Reason.INDEX_POSITIONS_VISITED, visited);
        timing.count(owner, Reason.INDEX_CANDIDATES, candidates.size());
        long sortStart = timing.start();
        try {
            candidates.sort(Comparator
                    .comparingLong((BlockPos pos) -> ScanGeometry.distanceSqr(
                            pos, region.centerX(), region.centerY(), region.centerZ()))
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
        } finally {
            timing.finish(owner, Stage.INDEX_SORT, sortStart);
        }
        long copyStart = timing.start();
        try {
            this.positions = List.copyOf(candidates);
        } finally {
            timing.finish(owner, Stage.INDEX_COPY, copyStart);
        }
    }

    @Override
    public PollResult poll(BlockPos.MutableBlockPos target) {
        if (this.index >= this.positions.size()) {
            return PollResult.COMPLETE;
        }
        target.set(this.positions.get(this.index++));
        return PollResult.AVAILABLE;
    }

    @Override
    public long peekDistanceSqr() {
        if (this.index >= this.positions.size()) {
            return Long.MAX_VALUE;
        }
        BlockPos pos = this.positions.get(this.index);
        return ScanGeometry.distanceSqr(pos, this.centerX, this.centerY, this.centerZ);
    }

    @Override
    public boolean isComplete() {
        return this.index >= this.positions.size();
    }
}
