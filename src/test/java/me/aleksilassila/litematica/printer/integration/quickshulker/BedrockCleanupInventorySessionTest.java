//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockBreaker;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static me.aleksilassila.litematica.printer.integration.quickshulker.BedrockToolEligibilityTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** All cleanup entry points must honor the same authoritative inventory response boundary. */
class BedrockCleanupInventorySessionTest {
    private static final BlockPos PISTON = BlockPos.ZERO.above();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void ownedCleanupSendsOneRealBundleAndWaitsForAirWithoutTheGenericMiningQueue() throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = 4; // Native delta=(8+1)*4/45=0.8: same-tick STOP, not instant START.
            assertTrue(f.cleanup(PISTON));
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), f.actions());
            assertEquals(0, f.mode.miningCalls, "Owned cleanup must not enter the generic delayed-destroy model");
            for (String entry : java.util.List.of("coordinator", "residue")) {
                Object cleanup = cleanup(entry, f, f.placer);
                advance(entry, f, f.placer, cleanup, 0);
            }
            for (int tick = 0; tick < 8; tick++) {
                f.clock.tick = tick;
                f.cleanup(PISTON);
            }
            assertEquals(2, f.actions().size(), "A sent cleanup waits for server air rather than resending every four ticks");
            assertFalse(f.clock.getBlockState(PISTON).isAir(), "Packet submission never predicts block removal");
        }
    }

    @Test void aSlowCleanupHoldsOneStartAndOneHandUntilItsServerAirArrives() throws Exception {
        try (var f = new CleanupFixture()) {
            BlockPos second = PISTON.east(2);
            f.clock.blocks.put(second, Blocks.PISTON.defaultBlockState());
            assertTrue(f.cleanup(PISTON)); // Native delta=(8+1)/45=0.2.
            f.cleanup(second);
            f.cleanup(PISTON);
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK), f.actions());
            assertTrue(f.active());
            for (int tick = 1; tick < 3; tick++) {
                f.clock.tick = tick;
                f.cleanup(second); // The coordinator may visit a different entry first.
                f.cleanup(PISTON);
                assertEquals(1, f.actions().size());
            }
            f.clock.tick = 3;
            f.cleanup(PISTON);
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), f.actions());
            assertTrue(f.active(), "STOP retains the hand until authoritative air, avoiding native delayed-slot races");
            f.cleanup(second);
            assertEquals(2, f.actions().size());
            f.clock.blocks.remove(PISTON);
            assertFalse(f.active());
            assertTrue(f.cleanup(second));
            assertEquals(3, f.actions().size());
        }
    }

    @Test void rejectedTransportCannotCreateAPendingCleanupOrHoldTheHand() throws Exception {
        try (var f = new CleanupFixture()) {
            f.transport.reject = true;
            assertFalse(f.cleanup(PISTON));
            assertFalse(f.active());
            f.transport.reject = false;
            assertTrue(f.cleanup(PISTON));
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK), f.actions());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"torch", "slime"})
    void zeroHardnessCleanupNeedsOnlyOneStartAndDoesNotSwitchTheHand(String kind) throws Exception {
        try (var f = new CleanupFixture()) {
            f.clock.blocks.put(PISTON, kind.equals("torch") ? Blocks.REDSTONE_TORCH.defaultBlockState()
                    : Blocks.SLIME_BLOCK.defaultBlockState());
            f.player.inventory.setItem(0, new ItemStack(Items.STONE));
            assertTrue(f.cleanup(PISTON));
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK), f.actions());
            assertTrue(f.player.getMainHandItem().is(Items.STONE));
            assertFalse(f.active());
            assertFalse(f.cleanup(PISTON), "Even an instant START waits for air before retrying the same position");
        }
    }

    @Test void anUnknownFastResultRetriesAtTheNetworkTimeoutWithoutDeletingTheBlock() throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = 4;
            f.cleanup(PISTON);
            f.clock.tick = 15;
            assertFalse(f.cleanup(PISTON));
            assertEquals(2, f.actions().size());
            f.clock.tick = 16;
            assertTrue(f.cleanup(PISTON));
            assertEquals(3, f.actions().size());
            assertTrue(f.active(), "An unknown result upgrades its retry to an owned OPEN session");
            assertFalse(f.clock.getBlockState(PISTON).isAir());
        }
    }

    @ParameterizedTest @ValueSource(ints = {4, 6})
    void anUnknownFastResultRetriesAsOneOwnedOpenStartThenStopAndAirClearsItsFallback(int speed) throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = speed; // Native client delta 0.8 or 1.2; server result deliberately stays unknown.
            f.cleanup(PISTON);
            int initial = f.actions().size();
            f.clock.tick = 16;
            assertTrue(f.cleanup(PISTON));
            assertEquals(initial + 1, f.actions().size(), "An unknown retry opens START alone, even for an estimated fast block");
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.actions().getLast());
            assertTrue(f.active(), "The exceptional retry owns the hand until server air");
            f.cleanup(PISTON);
            assertEquals(initial + 1, f.actions().size(), "Fallback STOP must cross a client tick boundary");
            f.clock.tick = 17;
            f.advanceMining();
            assertEquals(initial + 2, f.actions().size());
            assertEquals(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, f.actions().getLast());
            assertTrue(f.active());
            f.clock.blocks.remove(PISTON);
            var update = BedrockPlacer.class.getDeclaredMethod("onCleanupBlockUpdate", BlockPos.class);
            update.setAccessible(true);
            update.invoke(f.placer, PISTON);
            assertFalse(f.active());
            assertFalse(f.pending(PISTON));
            f.clock.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            assertTrue(f.cleanup(PISTON));
            assertEquals(initial + 2 + initial, f.actions().size(), "A future machine at the same coordinate returns to the normal fast path");
            assertFalse(f.active());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reset", "level", "epoch"})
    void fallbackOwnershipCannotLeakIntoANewCleanupContext(String change) throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = 6;
            f.cleanup(PISTON);
            f.clock.tick = 16;
            f.cleanup(PISTON);
            assertTrue(f.active());
            if (change.equals("reset")) {
                var reset = BedrockPlacer.class.getDeclaredMethod("resetInventorySync");
                reset.setAccessible(true);
                reset.invoke(f.placer);
            } else if (change.equals("level")) {
                f.client.level = f.traceLevel;
            } else {
                set(me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get(),
                        me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "epoch",
                        me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL);
            }
            assertTrue(f.cleanup(PISTON));
            assertFalse(f.active(), "A new context must use the normal first-attempt instant path");
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.actions().getLast());
        }
    }

    @Test void aRepeatedUnknownFallbackKeepsTheSameHandAndCannotOpenASecondPosition() throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = 6;
            BlockPos second = PISTON.east(2);
            f.clock.blocks.put(second, Blocks.PISTON.defaultBlockState());
            f.cleanup(PISTON);
            f.clock.tick = 16;
            f.cleanup(PISTON);
            f.clock.tick = 17;
            f.advanceMining();
            f.clock.tick = 33;
            f.advanceMining();
            assertTrue(f.active(), "An unresolved fallback cannot release MAIN to another native delayed slot");
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, f.actions().getLast());
            int sent = f.actions().size();
            f.cleanup(second);
            assertEquals(sent, f.actions().size());
            f.clock.tick = 34;
            f.advanceMining();
            assertEquals(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, f.actions().getLast());
            assertTrue(f.active());
        }
    }

    @Test void anAirUpdateReleasesPendingEvenWhenLateResidueReturnsBeforeTheNextTick() throws Exception {
        try (var f = new CleanupFixture()) {
            f.player.breakSpeed = 4;
            f.cleanup(PISTON);
            f.clock.blocks.remove(PISTON);
            var update = BedrockPlacer.class.getDeclaredMethod("onCleanupBlockUpdate", BlockPos.class);
            update.setAccessible(true);
            update.invoke(f.placer, PISTON);
            f.clock.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            assertTrue(f.cleanup(PISTON));
            assertEquals(4, f.actions().size());
        }
    }

    @Test void resettingAnOwnedSlowCleanupAbortsItsOpenStartWithoutRemovingTheBlock() throws Exception {
        try (var f = new CleanupFixture()) {
            f.cleanup(PISTON);
            var reset = BedrockPlacer.class.getDeclaredMethod("resetInventorySync");
            reset.setAccessible(true);
            reset.invoke(f.placer);
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK), f.actions());
            assertFalse(f.active());
            assertFalse(f.clock.getBlockState(PISTON).isAir());
        }
    }

    @Test void changingTheLevelDropsOldMiningWithoutSendingItsAbortIntoTheNewWorld() throws Exception {
        try (var f = new CleanupFixture()) {
            f.cleanup(PISTON);
            f.client.level = f.traceLevel;
            assertFalse(f.active());
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK), f.actions());
            assertFalse(f.clock.getBlockState(PISTON).isAir());
        }
    }

    @Test void cleanupOwnershipQueriesNeverSendPacketsEvenAfterTheAttemptTimesOut() throws Exception {
        try (var f = new CleanupFixture()) {
            f.cleanup(PISTON);
            f.clock.tick = 3;
            f.advanceMining();
            f.clock.tick = 20;
            int sent = f.connection.packets.size();
            assertTrue(f.active(), "Only the mutation phase closes an expired attempt");
            assertTrue(f.pending(PISTON));
            assertFalse(f.pending(PISTON.east(3)));
            assertEquals(sent, f.connection.packets.size(), "Read-only admission must not abort or restart cleanup");
            f.advanceMining();
            assertFalse(f.active());
            assertTrue(f.pending(PISTON), "A timeout retains the unknown coordinate until an owned retry or server air");
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK), f.actions());
        }
    }

    @Test void independentMiningAdvanceFinishesAnOpenTargetWithoutAnotherQueueVisit() throws Exception {
        try (var f = new CleanupFixture()) {
            f.cleanup(PISTON);
            f.clock.tick = 3;
            f.advanceMining();
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), f.actions());
            assertTrue(f.active());
            f.advanceMining();
            assertEquals(2, f.actions().size(), "Independent advancement only progresses the existing attempt");
        }
    }

    @Test void cleanupOutsideTheWorldBorderCannotSendEvenAnInstantStart() throws Exception {
        try (var f = new CleanupFixture()) {
            f.clock.blocks.put(PISTON, Blocks.REDSTONE_TORCH.defaultBlockState());
            f.clock.border.setCenter(100, 100);
            f.clock.border.setSize(2);
            assertFalse(f.clock.border.isWithinBounds(PISTON));
            assertFalse(f.cleanup(PISTON), "The owned cleanup must preserve vanilla's world-border restriction");
            assertTrue(f.actions().isEmpty());
            assertFalse(f.pending(PISTON));
        }
    }

    @Test void aWorldBorderChangeCancelsAnOpenCleanupBeforeItCanSendStop() throws Exception {
        try (var f = new CleanupFixture()) {
            f.cleanup(PISTON);
            f.clock.border.setCenter(100, 100);
            f.clock.border.setSize(2);
            f.clock.tick = 3;
            f.advanceMining();
            assertFalse(f.active());
            assertEquals(java.util.List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK), f.actions());
            assertFalse(f.clock.getBlockState(PISTON).isAir());
        }
    }

    static class ClockLevel extends BedrockCriticalFlowTest.TraceLevel {
        long tick;
        @Override public long getGameTime() { return tick; }
    }

    static class CleanupFixture extends BedrockCriticalFlowTest.Fixture {
        final ClockLevel clock = BedrockToolEligibilityTest.allocate(ClockLevel.class);
        final CleanupMode mode = BedrockToolEligibilityTest.allocate(CleanupMode.class);
        final BedrockPlacer placer;

        CleanupFixture() throws Exception {
            clock.blocks = traceLevel.blocks;
            clock.border = traceLevel.border;
            client.level = clock;
            client.gameMode = mode;
            var ctor = BedrockPlacer.class.getDeclaredConstructor(Minecraft.class);
            ctor.setAccessible(true);
            placer = ctor.newInstance(client);
        }

        boolean cleanup(BlockPos pos) { return BedrockBreaker.breakBlock(pos, Direction.DOWN, false, placer); }
        boolean active() throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("hasActiveCleanupMining");
            method.setAccessible(true);
            return (boolean) method.invoke(placer);
        }
        boolean pending(BlockPos pos) throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("isCleanupPendingAt", BlockPos.class);
            method.setAccessible(true);
            return (boolean) method.invoke(placer, pos);
        }
        void advanceMining() throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("advanceCleanupMining");
            method.setAccessible(true);
            method.invoke(placer);
        }
        java.util.List<ServerboundPlayerActionPacket.Action> actions() {
            return connection.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).map(ServerboundPlayerActionPacket::getAction).toList();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"breaker", "coordinator", "residue"})
    void aPredictedToolSwapCannotAuthorizeCleanupBeforeItsOrderedReply(String entry) throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var mode = BedrockToolEligibilityTest.allocate(CleanupMode.class);
            f.client.gameMode = mode;
            set(f.traceLevel, Level.class, "dimension", Level.OVERWORLD);
            var constructor = Arrays.stream(BedrockPlacer.class.getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 3).findFirst();
            assertTrue(constructor.isPresent(), "Dedicated cleanup needs its inventory session");
            constructor.get().setAccessible(true);
            var placer = (BedrockPlacer) constructor.get().newInstance(f.client, null, true);
            // Exercise vanilla's real client-side SWAP prediction. Matching the
            // hand alone must not release the later authoritative response fence.
            f.player.inventory.setItem(9, f.player.inventory.getItem(0));
            f.player.inventory.setItem(0, new ItemStack(Items.STONE));
            f.player.inventoryMenu.clicked(9, 0, ContainerInput.SWAP, f.player);
            assertTrue(f.player.getMainHandItem().is(Items.DIAMOND_PICKAXE));
            var fence = InventorySyncPackets.prepareFence(f.transport);
            f.transport.litematica_printer$sendBatch(java.util.List.of(
                    InventorySyncPackets.createPlayerInventoryFullSync(f.player), fence.packet()));
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(placer, fence);
            Object cleanup = cleanup(entry, f, placer);

            for (int tick = 0; tick < 3; tick++) {
                f.tick = tick;
                advance(entry, f, placer, cleanup, tick * 4);
            }

            assertEquals(0, starts(f), "A predicted tool must not bypass the shared placement inventory fence");
            InventoryRecoveryFence.received(f.transport);
            f.tick = 4;
            advance(entry, f, placer, cleanup, 12);
            for (var packet : java.util.List.copyOf(f.connection.packets)) {
                if (packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket marker) {
                    var ack = BedrockPlacer.class.getDeclaredMethod("acknowledgeToolTick", Object.class, int.class);
                    ack.setAccessible(true);
                    ack.invoke(placer, f.transport, marker.getSequence());
                }
            }
            advance(entry, f, placer, cleanup, 13);
            assertEquals(1, starts(f), "Cleanup resumes after its ordered inventory and native equipment replies");
            assertEquals(0, mode.miningCalls, "Owned cleanup keeps the generic mining queue untouched");
        }
    }

    private static long starts(BedrockCriticalFlowTest.Fixture f) {
        return f.connection.packets.stream().filter(packet -> packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).count();
    }

    private static Object cleanup(String entry, BedrockCriticalFlowTest.Fixture f, BedrockPlacer placer) throws Exception {
        if (entry.equals("breaker")) return null;
        if (entry.equals("coordinator")) {
            var type = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockCleanupCoordinator");
            var ctor = Arrays.stream(type.getDeclaredConstructors()).filter(c -> c.getParameterCount() == 5).findFirst();
            assertTrue(ctor.isPresent(), "Queued cleanup must retain its owning placer");
            ctor.get().setAccessible(true);
            Object result = ctor.get().newInstance(f.client, new CooldownUtils(), null, (BooleanSupplier) () -> false, placer);
            var add = type.getDeclaredMethod("add", BlockPos.class, boolean.class);
            add.setAccessible(true); add.invoke(result, PISTON, false);
            return result;
        }
        var layoutCtor = BedrockMachineLayout.class.getDeclaredConstructor(BlockPos.class, Direction.class);
        layoutCtor.setAccessible(true);
        var type = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetResidue");
        var ctor = assertDoesNotThrow(() -> type.getDeclaredConstructor(ClientLevel.class, BedrockMachineLayout.class,
                BlockPos.class, BlockPos.class, BedrockPlacer.class), "Recovery cleanup must retain its owning placer");
        ctor.setAccessible(true);
        return ctor.newInstance(f.traceLevel, layoutCtor.newInstance(BlockPos.ZERO, Direction.UP), PISTON, PISTON.above(), placer);
    }

    private static void advance(String entry, BedrockCriticalFlowTest.Fixture f, BedrockPlacer placer,
                                Object cleanup, int tick) throws Exception {
        if (entry.equals("breaker")) {
            var method = assertDoesNotThrow(() -> BedrockBreaker.class.getDeclaredMethod("breakBlock",
                    BlockPos.class, Direction.class, boolean.class, BedrockPlacer.class),
                    "Cleanup mining needs the owning placer's tool preparation boundary");
            method.invoke(null, PISTON, Direction.DOWN, false, placer);
        } else if (entry.equals("coordinator")) {
            var method = cleanup.getClass().getDeclaredMethod("process", Predicate.class, int.class);
            method.setAccessible(true); method.invoke(cleanup, (Predicate<BlockPos>) pos -> false, 1);
        } else {
            var method = cleanup.getClass().getDeclaredMethod("cleanupRecoveryResidue", int.class, Set.class);
            method.setAccessible(true); method.invoke(cleanup, tick, Set.of());
        }
    }

    static class CleanupMode extends BedrockCriticalFlowTest.Mode implements MultiPlayerGameModeExtension {
        int miningCalls;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            throw new AssertionError("Cleanup must not place blocks");
        }
        @Override public BlockBreakResult litematica_printer$continueDestroyBlock(boolean prediction, BlockPos pos,
                Direction face, boolean delayed, boolean switchTool) {
            this.miningCalls++;
            return BlockBreakResult.IN_PROGRESS;
        }
    }
}
//#endif
