//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real begin/admit/finish phases and packet submission, without faking game outcomes. */
class BedrockRollingWorkTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = FIRST.east(4);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aNewlyAdmittedMachineCanStartBeforeTheCurrentTickEndsWithoutBeingReplayed() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.level.blocks.remove(FIRST.above());
            f.tick(0);
            f.engine.beginTick(true);
            f.plan(FIRST, Direction.UP);
            assertTrue(f.engine.submit(FIRST));
            f.transport.packets.clear();

            f.engine.finishTick(true);

            long placed = uses(f);
            assertTrue(placed > 0, "A reservation accepted after observation must reach the same tick's action phase");
            f.engine.finishTick(true);
            f.engine.beginTick(true);
            assertEquals(placed, uses(f), "Additional entry calls cannot replay this machine's initial batch");
        }
    }

    @Test void anIndependentNewMachineCanStartAfterACompletedCriticalBundleInTheSameTick() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.level.blocks.remove(FIRST.above());
            f.tick(0);
            f.engine.beginTick(true);
            f.plan(FIRST, Direction.UP);
            assertTrue(f.engine.submit(FIRST));
            f.engine.finishTick(true);
            var first = f.targets.iterator().next();
            assertTrue(uses(f) > 0, "The older owner must enter its cycle through a real initial USE");
            assertFalse(first.hasTried());
            f.machine(Direction.UP);
            f.level.blocks.put(first.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence)
                    .max().ifPresent(sequence -> f.enginePlacer.acknowledgeToolTick(f.transport, sequence));
            f.tick(2);
            set(f.client.level, f.client.level.getClass(), "tick", 2L);
            f.engine.beginTick(true);
            assertTrue(first.hasTried());
            int previous = f.transport.packets.size();
            f.plan(SECOND, Direction.UP);
            assertTrue(f.engine.submit(SECOND));

            f.engine.finishTick(true);

            assertTrue(f.transport.packets.subList(previous, f.transport.packets.size()).stream()
                    .anyMatch(ServerboundUseItemOnPacket.class::isInstance),
                    "Only a real footprint/tool/menu conflict may block the independent initial batch");
        }
    }

    @Test void aMissingConstructionMaterialDoesNotPreventAnAlreadyPoweredMachineFromBreaking() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var first = extended(f);
            f.targets.add(first);
            set(f.engine, BedrockEngine.class, "awaitingSupplies", true);
            f.tick(0);

            f.engine.beginTick(true);

            assertTrue(first.hasTried(), "Construction availability must not also be the critical-work permission");
            assertFalse(f.engine.canScanForTargets());
        }
    }

    @Test void aReadOnlyTickCanRetireConfirmedWorkWithoutIssuingAnInventoryOrBlockAction() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var first = extended(f);
            f.targets.add(first);
            f.level.blocks.remove(FIRST);
            f.tick(0);
            int packets = f.transport.packets.size();

            f.engine.beginTick(false);
            f.engine.finishTick(false);

            assertTrue(f.targets.isEmpty());
            assertEquals(packets, f.transport.packets.size(), "Observer-only work cannot acquire another owner's hands");
        }
    }

    @Test void aReusedPowerPositionBelongsOnlyToItsNewCycle() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.level.blocks.remove(FIRST.above());
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            BlockPos torch = FIRST.east().above();
            f.level.blocks.put(torch, Blocks.REDSTONE_TORCH.defaultBlockState());
            cleanup.enqueueCycle(FIRST.north(5), Set.of(torch), false);
            f.level.blocks.remove(torch);
            cleanup.onServerBlockUpdate(torch);
            f.tick(0);
            f.engine.beginTick(true);
            f.plan(FIRST, Direction.UP);
            assertTrue(f.engine.submit(FIRST));
            f.level.blocks.put(torch, Blocks.REDSTONE_TORCH.defaultBlockState());

            f.engine.onRuntimeEvent(new RuntimeEvent.BlockUpdated(torch.getX(), torch.getY(), torch.getZ()));

            assertEquals(1, f.engine.getHudSnapshot().activeTargets(),
                    "An old AIR watch cannot charge the new owner's power source as a second occupied cycle");
        }
    }

    @Test void aRealCleanupUpdateWakesTheExactCandidateInsteadOfKeepingItsSixTickDelay() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.level.blocks.put(FIRST.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN));
            f.tick(0);
            f.engine.beginTick(false);
            f.plan(FIRST, Direction.UP);
            f.passInitialExposureDeferral(FIRST);
            assertFalse(f.engine.submit(FIRST));
            assertTrue(f.engine.isPositionOnRetryCooldown(FIRST));
            f.level.blocks.remove(FIRST.above());
            BlockPos removed = FIRST.above();

            f.engine.onRuntimeEvent(new RuntimeEvent.BlockUpdated(removed.getX(), removed.getY(), removed.getZ()));

            assertFalse(f.engine.isPositionOnRetryCooldown(FIRST), "The real blocking position is clear; the timer no longer owns this candidate");
        }
    }

    private static long uses(BedrockAdmissionPipelineTest.Fixture f) {
        return f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count();
    }

    private static BedrockTarget extended(BedrockAdmissionPipelineTest.Fixture f) throws Exception {
        f.machine(Direction.UP);
        var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
        var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
        var layout = f.target(FIRST, Direction.UP, f.enginePlacer).layout();
        var target = new BedrockTarget(FIRST, f.client.level, layout,
                new BedrockTorchPlacement(FIRST.east(), Direction.UP), null, critical, f.enginePlacer, sync);
        f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
        target.setStatus(BedrockTarget.Status.EXTENDED);
        return target;
    }
}
//#endif
