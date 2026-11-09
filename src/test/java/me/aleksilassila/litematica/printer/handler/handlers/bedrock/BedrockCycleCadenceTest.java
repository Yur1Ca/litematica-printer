//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Actual engine retirement and cleanup replies; this does not model Minecraft physics or TPS. */
class BedrockCycleCadenceTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos PROBE = new BlockPos(-12, 64, -12);
    private static final List<BlockPos> NEXT = List.of(TARGET.east(4), TARGET.east(8), TARGET.east(12),
            TARGET.south(4).east(4), TARGET.south(4).east(8), TARGET.south(4).east(12));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void retirementWithARealPistonStillOwnedDoesNotFinishTheCycleSample() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            var target = start(f);
            retireWithPiston(f, target, 18);
            var scheduler = scheduler(f);
            assertTrue(f.targets.isEmpty(), "The break already retired into the real cleanup owner");
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            assertTrue(f.client.level.getBlockState(TARGET.above()).is(Blocks.PISTON));

            // This public-policy probe consumes one start; all lifetime observation stays in Engine.
            scheduler.started(PROBE);
            scheduler.beginTick(19, 6, 0, true);

            assertEquals(1, scheduler.availableStarts(),
                    "Bedrock removal must not teach an 18-tick whole cycle while its piston remains");
        }
    }

    @Test void finalAuthoritativeAirFinishesTheWholeCycleInsteadOfTheWatchExpiry() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            var target = start(f);
            retireWithPiston(f, target, 18);
            assertTrue(f.enginePlacer.isCleanupPendingAt(TARGET.above()),
                    "The real cleanup START owns a native result before AIR arrives");
            advanceTo(f, 23);
            removeAndNotify(f, TARGET.above());
            assertFalse(f.enginePlacer.isCleanupPendingAt(TARGET.above()));
            advance(f, 24);
            var scheduler = scheduler(f);
            assertEquals(0, f.engine.getHudSnapshot().activeTargets());
            scheduler.started(PROBE);

            scheduler.beginTick(25, 6, 0, true);
            assertEquals(0, scheduler.availableStarts(), "24 / six occupied slots requires four ticks per new start");
            scheduler.beginTick(27, 6, 0, true);
            assertEquals(0, scheduler.availableStarts());
            scheduler.beginTick(28, 6, 0, true);
            assertEquals(1, scheduler.availableStarts(),
                    "The remaining AIR watch must not add its 40-tick retention to the measured cycle");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unloaded", "out_of_range"})
    void unobservableCleanupCannotBecomeAShortSuccessfulSample(String unavailable) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            var target = start(f);
            retireWithPiston(f, target, 18);
            var knownLevel = f.client.level;
            if (unavailable.equals("unloaded")) {
                var unknown = f.allocate(UnloadedLevel.class);
                unknown.blocks = f.level.blocks;
                set(unknown, Level.class, "dimension", Level.OVERWORLD);
                f.client.level = unknown;
                assertFalse(BedrockEnvironment.isChunkLoaded(unknown, TARGET.above()));
                assertTrue(unknown.getBlockState(TARGET.above()).is(Blocks.VOID_AIR));
            } else {
                set(f.client.player, Entity.class, "position", new Vec3(1000.5D, 64.0D, 1000.5D));
                assertFalse(BedrockEnvironment.canInteract(TARGET.above()));
            }
            advance(f, 19);
            f.client.level = knownLevel;
            set(f.client.player, Entity.class, "position", new Vec3(0.5D, 64.0D, 0.5D));
            removeAndNotify(f, TARGET.above());
            advanceTo(f, 24);
            var scheduler = scheduler(f);
            scheduler.started(PROBE);

            scheduler.beginTick(25, 6, 0, true);

            assertEquals(1, scheduler.availableStarts(),
                    "A cleanup cycle that became unobservable is censored instead of teaching an AIR/timeout duration");
        }
    }

    @Test void aLateReverseResidueRetakesCapacityBeforeReservedOwnersCanAllStart() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            var target = start(f);
            retireWithPiston(f, target, 18);
            advanceTo(f, 23);
            removeAndNotify(f, TARGET.above());
            advance(f, 24);
            f.beginFence();
            for (BlockPos pos : NEXT) {
                f.level.blocks.put(pos, Blocks.BEDROCK.defaultBlockState());
                f.level.blocks.put(pos.east(), Blocks.BEDROCK.defaultBlockState());
                f.plan(pos, Direction.UP);
                assertTrue(f.engine.submit(pos), "Read-only reservations retain their six-slot contract");
            }
            // MOVING_PISTON is real residue but cannot start a cleanup OPEN that would mask the cap gate.
            f.level.blocks.put(TARGET.above(), Blocks.MOVING_PISTON.defaultBlockState());
            notifyBlock(f, TARGET.above());
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(1);
            f.transport.packets.clear();

            for (long tick = 25; tick <= 30; tick++) advance(f, tick);

            long startedOwners = NEXT.stream().filter(owner -> f.transport.packets.stream()
                    .filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast)
                    .anyMatch(packet -> packet.getHitResult().getBlockPos().equals(owner))).count();
            assertEquals(5, startedOwners,
                    "The late old cycle leaves five real start slots, even when six newer owners were already reserved");
            assertTrue(f.client.level.getBlockState(TARGET.above()).is(Blocks.MOVING_PISTON));
        }
    }

    private static BedrockTarget start(BedrockAdmissionPipelineTest.Fixture f) throws Exception {
        f.cleanupWorld();
        Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(0);
        f.machine(Direction.UP);
        var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
        var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
        var target = new BedrockTarget(TARGET, f.client.level, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync);
        f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
        target.setStatus(BedrockTarget.Status.EXTENDED);
        f.targets.add(target);
        f.transport.packets.clear();
        advance(f, 0);
        assertTrue(target.hasTried(), "A reused EXTENDED machine must really send its first critical bundle");
        assertTrue(f.transport.packets.stream().anyMatch(ServerboundUseItemOnPacket.class::isInstance));
        return target;
    }

    private static void retireWithPiston(BedrockAdmissionPipelineTest.Fixture f, BedrockTarget target, long tick)
            throws Exception {
        for (BlockPos pos : target.getCleanupPositions()) {
            if (!pos.equals(TARGET.above())) removeAndNotify(f, pos);
        }
        f.level.blocks.remove(TARGET);
        f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState());
        advanceTo(f, tick);
    }

    private static void advanceTo(BedrockAdmissionPipelineTest.Fixture f, long tick) throws Exception {
        for (long next = f.runtime.currentTick() + 1; next <= tick; next++) advance(f, next);
    }

    private static void advance(BedrockAdmissionPipelineTest.Fixture f, long tick) throws Exception {
        f.tick(tick);
        if (f.client.level.getClass().getSimpleName().equals("ClockLevel")) {
            set(f.client.level, f.client.level.getClass(), "tick", tick);
        }
        f.engine.beginTick(true);
        f.engine.finishTick(true);
    }

    private static void removeAndNotify(BedrockAdmissionPipelineTest.Fixture f, BlockPos pos) {
        f.level.blocks.remove(pos);
        notifyBlock(f, pos);
    }

    private static void notifyBlock(BedrockAdmissionPipelineTest.Fixture f, BlockPos pos) {
        f.engine.onRuntimeEvent(new RuntimeEvent.BlockUpdated(pos.getX(), pos.getY(), pos.getZ()));
    }

    @SuppressWarnings("unchecked")
    private static BedrockThroughputScheduler<BlockPos> scheduler(BedrockAdmissionPipelineTest.Fixture f)
            throws Exception {
        return (BedrockThroughputScheduler<BlockPos>) get(f.engine, BedrockEngine.class, "throughputScheduler");
    }

    private static final class UnloadedLevel extends BedrockPlacementRegressionTest.TestLevel {
        @Override boolean isChunkCached(int x, int z) { return false; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.VOID_AIR.defaultBlockState(); }
    }
}
//#endif
