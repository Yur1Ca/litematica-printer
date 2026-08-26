package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedPositionCursorTest {
    @Test
    void emitsOnlyIndexedPositionsInsideTheScanRegionInDistanceOrder() {
        SchematicBlockIndex index = new SchematicBlockIndex();
        index.positions().add(BlockPos.asLong(0, 0, 0));
        index.positions().add(BlockPos.asLong(2, 0, 0));
        index.positions().add(BlockPos.asLong(8, 0, 0));

        PrinterBox box = new PrinterBox(-1, -1, -1, 4, 1, 1);
        ScanRegion region = ScanRegion.from(box, null);
        IndexedPositionCursor cursor = new IndexedPositionCursor(index, List.of(box), region);
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();

        assertTrue(cursor.poll(target) == PositionCursor.PollResult.AVAILABLE);
        assertEquals(new BlockPos(0, 0, 0), target);
        assertTrue(cursor.poll(target) == PositionCursor.PollResult.AVAILABLE);
        assertEquals(new BlockPos(2, 0, 0), target);
        assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(target));
    }
}
