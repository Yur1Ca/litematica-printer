//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static me.aleksilassila.litematica.printer.integration.quickshulker.BedrockToolEligibilityTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Captures actual coordinator cleanup packets; block removal is never simulated. */
class BedrockCleanupBatchTest {
    private static final BlockPos FIRST = new BlockPos(-2, 1, 0);
    private static final BlockPos SECOND = new BlockPos(2, 1, 0);
    private static final BlockPos THIRD = new BlockPos(0, 1, 3);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void independentFastPairsShareOneTransportBatchAndKeepTheirNativeOrder() throws Exception {
        try (var f = new Fixture(FIRST, SECOND)) {
            f.player.breakSpeed = 4; // Native delta 0.8 needs a contiguous START / STOP pair.

            f.process(6, pos -> false);

            assertEquals(1, f.transport.batches.size());
            assertEquals(List.of(FIRST, FIRST, SECOND, SECOND), f.actions().stream().map(ServerboundPlayerActionPacket::getPos).toList());
            assertEquals(List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK),
                    f.actions().stream().map(ServerboundPlayerActionPacket::getAction).toList());
            for (var pos : List.of(FIRST, SECOND)) {
                assertTrue(f.pending(pos));
                assertFalse(f.traceLevel.getBlockState(pos).isAir());
            }
            f.process(6, pos -> false);
            assertEquals(1, f.transport.batches.size(), "In-flight positions cannot be resubmitted before their real result");
        }
    }

    @Test void anInstantBatchKeepsEachPositionPendingUntilItsOwnAirUpdate() throws Exception {
        try (var f = new Fixture(FIRST, SECOND)) {
            f.player.breakSpeed = 6; // Native delta 1.2: START alone.
            f.process(6, pos -> false);
            assertEquals(1, f.transport.batches.size());
            assertEquals(2, f.actions().size());
            assertTrue(f.actions().stream().allMatch(p -> p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK));
            var ack = BedrockPlacer.class.getDeclaredMethod("acknowledgeToolTick", Object.class, int.class);
            ack.setAccessible(true);
            ack.invoke(f.placer, f.transport, Integer.MAX_VALUE);
            assertTrue(f.pending(FIRST));
            assertTrue(f.pending(SECOND), "A sequence acknowledgement is not a block removal");
            f.traceLevel.blocks.remove(FIRST);
            var update = BedrockPlacer.class.getDeclaredMethod("onCleanupBlockUpdate", BlockPos.class);
            update.setAccessible(true);
            update.invoke(f.placer, FIRST);
            assertFalse(f.pending(FIRST));
            assertTrue(f.pending(SECOND));
        }
    }

    @Test void aRejectedBatchCommitsNeitherPendingPositionsNorDurability() throws Exception {
        try (var f = new Fixture(FIRST, SECOND)) {
            f.player.breakSpeed = 4;
            int before = f.toolBudget();
            f.transport.reject = true;
            f.process(6, pos -> false);
            assertFalse(f.pending(FIRST));
            assertFalse(f.pending(SECOND));
            assertEquals(before, f.toolBudget());
            f.transport.reject = false;
            f.process(6, pos -> false);
            assertEquals(1, f.transport.batches.size());
            assertEquals(before - 2, f.toolBudget());
        }
    }

    @Test void aGuardApprovesOnlyTheSafePrefixWithoutSpendingDurabilityOnZeroHardness() throws Exception {
        try (var f = new Fixture(FIRST, SECOND, THIRD)) {
            f.player.breakSpeed = 4;
            var tool = f.player.getMainHandItem();
            tool.setDamageValue(tool.getMaxDamage() - 2);
            f.traceLevel.blocks.put(THIRD, Blocks.SLIME_BLOCK.defaultBlockState());
            var checked = new ArrayList<Integer>();
            BedrockCriticalBatchTest.installGuard(f, 1, new int[1], checked);

            f.process(6, pos -> false);

            assertEquals(1, f.transport.batches.size());
            assertEquals(List.of(FIRST, FIRST, THIRD), f.actions().stream().map(ServerboundPlayerActionPacket::getPos).toList());
            assertTrue(checked.contains(1), "The second hard block must be checked at its projected guard boundary");
            assertEquals(1, f.toolBudget());
            assertFalse(f.pending(SECOND));
            assertTrue(f.pending(THIRD));
            assertEquals(2, tool.getMaxDamage() - tool.getDamageValue(), "Projection must not change the visible tool");
        }
    }

    @Test void reservedMovingAndOverLimitPositionsRemainUnsubmitted() throws Exception {
        try (var f = new Fixture(FIRST, SECOND, THIRD)) {
            f.player.breakSpeed = 6;
            f.process(1, FIRST::equals);
            assertEquals(List.of(SECOND), f.actions().stream().map(ServerboundPlayerActionPacket::getPos).toList());
            assertFalse(f.pending(FIRST));
            assertFalse(f.pending(THIRD));
            f.traceLevel.blocks.put(FIRST, Blocks.MOVING_PISTON.defaultBlockState());
            f.process(6, pos -> false);
            assertFalse(f.pending(FIRST));
            assertTrue(f.pending(THIRD));
        }
    }

    @Test void aSlowOwnerCannotBeOvertakenByAnInstantStartInTheSameCleanupPass() throws Exception {
        try (var f = new Fixture(FIRST, SECOND)) {
            f.process(6, pos -> false); // Native delta 0.2 owns the sole delayed mining position.
            f.traceLevel.blocks.put(SECOND, Blocks.SLIME_BLOCK.defaultBlockState());
            f.process(6, pos -> false);
            assertEquals(1, f.actions().size());
            assertEquals(FIRST, f.actions().getFirst().getPos());
            assertFalse(f.pending(SECOND));
        }
    }

    @Test void dependentPartsKeepSeparateSubmissionsWithoutAddingAClientTick() throws Exception {
        try (var f = new Fixture(FIRST, FIRST.above())) {
            f.player.breakSpeed = 4;
            f.process(6, pos -> false);
            assertEquals(2, f.transport.batches.size());
            assertEquals(List.of(FIRST, FIRST, FIRST.above(), FIRST.above()),
                    f.actions().stream().map(ServerboundPlayerActionPacket::getPos).toList());
            assertTrue(f.pending(FIRST.above()), "Batch boundaries cannot postpone work that was already safe to submit this tick");
        }
    }

    @Test void anUnknownInstantResultKeepsItsExclusiveFallbackInsteadOfJoiningTheNextBatch() throws Exception {
        try (var f = new Fixture(FIRST, SECOND)) {
            var clock = BedrockToolEligibilityTest.allocate(BedrockCleanupInventorySessionTest.ClockLevel.class);
            clock.blocks = f.traceLevel.blocks;
            clock.border = f.traceLevel.border;
            f.client.level = clock;
            var prepare = BedrockPlacer.class.getDeclaredMethod("prepareCriticalToolTick");
            prepare.setAccessible(true);
            assertFalse((boolean) prepare.invoke(f.placer));
            var ack = BedrockPlacer.class.getDeclaredMethod("acknowledgeToolTick", Object.class, int.class);
            ack.setAccessible(true);
            for (var packet : List.copyOf(f.transport.packets)) {
                if (packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket marker) {
                    ack.invoke(f.placer, f.transport, marker.getSequence());
                }
            }
            assertTrue((boolean) prepare.invoke(f.placer));
            f.transport.packets.clear();
            f.transport.batches.clear();
            f.player.breakSpeed = 6;
            f.process(1, pos -> false);
            clock.tick = 16;

            f.process(6, pos -> false);

            assertEquals(List.of(FIRST, FIRST), f.actions().stream().map(ServerboundPlayerActionPacket::getPos).toList());
            assertTrue(f.actions().stream().allMatch(p -> p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK));
            assertFalse(f.pending(SECOND));
        }
    }

    private static class Fixture extends BedrockCriticalFlowTest.Fixture {
        final BedrockPlacer placer;
        final Object coordinator;

        Fixture(BlockPos... positions) throws Exception {
            placer = (BedrockPlacer) get(executor, executor.getClass(), "placer");
            var type = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockCleanupCoordinator");
            var ctor = type.getDeclaredConstructor(net.minecraft.client.Minecraft.class,
                    Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockNetworkSync"),
                    BooleanSupplier.class, BedrockPlacer.class);
            ctor.setAccessible(true);
            coordinator = ctor.newInstance(client, null, (BooleanSupplier) () -> false, placer);
            var add = type.getDeclaredMethod("enqueueAuthoritative", BlockPos.class);
            add.setAccessible(true);
            for (var pos : positions) {
                traceLevel.blocks.put(pos, Blocks.PISTON.defaultBlockState());
                add.invoke(coordinator, pos);
            }
        }

        void process(int limit, Predicate<BlockPos> reserved) throws Exception {
            var process = coordinator.getClass().getDeclaredMethod("process", Predicate.class, int.class);
            process.setAccessible(true);
            process.invoke(coordinator, reserved, limit);
        }

        boolean pending(BlockPos pos) throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("isCleanupPendingAt", BlockPos.class);
            method.setAccessible(true);
            return (boolean) method.invoke(placer, pos);
        }

        int toolBudget() throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("availableToolUses");
            method.setAccessible(true);
            return (int) method.invoke(placer);
        }

        List<ServerboundPlayerActionPacket> actions() {
            return transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
        }
    }
}
//#endif
