//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.interaction.ToolSwitchService;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.lang.reflect.Proxy;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real admission and engine windows; only the client world and outbound transport are simulated. */
class BedrockAdmissionPipelineTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final List<BlockPos> TARGETS = List.of(FIRST, FIRST.east(4), FIRST.east(8),
            FIRST.south(4), FIRST.south(4).east(4), FIRST.south(4).east(8));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void reservationBudgetStartsClosedAndResetClosesAnOpenWindow() throws Exception {
        try (var f = new Fixture()) {
            assertFalse(f.engine.canSubmitInCurrentWindow(), "Only the engine tick may open an admission window");
            f.advance(0);
            assertTrue(f.engine.canSubmitInCurrentWindow());

            f.engine.reset();

            assertFalse(f.engine.canSubmitInCurrentWindow(), "A reset cannot retain the previous tick's capacity");
        }
    }

    @Test void anInventoryFenceAllowsSixReservationsWithoutAnyMutation() throws Exception {
        try (var f = new Fixture()) {
            f.beginFence();
            f.advance(0);
            var beforeWorld = new HashMap<>(f.level.blocks);
            var beforeInventory = f.client.player.getInventory().getItem(40).copy();
            int sent = f.transport.packets.size();

            assertTrue(f.engine.canScanForTargets());
            for (BlockPos pos : TARGETS) {
                f.plan(pos, Direction.UP);
                assertTrue(f.engine.canAccept(pos));
                assertTrue(f.engine.submit(pos));
            }

            assertEquals(6, f.engine.getHudSnapshot().activeTargets());
            assertFalse(f.engine.canAccept(FIRST), "Duplicate targets retain their reservation boundary");
            assertEquals(beforeWorld, f.level.blocks);
            assertEquals(beforeInventory.getCount(), f.client.player.getInventory().getItem(40).getCount());
            assertEquals(sent, f.transport.packets.size());
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.enginePlacer.isInventorySettling());

            f.advance(1);
            assertTrue(f.engine.canSubmitInCurrentWindow(), "Read-only reservations no longer depend on construction-start windows");
            assertFalse(f.engine.canScanForTargets(), "Six reserved complete cycles still hold the hard capacity");
            assertEquals(sent, f.transport.packets.size());
        }
    }

    @Test void aToolEquipmentAckOnlyGatesToolUseAndDoesNotConsumeTheReservationWindow() throws Exception {
        try (var f = new Fixture()) {
            f.beginFence();
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            assertFalse(f.enginePlacer.isInventorySettling(), "An equipment ACK does not own the inventory menu");
            int sent = f.transport.packets.size();

            f.advance(0);
            f.plan(FIRST, Direction.UP);

            assertTrue(f.engine.canScanForTargets());
            assertTrue(f.engine.submit(FIRST));
            assertTrue(f.enginePlacer.preparePistons(), "An already prepared offhand does not depend on MAIN equipment attributes");
            assertFalse(f.enginePlacer.prepareCriticalToolTick(), "Reservation cannot release the equipment acknowledgement");
            assertEquals(sent, f.transport.packets.size());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void readOnlyAdmissionCannotExpediteConflictingCleanupThroughAnInventoryFence() throws Exception {
        try (var f = new Fixture()) {
            f.beginFence();
            f.level.blocks.put(FIRST.above(), Blocks.PISTON.defaultBlockState());
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            cleanup.add(FIRST.above(), false);
            f.advance(0);
            f.plan(FIRST, Direction.UP);
            f.passInitialExposureDeferral(FIRST);
            int sent = f.transport.packets.size();

            assertFalse(f.engine.submit(FIRST));
            assertTrue(cleanup.contains(FIRST.above()));
            assertEquals(sent, f.transport.packets.size());
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.enginePlacer.isInventorySettling());
        }
    }

    @Test void readOnlyAdmissionQueuesConflictingCleanupWithoutStartingAnInventoryOrMiningAction() throws Exception {
        try (var f = new Fixture()) {
            f.advance(0);
            f.level.blocks.put(FIRST.above(), Blocks.PISTON.defaultBlockState());
            f.plan(FIRST, Direction.UP);
            f.passInitialExposureDeferral(FIRST);
            var beforeWorld = new HashMap<>(f.level.blocks);
            int sent = f.transport.packets.size();

            assertFalse(f.engine.submit(FIRST));

            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            assertTrue(cleanup.contains(FIRST.above()), "The ordinary cleanup phase owns the conflicting block");
            assertEquals(sent, f.transport.packets.size(), "Admission cannot acquire inventory or start breaking");
            assertEquals(beforeWorld, f.level.blocks);
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void aPendingCleanupDeletionCannotBeReusedAsAPrimingPiston() throws Exception {
        try (var f = new Fixture()) {
            var clock = f.cleanupWorld();
            f.advance(0);
            assertTrue(f.enginePlacer.breakCleanupBlock(FIRST.above(), Direction.DOWN));
            assertTrue(f.enginePlacer.isCleanupPendingAt(FIRST.above()));
            f.plan(FIRST, Direction.UP);
            f.passInitialExposureDeferral(FIRST);
            int sent = f.transport.packets.size();

            assertFalse(f.engine.submit(FIRST), "A reusable piston may already have a server-side deletion in flight");

            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            assertEquals(sent, f.transport.packets.size());
            assertFalse(clock.getBlockState(FIRST.above()).isAir());
        }
    }

    @Test void admissionCannotReleaseAnOwnedResidueFromAnUnloadedChunk() throws Exception {
        try (var f = new Fixture()) {
            BlockPos piston = FIRST.above();
            f.level.blocks.put(piston, Blocks.PISTON.defaultBlockState());
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            cleanup.add(piston, false);
            var candidate = f.target(FIRST, Direction.UP, f.enginePlacer);
            var unloaded = f.allocate(UnloadedLevel.class);
            unloaded.blocks = f.level.blocks;
            set(unloaded, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = unloaded;
            assertTrue(unloaded.hasChunk(piston.getX() >> 4, piston.getZ() >> 4),
                    "Native ClientLevel.hasChunk does not report client cache misses");
            assertFalse(unloaded.getChunkSource().hasChunk(piston.getX() >> 4, piston.getZ() >> 4));
            assertTrue(unloaded.getBlockState(piston).is(Blocks.VOID_AIR));
            assertTrue(BedrockEnvironment.canInteract(piston), "Distance alone does not guarantee a loaded client chunk");
            int sent = f.transport.packets.size();
            var admission = get(f.engine, BedrockEngine.class, "admission");
            var conflict = BedrockAdmissionController.class.getDeclaredMethod("findPendingCleanupConflict", BedrockTarget.class);
            conflict.setAccessible(true);

            assertEquals(piston, conflict.invoke(admission, candidate));
            assertTrue(cleanup.contains(piston), "Unknown client state cannot release previously owned cleanup");
            assertEquals(sent, f.transport.packets.size(), "Admission only preserves ownership; it cannot start cleanup");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anExistingSlowCleanupAdvancesEvenWhenItsPositionIsReservedAndTheQueueIsEmpty() throws Exception {
        try (var f = new Fixture()) {
            var clock = f.cleanupWorld();
            assertTrue(f.enginePlacer.breakCleanupBlock(FIRST.above(), Direction.DOWN));
            f.targets.add(f.target(FIRST, Direction.UP, f.enginePlacer));
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            assertTrue(cleanup.isEmpty());
            assertTrue(f.targets.isReserved(FIRST.above()));
            assertTrue(f.engine.hasActiveWork());
            clock.tick = 3;

            f.advance(3);

            assertEquals(List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), f.transport.packets.stream()
                    .filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).map(ServerboundPlayerActionPacket::getAction).toList());
            assertTrue(f.enginePlacer.hasActiveCleanupMining(), "STOP still owns the hand until authoritative air");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void enablingSideLimitsVerticalAndHorizontalPlansToOneSharedSlot() throws Exception {
        try (var f = new Fixture()) {
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(true);
            f.beginFence();
            f.advance(0);
            f.plan(FIRST, Direction.UP);

            assertTrue(f.engine.submit(FIRST));
            f.plan(TARGETS.get(1), Direction.NORTH);
            assertFalse(f.engine.submit(TARGETS.get(1)));
            var hud = f.engine.getHudSnapshot();
            assertEquals(6, hud.configuredThroughput(), "The stored user configuration stays visible");
            assertEquals(1, hud.submitCap());
            assertEquals(1, hud.activeCap());
            assertEquals(1, hud.verticalActiveCap());
            assertEquals(1, hud.totalTargets());
        }
    }

    @Test void enablingSideDrainsExistingVerticalReservationsBeforeAcceptingAnotherTarget() throws Exception {
        try (var f = new Fixture()) {
            f.beginFence();
            f.advance(0);
            for (BlockPos pos : TARGETS.subList(0, 3)) {
                f.plan(pos, Direction.UP);
                assertTrue(f.engine.submit(pos));
            }

            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(true);
            f.advance(2);
            f.plan(TARGETS.get(3), Direction.UP);

            assertEquals(3, f.engine.getHudSnapshot().totalTargets(), "A mode change cannot discard owned machines");
            assertFalse(f.engine.submit(TARGETS.get(3)));
            assertEquals(1, f.engine.getHudSnapshot().activeCap());
            assertTrue(f.enginePlacer.isInventorySettling());
        }
    }

    @Test void disablingSideWaitsForTheExistingHorizontalMachineToDrain() throws Exception {
        try (var f = new Fixture()) {
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(true);
            f.beginFence();
            f.advance(0);
            f.plan(FIRST, Direction.NORTH);
            assertTrue(f.engine.submit(FIRST));

            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(false);
            f.advance(2);
            f.plan(TARGETS.get(1), Direction.UP);

            assertFalse(f.engine.submit(TARGETS.get(1)), "An old horizontal look owner keeps its exclusive lane");
            assertEquals(1, f.engine.getHudSnapshot().sideTargets());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    private static final class ReadyPlayer extends BedrockPlacementRegressionTest.TestPlayer {
        ReadyPlayer() { super(); }
        @Override public Abilities getAbilities() { return new Abilities(); }
        @Override public boolean onGround() { return true; }
        @Override public boolean hasEffect(Holder<MobEffect> effect) { return false; }
        @Override public boolean isEyeInFluid(TagKey<Fluid> tag) { return false; }
        @Override public float getDestroySpeed(BlockState state) { return 9.0F; }
        @Override public double getAttributeValue(Holder<Attribute> attribute) {
            return attribute.equals(Attributes.BLOCK_BREAK_SPEED) ? 100.0D : 1.0D;
        }
        @Override public AttributeInstance getAttribute(Holder<Attribute> attribute) {
            var result = new AttributeInstance(attribute, ignored -> { });
            result.setBaseValue(getAttributeValue(attribute));
            return result;
        }
    }

    private static final class UnloadedLevel extends BedrockPlacementRegressionTest.TestLevel {
        @Override boolean isChunkCached(int x, int z) { return false; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.VOID_AIR.defaultBlockState(); }
    }

    private static final class ClockLevel extends BedrockPlacementRegressionTest.TestLevel {
        long tick;
        ClockLevel() { super(); }
        @Override public long getGameTime() { return tick; }
    }

    static class Fixture extends BedrockRestockTest.Fixture {
        final int oldQuantity = Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue();
        final int oldInterval = Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue();
        final boolean oldSide = Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
        final int oldWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        final Object oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        final SelectionManager selectionManager = DataManager.getSelectionManager();
        final SelectionMode oldSelectionMode = selectionManager.getSelectionMode();
        final DataManager data;
        final Object oldArea;

        Fixture() throws Exception {
            var instance = DataManager.class.getDeclaredMethod("getInstance");
            instance.setAccessible(true);
            data = (DataManager) instance.invoke(null);
            oldArea = get(data, DataManager.class, "areaSimple");
            var area = new AreaSelectionSimple(true);
            var box = area.getAllSubRegionBoxes().getFirst();
            box.setPos1(new BlockPos(-16, 64, -16));
            box.setPos2(new BlockPos(16, 64, 16));
            set(data, DataManager.class, "areaSimple", area);
            set(selectionManager, SelectionManager.class, "mode", SelectionMode.SIMPLE);
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(6);
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(false);
            Configs.Core.WORK_RANGE.setIntegerValue(16);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            var player = allocate(ReadyPlayer.class);
            player.inventory = ((BedrockPlacementRegressionTest.TestPlayer) client.player).inventory;
            set(player, Player.class, "inventory", player.inventory);
            set(player, Player.class, "inventoryMenu", client.player.inventoryMenu);
            player.containerMenu = player.inventoryMenu;
            set(player, LocalPlayer.class, "connection", connection);
            set(player, Entity.class, "position", new Vec3(0.5D, 64.0D, 0.5D));
            set(player, Entity.class, "eyeHeight", 1.5F);
            client.player = player;
            put(0, Items.DIAMOND_PICKAXE, 1);
            put(1, Items.REDSTONE_TORCH, 64);
            put(2, Items.SLIME_BLOCK, 64);
            put(40, Items.PISTON, 64);
            InventoryUtils.setSelectedSlot(player.inventory, 0);
            for (BlockPos pos : TARGETS) {
                level.blocks.put(pos, Blocks.BEDROCK.defaultBlockState());
                level.blocks.put(pos.east(), Blocks.BEDROCK.defaultBlockState());
            }
            mode.rejectMutations = true;
        }

        void beginFence() {
            var fence = InventorySyncPackets.prepareFence(transport);
            connection.send(fence.packet());
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(enginePlacer, fence);
        }

        void advance(long tick) throws Exception { tick(tick); engine.tick(); }

        ClockLevel cleanupWorld() throws Exception {
            var clock = allocate(ClockLevel.class);
            clock.blocks = level.blocks;
            set(clock, Level.class, "dimension", Level.OVERWORLD);
            client.level = clock;
            var guard = new InventorySwitchGuard(client, runtime::currentTick);
            var tweakeroo = (TweakerooToolSwitchPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TweakerooToolSwitchPort.class},
                    (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            set(runtime, PrinterRuntime.class, "toolSwitchService", new ToolSwitchService(client, tweakeroo, guard));
            beginFence();
            InventoryRecoveryFence.received(transport);
            assertFalse(enginePlacer.isInventorySettling());
            assertFalse(enginePlacer.prepareCriticalToolTick());
            enginePlacer.acknowledgeToolTick(transport, 0);
            assertFalse(enginePlacer.isInventorySettling());
            clock.blocks.put(FIRST.above(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));
            return clock;
        }

        void plan(BlockPos pos, Direction direction) throws Exception {
            var target = target(pos, direction, enginePlacer);
            engine.primeSubmissionPlan(pos, target.layout(),
                    new BedrockTorchPlacement(pos.east(), Direction.UP), null);
        }

        @SuppressWarnings("unchecked")
        void passInitialExposureDeferral(BlockPos pos) throws Exception {
            var admission = get(engine, BedrockEngine.class, "admission");
            var gate = (BedrockExposureGate<BlockPos>) get(admission, BedrockAdmissionController.class, "exposureGate");
            assertEquals(BedrockExposureGate.Decision.DEFER, gate.evaluate(pos, true, true));
        }

        @Override public void close() throws Exception {
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(oldQuantity);
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(oldInterval);
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(oldSide);
            Configs.Core.WORK_RANGE.setIntegerValue(oldWorkRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldShape);
            set(selectionManager, SelectionManager.class, "mode", oldSelectionMode);
            set(data, DataManager.class, "areaSimple", oldArea);
            super.close();
        }
    }
}
//#endif
