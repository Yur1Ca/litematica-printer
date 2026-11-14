//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

class BedrockExposureAdmissionRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void repeatedChecksShareOnlyThisTicksExposureApproval() throws Exception {
        try (var f = new Fixture()) {
            f.awaitExposureApproval();
            for (int check = 0; check < 8; check++) {
                f.engine.beginTick(true);
                assertTrue(f.engine.canAccept(TARGET), "Planning and submission checks cannot consume another attempt");
            }
            // A new tick retains the gate's original one-use retry, not the previous tick's approval.
            f.nextTick(5);
            assertTrue(f.engine.canAccept(TARGET));
            f.nextTick(6);
            assertFalse(f.engine.canAccept(TARGET), "An unused approval cannot live forever across ticks");
        }
    }

    @Test void anExposureApprovalCannotBypassASameTickSideDisable() throws Exception {
        try (var f = new Fixture()) {
            f.awaitExposureApproval();
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(false);

            assertFalse(f.engine.submit(TARGET));
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anExposureApprovalCannotBypassASameTickReachChange() throws Exception {
        try (var f = new Fixture()) {
            f.awaitExposureApproval();
            set(f.client.player, Entity.class, "position", new Vec3(1000.5D, 64.0D, 0.5D));
            assertFalse(BedrockEnvironment.canInteract(TARGET));

            assertFalse(f.engine.submit(TARGET));
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void resetRevokesExposureApprovalAndClosesAdmission() throws Exception {
        try (var f = new Fixture()) {
            f.awaitExposureApproval();
            f.engine.reset();
            assertFalse(f.engine.canSubmitInCurrentWindow());
            f.nextTick(5);

            assertFalse(f.engine.canAccept(TARGET), "Reset must restore the initial bounded exposure wait");
            assertEquals(4, f.cooldowns.getRemainingCooldown(f.level, "bedrock_retry", TARGET));
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
        }
    }

    private static final class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final CooldownUtils cooldowns;

        Fixture() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(true);
            level.blocks.clear();
            level.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            level.blocks.put(TARGET.above(), Blocks.BEDROCK.defaultBlockState());
            level.blocks.put(TARGET.below(), Blocks.BEDROCK.defaultBlockState());
            level.blocks.put(TARGET.north().east(), Blocks.STONE.defaultBlockState());
            var admission = get(engine, BedrockEngine.class, "admission");
            cooldowns = (CooldownUtils) get(admission, BedrockAdmissionController.class, "cooldownUtils");
            var plan = BedrockCandidatePlan.build(level, TARGET, () -> 0L);
            assertNotNull(plan.layout());
            assertTrue(plan.layout().isHorizontal());
            assertTrue(BedrockMachineLayout.shouldDeferUntilExposed(level, TARGET));
            engine.primeSubmissionPlan(TARGET, plan.layout(), plan.placement(), plan.slimePos());
            beginFence();
        }

        void awaitExposureApproval() throws Exception {
            nextTick(0);
            assertFalse(engine.canAccept(TARGET));
            assertEquals(4, cooldowns.getRemainingCooldown(level, "bedrock_retry", TARGET));
            for (int tick = 1; tick <= 4; tick++) nextTick(tick);
            assertFalse(engine.isPositionOnRetryCooldown(TARGET));
            assertTrue(engine.canAccept(TARGET));
        }

        void nextTick(int tick) throws Exception {
            cooldowns.tick();
            advance(tick);
        }

        @Override public void close() throws Exception {
            try { super.close(); }
            finally { Configs.Core.WORK_SWITCH.setBooleanValue(oldWork); }
        }
    }
}
//#endif
