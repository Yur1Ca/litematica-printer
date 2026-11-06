//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class TrenchFillBatchReadinessTest {
    private static final BlockPos UNSUPPORTED = new BlockPos(2, 1, 0);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test
    void aFluidWithoutSupportDoesNotHideTheSupportedTargetBehindIt() throws Exception {
        try (var f = fixture()) {
            var skip = new AtomicReference<>(false);
            // Follow the runner's shared skip flag across the ordered batch.
            for (BlockPos pos : List.of(UNSUPPORTED, TrenchFillIntegrationTest.TARGET)) {
                if (skip.get()) break;
                f.execute(pos, skip);
            }
            assertEquals(1, f.queued.size(), "A target-local missing support cannot stop later fill work");
            assertFalse(skip.get());
            assertFalse(canAttempt(f, UNSUPPORTED, 109));
            assertTrue(canAttempt(f, UNSUPPORTED, 110), "The unsupported target keeps its normal ten-tick retry");
            assertFalse(f.canAttempt(101), "The supported target still waits for its world confirmation");
        }
    }

    @Test
    void missingSupportConsumesNoActionBudgetAndSendsNoClick() throws Exception {
        try (var f = fixture()) {
            var begin = FeatureModuleBase.class.getDeclaredMethod("beginEffectiveExecution");
            begin.setAccessible(true);
            begin.invoke(f.handler);
            var skip = new AtomicReference<>(false);
            f.execute(UNSUPPORTED, skip);

            var consumed = FeatureModuleBase.class.getDeclaredMethod("consumedEffectiveExecution");
            consumed.setAccessible(true);
            assertFalse((boolean) consumed.invoke(f.handler), "Rejecting support before dispatch is not an effective action");
            assertFalse(skip.get());
            assertTrue(f.queued.isEmpty());
            assertEquals(0, f.sentThisTick());
            assertEquals(Long.MIN_VALUE, f.rate.lastSentTick());
            assertFalse(canAttempt(f, UNSUPPORTED, 109));
        }
    }

    private static TrenchFillIntegrationTest.Fixture fixture() throws Exception {
        var f = new TrenchFillIntegrationTest.Fixture();
        f.player.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        f.level.states = Map.of(UNSUPPORTED, Blocks.LAVA.defaultBlockState());
        var update = f.state.getClass().getDeclaredMethod("updateTargets", List.class, Set.class,
                Set.class, long.class, Predicate.class);
        update.setAccessible(true);
        update.invoke(f.state, List.of(), Set.of(UNSUPPORTED, TrenchFillIntegrationTest.TARGET), Set.of(),
                100L, (Predicate<BlockPos>) pos -> true);
        return f;
    }

    private static boolean canAttempt(TrenchFillIntegrationTest.Fixture f, BlockPos pos, long tick) throws Exception {
        var attempt = f.state.getClass().getDeclaredMethod("canAttempt", BlockPos.class, long.class);
        attempt.setAccessible(true);
        return (boolean) attempt.invoke(f.state, pos, tick);
    }
}
//#endif
