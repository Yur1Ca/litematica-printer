//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTarget;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTorchPlacement;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.integration.quickshulker.BedrockToolEligibilityTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the real tool, power and packet preflight; captures the complete transport batch. */
class BedrockCriticalBatchTest {
    private static final BlockPos FIRST = BlockPos.ZERO;
    private static final BlockPos SECOND = FIRST.south(3);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void independentTargetsUseOnlyTheirExploitPacketsWithoutAnInventoryRoundTrip() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            int[] preparations = {0};
            Runnable observePreparation = () -> {
                assertTrue(f.transport.packets.isEmpty(), "All targets must be preflighted before any packet is sent");
                preparations[0]++;
            };
            first.beforePowerCheck = observePreparation;
            second.beforePowerCheck = observePreparation;

            assertEquals(List.of(first, second), submit(f, List.of(first, second)));

            assertEquals(2, preparations[0]);
            assertEquals(1, f.transport.batches.size());
            var packets = f.transport.batches.getFirst();
            assertEquals(12, packets.size(), BedrockCriticalFlowTest.describe(packets));
            assertEquals(2, packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(0, packets.stream().filter(ServerboundContainerClickPacket.class::isInstance).count());
            assertEquals(0, packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count());
            assertEquals(2, get(f.executor, f.executor.getClass(), "reservedPistons"));
            assertTrue(f.player.inventory.getItem(40).isEmpty());
            assertFalse(first.hasTried());
            assertFalse(second.hasTried());
            assertEquals(BedrockTarget.Status.EXTENDED, first.getStatus());
            assertEquals(BedrockTarget.Status.EXTENDED, second.getStatus());
            assertTrue(f.traceLevel.getBlockState(first.getPistonPos()).getValue(PistonBaseBlock.EXTENDED));
            assertFalse(settling(f), "A raw exploit batch cannot stall the pipeline on an unrelated menu snapshot");
        }
    }

    @Test void aGuardFarFromItsThresholdKeepsSingleBodiesWithoutPerUseInventoryPolling() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            int[] guardedPreparations = {0};
            installGuard(f, 1, guardedPreparations);

            assertEquals(List.of(first), submit(f, List.of(first, second)));

            assertEquals(1, guardedPreparations[0], "A second body cannot reuse approval for unchanged client durability");
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(1, f.transport.batches.size());
            assertFalse(settling(f), "The first guarded raw body does not request an eager inventory snapshot");
            assertEquals(List.of(second), submit(f, List.of(second)),
                    "Conservatively projected damage is still safe under the guard's actual policy");
            assertEquals(2, guardedPreparations[0]);
            assertFalse(settling(f));
            assertEquals(0, f.transport.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count());
        }
    }

    @Test void aGuardAtItsActualThresholdCalibratesUnconfirmedDamageWithoutChangingTheVisibleTool() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            var tool = f.player.getMainHandItem();
            tool.setDamageValue(tool.getMaxDamage() - 2);
            int visibleDamage = tool.getDamageValue();
            int[] preparations = {0};
            installGuard(f, 1, preparations);

            assertEquals(List.of(first), submit(f, List.of(first, second)));
            assertFalse(settling(f));
            assertTrue(submit(f, List.of(second)).isEmpty());

            assertEquals(1, preparations[0], "A projected unsafe tool is calibrated before another real guard decision");
            assertTrue(settling(f));
            assertEquals(visibleDamage, f.player.getMainHandItem().getDamageValue(), "Policy projection only uses a copy");
            f.player.getMainHandItem().setDamageValue(tool.getMaxDamage() - 1); // Actual native damage arrives with the snapshot.
            InventoryRecoveryFence.received(f.transport);
            assertFalse(settling(f));
            assertTrue(submit(f, List.of(second)).isEmpty(), "The guard's real policy rejects the now-authoritative unsafe tool");
            assertEquals(2, preparations[0]);
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
        }
    }

    private static void installGuard(BedrockCriticalFlowTest.Fixture f, int threshold, int[] preparations) throws Exception {
        var capability = me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort.class;
        var port = (me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort)
                java.lang.reflect.Proxy.newProxyInstance(capability.getClassLoader(), new Class<?>[]{capability},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "isDurabilityGuardActive" -> true;
                            case "isCurrentToolUsable" -> {
                                var stack = (ItemStack) args[0];
                                yield stack.getMaxDamage() - stack.getDamageValue() > threshold;
                            }
                            case "prepareCurrentTool" -> {
                                preparations[0]++;
                                var stack = f.player.getMainHandItem();
                                yield stack.getMaxDamage() - stack.getDamageValue() > threshold;
                            }
                            default -> method.getReturnType() == boolean.class ? false : null;
                        });
        BedrockToolEligibilityTest.set(me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get(),
                me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "toolSwitchService",
                new me.aleksilassila.litematica.printer.interaction.ToolSwitchService(f.client, port, f.guard));
    }

    @Test void aSharedPowerSourceCannotJoinTheSameBatchBeforeTargetsAreMarkedDispatched() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, FIRST.east(2), FIRST.east(), Direction.UP);

            assertEquals(List.of(first), submit(f, List.of(first, second)));

            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(1, f.player.inventory.getItem(40).getCount());
        }
    }

    @Test void offhandReservationsLimitTheBatchBeforeAnyPistonIsConsumed() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));

            assertEquals(List.of(first), submit(f, List.of(first, second)));

            assertTrue(f.player.inventory.getItem(40).isEmpty());
            assertEquals(1, f.transport.batches.size());
        }
    }

    @Test void theLastToolDurabilityCannotBeReservedByTwoTargets() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            var tool = f.player.getMainHandItem();
            tool.setDamageValue(tool.getMaxDamage() - 1);

            assertEquals(List.of(first), submit(f, List.of(first, second)));

            assertEquals(1, f.player.inventory.getItem(40).getCount());
        }
    }

    @Test void anUnrelatedStatsReplyDoesNotCreateAnInventoryFenceForAnOrdinaryBatch() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            var old = new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS);
            InventoryRecoveryFence.sent(f.transport, old);

            assertEquals(2, submit(f, List.of(first, second)).size());

            InventoryRecoveryFence.received(f.transport);
            assertFalse(settling(f));
            InventoryRecoveryFence.received(f.transport);
            assertFalse(settling(f));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unlit", "wrong_priming", "tried", "power_conflict", "side", "collision"})
    void unsafeCandidatesSendNoDestructivePackets(String unsafe) throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var target = target(f, FIRST, FIRST.east(), unsafe.equals("side") ? Direction.EAST : Direction.UP);
            if (unsafe.equals("unlit")) f.traceLevel.blocks.put(target.getTorchPos(),
                    Blocks.REDSTONE_TORCH.defaultBlockState().setValue(RedstoneTorchBlock.LIT, false));
            if (unsafe.equals("wrong_priming")) f.traceLevel.blocks.put(target.getPistonPos(),
                    Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN)
                            .setValue(PistonBaseBlock.EXTENDED, true));
            if (unsafe.equals("tried")) target.setHasTried(true);
            if (unsafe.equals("power_conflict")) target.mayMutatePower = false;
            if (unsafe.equals("collision")) f.traceLevel.obstructed = true;

            assertTrue(submit(f, List.of(target)).isEmpty());

            assertTrue(f.transport.packets.stream().noneMatch(ServerboundPlayerActionPacket.class::isInstance));
            assertTrue(f.transport.batches.isEmpty());
            assertEquals(2, f.player.inventory.getItem(40).getCount());
            assertFalse(settling(f));
        }
    }

    @Test void rejectedTransportDoesNotConsumeMaterialsOrCreateAnInventoryBarrier() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            f.transport.reject = true;

            assertTrue(submit(f, List.of(first, second)).isEmpty());

            assertTrue(f.transport.packets.isEmpty());
            assertEquals(2, f.player.inventory.getItem(40).getCount());
            assertEquals(0, get(f.executor, f.executor.getClass(), "reservedPistons"));
            assertFalse(settling(f));
            assertFalse(first.hasTried());
        }
    }

    @Test void aLaterCandidatesInventoryMoveCancelsAlreadyFrozenBodiesWithoutReplacingItsFence() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var first = target(f, FIRST, FIRST.east(), Direction.UP);
            var second = target(f, SECOND, SECOND.east(), Direction.UP);
            var placer = get(f.executor, f.executor.getClass(), "placer");
            InventorySyncPackets.SyncFence[] original = {null};
            second.beforePowerCheck = () -> {
                try {
                    assertTrue(f.transport.packets.isEmpty(), "The first body's packets must still be frozen locally");
                    f.player.inventory.setItem(9, new ItemStack(Items.STONE));
                    f.player.inventoryMenu.clicked(9, 0, ContainerInput.SWAP, f.player);
                    original[0] = InventorySyncPackets.prepareFence(f.transport);
                    f.transport.litematica_printer$sendBatch(List.of(
                            InventorySyncPackets.createPlayerInventoryFullSync(f.player), original[0].packet()));
                    me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(placer, original[0]);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            };

            assertTrue(submit(f, List.of(first, second)).isEmpty(),
                    "A later preparation transaction must cancel all previously frozen critical bodies");

            assertTrue(f.transport.packets.stream().noneMatch(ServerboundUseItemOnPacket.class::isInstance));
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundPlayerActionPacket.class::isInstance));
            assertEquals(1, f.transport.batches.size(), "Only the preparation snapshot and marker may be sent");
            assertEquals(2, f.player.inventory.getItem(40).getCount());
            var session = get(placer, placer.getClass(), "inventory");
            assertSame(original[0], get(session, session.getClass(), "pending"),
                    "The critical tail must not overwrite another transaction's live fence");
            assertTrue(settling(f));
            InventoryRecoveryFence.received(f.transport);
            assertFalse(settling(f));
            assertFalse(first.hasTried());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BedrockTarget> submit(BedrockCriticalFlowTest.Fixture f, List<BedrockTarget> targets) throws Exception {
        var method = assertDoesNotThrow(() -> f.executor.getClass().getDeclaredMethod("submitBatch", List.class),
                "Independent vertical machines need one indivisible critical batch");
        method.setAccessible(true);
        return (List<BedrockTarget>) method.invoke(f.executor, targets);
    }

    private static boolean settling(BedrockCriticalFlowTest.Fixture f) throws Exception {
        var placer = get(f.executor, f.executor.getClass(), "placer");
        var method = placer.getClass().getDeclaredMethod("isInventorySettling");
        method.setAccessible(true);
        return (boolean) method.invoke(placer);
    }

    private static Target target(BedrockCriticalFlowTest.Fixture f, BlockPos bedrock, BlockPos support,
                                 Direction priming) throws Exception {
        var ctor = BedrockMachineLayout.class.getDeclaredConstructor(BlockPos.class, Direction.class);
        ctor.setAccessible(true);
        var layout = ctor.newInstance(bedrock, priming);
        f.traceLevel.blocks.put(bedrock, Blocks.BEDROCK.defaultBlockState());
        f.traceLevel.blocks.put(layout.getPistonPos(), Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, priming).setValue(PistonBaseBlock.EXTENDED, true));
        f.traceLevel.blocks.put(support, Blocks.SLIME_BLOCK.defaultBlockState());
        f.traceLevel.blocks.put(support.above(), Blocks.REDSTONE_TORCH.defaultBlockState());
        var result = new Target(f, bedrock, layout, new BedrockTorchPlacement(support, Direction.UP));
        result.setStatus(BedrockTarget.Status.EXTENDED);
        return result;
    }

    private static final class Target extends BedrockTarget {
        boolean mayMutatePower = true;
        Runnable beforePowerCheck;
        Target(BedrockCriticalFlowTest.Fixture f, BlockPos pos, BedrockMachineLayout layout, BedrockTorchPlacement torch) {
            super(pos, f.traceLevel, layout, torch, null);
        }
        @Override public boolean canMutatePower() {
            if (this.beforePowerCheck != null) {
                Runnable action = this.beforePowerCheck;
                this.beforePowerCheck = null;
                action.run();
            }
            return this.mayMutatePower;
        }
    }
}
//#endif
