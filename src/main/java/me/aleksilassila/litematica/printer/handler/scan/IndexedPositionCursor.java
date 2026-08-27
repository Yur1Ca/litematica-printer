package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongIterator;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
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
            SchematicBlockIndex schematicIndex,
            List<PrinterBox> sourceBoxes,
            ScanRegion region
    ) {
        this.centerX = region.centerX();
        this.centerY = region.centerY();
        this.centerZ = region.centerZ();
        List<BlockPos> candidates = new ArrayList<>();
        LongIterator iterator = schematicIndex.positions().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            if (ScanGeometry.containsAny(sourceBoxes, pos)
                    && ScanGeometry.distanceSqr(pos, region.centerX(), region.centerY(), region.centerZ())
                    <= (long) region.maxDistanceBand() * region.maxDistanceBand()) {
                candidates.add(pos);
            }
        }
        candidates.sort(Comparator
                .comparingLong((BlockPos pos) -> ScanGeometry.distanceSqr(
                        pos, region.centerX(), region.centerY(), region.centerZ()))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        this.positions = List.copyOf(candidates);
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
