//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MineCandidateRefreshTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void refreshReplacesQueuedStateAndDropsRemovedOrUnbreakableTargets() throws Exception {
        try (var fixture = new MineCandidateRefreshFixture(true)) {
            fixture.seed(4, false);
            fixture.state(MineCandidateRefreshFixture.position(0), Blocks.DIRT.defaultBlockState());
            fixture.state(MineCandidateRefreshFixture.position(1), Blocks.AIR.defaultBlockState());
            fixture.state(MineCandidateRefreshFixture.position(2), Blocks.BEDROCK.defaultBlockState());
            fixture.beginTick();
            var targets = fixture.views(fixture.refresh());
            assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(3, 0, 0)),
                    targets.stream().map(MineCandidateRefreshFixture.TargetView::pos).toList());
            assertSame(Blocks.DIRT.defaultBlockState(), targets.get(0).state());
            assertSame(Blocks.STONE.defaultBlockState(), targets.get(1).state());
        }
    }

    @Test void refreshStillChecksCurrentRangeAndRestrictions() throws Exception {
        try (var fixture = new MineCandidateRefreshFixture(true)) {
            fixture.seed(4, true);
            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(UsageRestriction.ListType.BLACKLIST);
            Configs.Mine.EXCAVATE_BLACKLIST.setStrings(List.of("minecraft:stone"));
            var targets = fixture.views(fixture.refresh());
            assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(3, 0, 0)),
                    targets.stream().map(MineCandidateRefreshFixture.TargetView::pos).toList());
            fixture.movePlayer(new Vec3(1000, 1000, 1000));
            assertTrue(fixture.refresh().isEmpty());
        }
    }

    @Test void refreshPreservesDistanceThenCoordinateOrder() throws Exception {
        try (var fixture = new MineCandidateRefreshFixture(true)) {
            for (BlockPos pos : List.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0),
                    new BlockPos(0, 0, 1), BlockPos.ZERO)) {
                fixture.state(pos, Blocks.STONE.defaultBlockState());
                fixture.enqueue(pos);
            }
            var targets = fixture.views(fixture.refresh());
            assertEquals(List.of(BlockPos.ZERO, new BlockPos(0, 0, 1), new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)),
                    targets.stream().map(MineCandidateRefreshFixture.TargetView::pos).toList());
        }
    }

    @Test void sameTickToolSwitchAndReplacementStillInvalidateAnalysis() throws Exception {
        try (var fixture = new MineCandidateRefreshFixture(false)) {
            fixture.seed(1, false);
            var wooden = fixture.views(fixture.refresh()).get(0);
            assertSame(Items.IRON_PICKAXE, wooden.tool());
            fixture.selectSlot(1);
            var iron = fixture.views(fixture.refresh()).get(0);
            assertTrue(iron.currentProgress() > wooden.currentProgress());
            fixture.setSlot(1, new ItemStack(Items.DIAMOND_PICKAXE));
            fixture.selectSlot(1);
            var diamond = fixture.views(fixture.refresh()).get(0);
            assertSame(Items.DIAMOND_PICKAXE, diamond.tool());
            assertTrue(diamond.currentProgress() > iron.currentProgress());
        }
    }
}
//#endif
