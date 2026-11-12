//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduces delayed client equipment attributes while retaining the real tool acknowledgement gate. */
class BedrockCleanupEquipmentProgressTest {
    private static final BlockPos PISTON = BlockPos.ZERO.above();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void acknowledgedEfficiencyFiveDoesNotOpenSlowMiningWhileClientAttributesLag() throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.miningEfficiency = 0.0;
            assertEquals(0.17777778F, Blocks.PISTON.defaultBlockState()
                    .getDestroyProgress(f.player, f.clock, PISTON), 0.000001F);

            assertTrue(f.cleanup(PISTON));

            assertEquals(List.of(START_DESTROY_BLOCK, STOP_DESTROY_BLOCK), f.actions());
            assertFalse(f.active(), "A completed fast pair must not lock unrelated ready machines");
            assertTrue(f.pending(PISTON), "Fast submission still requires the authoritative block result");
            assertFalse(f.clock.getBlockState(PISTON).isAir(), "No optimistic block deletion");
            assertFalse(f.cleanup(PISTON), "The same position cannot be resubmitted while its result is pending");
        }
    }

    @Test void hasteTwoCanUseInstantStartWhileTheClientEfficiencyAttributeIsStillOld() throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.miningEfficiency = 0.0;
            f.player.haste = new MobEffectInstance(MobEffects.HASTE, 600, 1);

            assertTrue(f.cleanup(PISTON));

            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
            assertFalse(f.active());
            assertTrue(f.pending(PISTON));
        }
    }

    @Test void aChangedToolStillRequiresItsOwnAcknowledgementBeforeTheFastPair() throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.inventory.setItem(0, f.tool(4));
            assertFalse(f.cleanup(PISTON));
            f.player.inventory.setItem(0, f.tool(5));
            f.player.miningEfficiency = 0.0;
            assertFalse(f.cleanup(PISTON));
            assertTrue(f.actions().isEmpty());
            assertFalse(f.pending(PISTON));

            assertTrue(BedrockInventoryTestAccess.acknowledgeSelectedTool(f.placer, f.client));
            assertTrue(f.cleanup(PISTON));
            assertEquals(List.of(START_DESTROY_BLOCK, STOP_DESTROY_BLOCK), f.actions());
            assertFalse(f.active());
        }
    }

    @Test void staleHighEfficiencyCannotTurnAWeakerCurrentToolIntoAFastPair() throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.inventory.setItem(0, f.tool(4));
            f.player.miningEfficiency = 40.0;
            assertTrue(BedrockInventoryTestAccess.acknowledgeSelectedTool(f.placer, f.client));

            assertTrue(f.cleanup(PISTON));

            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
            assertTrue(f.active(), "The current tool, not a stale stronger attribute, determines the first attempt");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"air", "water"})
    void actualEnvironmentalPenaltiesStillRequireOwnedSlowMining(String penalty) throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.miningEfficiency = 0.0;
            f.player.airborne = penalty.equals("air");
            f.player.submerged = penalty.equals("water");

            assertTrue(f.cleanup(PISTON));

            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
            assertTrue(f.active());
        }
    }

    @Test void anUnconfirmedFastResultStillRetriesAsOwnedNativeMining() throws Exception {
        try (var f = new BedrockCleanupInventorySessionTest.CleanupFixture(5)) {
            f.player.miningEfficiency = 0.0;
            assertTrue(f.cleanup(PISTON));
            assertFalse(f.active());
            f.clock.tick = 16;

            assertTrue(f.cleanup(PISTON));

            assertEquals(List.of(START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, START_DESTROY_BLOCK), f.actions());
            assertTrue(f.active(), "Fallback retains the existing native owner and cancellation rules");
        }
    }
}
//#endif
