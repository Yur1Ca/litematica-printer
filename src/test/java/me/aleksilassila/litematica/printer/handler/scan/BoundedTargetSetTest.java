package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedTargetSetTest {
    @Test void onlyCapacityEvictionRequestsAnotherDelivery() {
        List<BlockPos> removed = new ArrayList<>(), evicted = new ArrayList<>();
        var targets = new BoundedTargetSet(2, removed::add, evicted::add);
        for (int x = 0; x < 3; x++) targets.add(new BlockPos(x, 0, 0));
        targets.remove(new BlockPos(1, 0, 0));
        targets.add(new BlockPos(3, 0, 0));
        targets.add(new BlockPos(4, 0, 0));
        targets.removeIf(pos -> pos.getX() == 3);
        targets.clear();
        assertEquals(List.of(BlockPos.ZERO, new BlockPos(2, 0, 0)), evicted,
                "Success, pruning and clearing must not recursively reoffer completed or obsolete targets");
        assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
                new BlockPos(3, 0, 0), new BlockPos(4, 0, 0)), removed);
    }

    @Test void associatedWaitMetadataIsRemovedForEvictionPruningAndClear() {
        java.util.Set<BlockPos> metadata = new java.util.HashSet<>();
        var targets = new BoundedTargetSet(2, metadata::remove);
        for (int x = 0; x < 3; x++) {
            BlockPos pos = new BlockPos(x, 0, 0);
            targets.add(pos);
            metadata.add(pos);
        }
        assertEquals(new java.util.HashSet<>(targets), metadata);
        targets.removeIf(pos -> pos.getX() == 1);
        assertEquals(new java.util.HashSet<>(targets), metadata);
        targets.clear();
        assertTrue(metadata.isEmpty());
    }
    @Test void duplicateRetriesDoNotGrowOrDisplaceTheNextTarget() {
        var targets = new BoundedTargetSet(2);
        targets.add(BlockPos.ZERO);
        targets.add(BlockPos.ZERO.east());
        assertFalse(targets.add(BlockPos.ZERO));
        assertEquals(List.of(BlockPos.ZERO, BlockPos.ZERO.east()), new ArrayList<>(targets));
        targets.add(BlockPos.ZERO.east(2));
        assertEquals(List.of(BlockPos.ZERO.east(), BlockPos.ZERO.east(2)), new ArrayList<>(targets));
        targets.removeIf(pos -> pos.getX() == 1);
        assertEquals(List.of(BlockPos.ZERO.east(2)), new ArrayList<>(targets));
    }

    @Test void mutableScanPositionsAreStoredAsImmutableDeliveryReferences() {
        var targets = new BoundedTargetSet(2);
        var cursor = new BlockPos.MutableBlockPos(1, 2, 3);
        targets.add(cursor);
        cursor.set(4, 5, 6);
        assertTrue(targets.contains(new BlockPos(1, 2, 3)));
        assertFalse(targets.contains(cursor));
        assertTrue(targets.remove(new BlockPos(1, 2, 3)));
        assertTrue(targets.isEmpty());
    }
}
