//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicSectionSnapshotsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void exactContentComparisonDetectsInPlacePaletteAndPackedStorageChanges() throws Exception {
        var snapshots = new SchematicSectionSnapshots();
        var section = SchematicIndexFixture.chunk(0, 3).getSections()[4];
        try (var comparison = snapshots.compare()) {
            assertFalse(comparison.unchanged(1L, section));
            assertTrue(comparison.unchanged(1L, section));
            section.getStates().set(1, 0, 0, Blocks.AIR.defaultBlockState());
            assertFalse(comparison.unchanged(1L, section));
            section.setBlockState(2, 0, 0, Blocks.DIRT.defaultBlockState());
            assertFalse(comparison.unchanged(1L, section));
            assertTrue(comparison.unchanged(1L, section));
            assertFalse(comparison.unchanged(2L, section));
        }
        snapshots.retainSections(new LongOpenHashSet(new long[]{2L}));
        try (var comparison = snapshots.compare()) {
            assertFalse(comparison.unchanged(1L, section));
            assertTrue(comparison.unchanged(2L, section));
        }
        snapshots.clear();
        try (var comparison = snapshots.compare()) { assertFalse(comparison.unchanged(2L, section)); }
    }

    @Test void exhaustedSnapshotAllowanceAlwaysFallsBackToRebuilding() throws Exception {
        var snapshots = new SchematicSectionSnapshots(0);
        var section = SchematicIndexFixture.chunk(0, 1).getSections()[4];
        try (var comparison = snapshots.compare()) {
            assertFalse(comparison.unchanged(1L, section));
            assertFalse(comparison.unchanged(1L, section));
            section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
            assertFalse(comparison.unchanged(1L, section));
        }
    }
}
//#endif
