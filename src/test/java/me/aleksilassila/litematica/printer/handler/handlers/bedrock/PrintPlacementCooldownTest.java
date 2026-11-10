package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real print confirmation and position cooldowns without a game process. */
class PrintPlacementCooldownTest {
    private static final BlockPos POS = new BlockPos(1, 64, 0);

    @BeforeAll
    static void bootstrap() {
        BedrockPlacementRegressionTest.bootstrap();
    }

    @Test
    void correctionPreservesCooldownUntilItsNaturalExpiry() throws Exception {
        try (var f = new CooldownFixture()) {
            f.sent(8);
            f.advanceTo(101);

            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.AIR.defaultBlockState());

            assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
            assertEquals(7, f.remainingCooldown(), "A rejected placement must keep its configured retry interval");
            assertEquals(0, f.runtime.hudStats().snapshot(HudStatsManager.Mode.PRINT).finished());
            f.advanceTo(107);
            assertTrue(f.cooldowns.isOnCooldown(f.level, "print", POS));
            assertEquals(1, f.remainingCooldown());
            f.advanceTo(108);
            assertFalse(f.cooldowns.isOnCooldown(f.level, "print", POS));
            assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
            f.sent(8);
            assertTrue(f.runtime.hudStats().isPrintPlacementPending(POS));
            assertEquals(8, f.remainingCooldown());
        }
    }

    @Test
    void confirmationTimeoutPreservesLongerConfiguredCooldown() throws Exception {
        try (var f = new CooldownFixture()) {
            f.sent(64);
            f.advanceTo(110);
            assertTrue(f.runtime.hudStats().isPrintPlacementPending(POS));
            long dirtyVersion = f.scan.dirtyVersion();

            f.advanceTo(111);

            assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
            assertTrue(f.scan.dirtyVersion() > dirtyVersion, "A timed-out target must still be rescanned");
            assertEquals(53, f.remainingCooldown(), "The confirmation deadline must not shorten placement cooldown");
            assertEquals(0, f.runtime.hudStats().snapshot(HudStatsManager.Mode.PRINT).finished());
            f.advanceTo(163);
            assertEquals(1, f.remainingCooldown());
            f.advanceTo(164);
            assertFalse(f.cooldowns.isOnCooldown(f.level, "print", POS));
        }
    }

    @Test
    void matchingAndDuplicateUpdatesCountOneConfirmationWithoutChangingCooldown() throws Exception {
        try (var f = new CooldownFixture()) {
            f.sent(8);
            f.advanceTo(101);

            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.STONE.defaultBlockState());
            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.STONE.defaultBlockState());
            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.AIR.defaultBlockState());

            assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
            var stats = f.runtime.hudStats().snapshot(HudStatsManager.Mode.PRINT);
            assertEquals(1, stats.finished());
            assertEquals(1, stats.total());
            assertEquals(7, f.remainingCooldown());
        }
    }

    @Test
    void lateCorrectionCannotShortenANewerAttemptCooldown() throws Exception {
        try (var f = new CooldownFixture()) {
            f.sent(8);
            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.AIR.defaultBlockState());
            f.advanceTo(108);
            f.sent(8);
            f.advanceTo(109);

            // An old correction has no placement receipt ID, so it may resolve the new pending
            // observation, but it must never cancel the new attempt's independent cooldown.
            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.AIR.defaultBlockState());
            f.runtime.hudStats().confirmBlockUpdate(POS, Blocks.AIR.defaultBlockState());

            assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
            assertEquals(7, f.remainingCooldown());
            assertEquals(0, f.runtime.hudStats().snapshot(HudStatsManager.Mode.PRINT).finished());
        }
    }

    private static class CooldownFixture extends BedrockPlacementRegressionTest.Fixture {
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final int oldCooldown = Configs.Placement.PLACE_COOLDOWN.getIntegerValue();
        final CooldownUtils cooldowns = new CooldownUtils();
        final ScanEngine scan = new ScanEngine(runtime::epoch, () -> client.level, new ScanCache());

        CooldownFixture() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            set(runtime, PrinterRuntime.class, "cooldownUtils", cooldowns);
            set(runtime, PrinterRuntime.class, "scanEngine", scan);
            tick(100);
        }

        void sent(int cooldownTicks) {
            Configs.Placement.PLACE_COOLDOWN.setIntegerValue(cooldownTicks);
            cooldowns.setCooldown(level, "print", POS, Configs.Placement.PLACE_COOLDOWN.getIntegerValue());
            runtime.hudStats().trackExpectedBlockState(HudStatsManager.Mode.PRINT, POS, Blocks.STONE.defaultBlockState());
            runtime.hudStats().recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        }

        void advanceTo(long targetTick) throws Exception {
            while (runtime.currentTick() < targetTick) {
                tick(runtime.currentTick() + 1);
                cooldowns.tick();
                runtime.hudStats().tick();
            }
        }

        int remainingCooldown() {
            return cooldowns.getRemainingCooldown(level, "print", POS);
        }

        @Override
        public void close() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Placement.PLACE_COOLDOWN.setIntegerValue(oldCooldown);
            super.close();
        }
    }
}
