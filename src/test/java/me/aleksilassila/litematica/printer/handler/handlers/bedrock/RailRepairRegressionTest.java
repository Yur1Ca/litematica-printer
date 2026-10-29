package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.*;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.guide.guides.RailGuide;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.interaction.MiningInteractionPort;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.core.*;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;

/** Reuses the isolated client boundary; runs the real rail guide and mining queue. */
class RailRepairRegressionTest {
    static final BlockPos POS = new BlockPos(0, 64, 0);
    @BeforeAll static void bootstrapRails() { bootstrap(); }

    @Test void waitingInQueueDoesNotConsumeAttempts() throws Exception {
        try (var f = new RailFixture()) {
            for (int tick : new int[]{0, 40, 80, 200}) { f.level.time = tick; f.inspect(); }
            assertEquals(0, f.attempts(), "Queue admission is not an executed mining attempt");
        }
    }

    @Test void stableSuccessAllowsALaterRepairAtTheSamePosition() throws Exception {
        try (var f = new RailFixture()) {
            for (int round = 1; round <= 4; round++) {
                f.level.blocks.put(POS, f.wrong);
                f.inspect(); f.runtime.interactionUtils().onTick();
                assertEquals(round, f.mode.calls, "Stable successful repairs must not exhaust a lifetime quota");
                f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
                f.level.blocks.put(POS, f.target);
                f.inspect(); f.level.time+=50; f.inspect();
            }
        }
    }

    @Test void railCorrectedBeforeExecutionMustNotBeBroken() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.level.blocks.put(POS, f.target);
            f.runtime.interactionUtils().onTick();
            assertEquals(0, f.mode.calls);
        }
    }

    @Test void disablingRepairDiscardsItsQueuedBreak() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); Configs.Print.REPAIR_RAIL_SHAPE.setBooleanValue(false);
            f.runtime.interactionUtils().onTick();
            assertEquals(0, f.mode.calls);
        }
    }

    @Test void continuouslyWrongRailStopsAfterThreeActualAttempts() throws Exception {
        try (var f = new RailFixture()) {
            for (int i=0; i<8; i++) {
                f.level.time = i * 50;
                f.inspect(); f.runtime.interactionUtils().onTick();
                f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            }
            assertEquals(3, f.mode.calls);
        }
    }

    @ParameterizedTest @EnumSource(value=ResourceLease.class, names={"INVENTORY", "CONTAINER", "MAIN_HAND"})
    void inventoryOwnerMayDelayMiningWithoutUsingItsRetryBudget(ResourceLease resource) throws Exception {
        try (var f = new RailFixture()) {
            assertTrue(f.broker.tryAcquire("restock", EnumSet.of(resource), 0));
            for (int tick : new int[]{0, 39, 40, 80, 160, 240}) {
                f.level.time=tick; f.inspect(); f.runtime.interactionUtils().onTick();
            }
            assertEquals(0, f.mode.calls);
            assertEquals(0, f.attempts());
            f.broker.releaseOwner("restock");
            f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
            assertEquals(1, f.attempts());
        }
    }

    @Test void carriedItemDelaysRepairWithoutLosingIt() throws Exception {
        try (var f = new RailFixture()) {
            var carried = new ItemStack(Items.STONE);
            f.client.player.containerMenu.setCarried(carried);
            assertFalse(carried.isEmpty());
            for (int tick : new int[]{0, 40, 80, 200}) { f.level.time=tick; f.inspect(); f.runtime.interactionUtils().onTick(); }
            assertEquals(0, f.attempts());
            assertSame(carried, f.client.player.containerMenu.getCarried());
            f.client.player.containerMenu.setCarried(ItemStack.EMPTY);
            f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
        }
    }

    @Test void aLongRunningBreakAndItsServerWaitCountOnce() throws Exception {
        try (var f = new RailFixture()) {
            f.mode.result = BlockBreakResult.IN_PROGRESS;
            for (int tick : new int[]{0, 40, 80, 160}) { f.level.time=tick; f.inspect(); f.runtime.interactionUtils().onTick(); }
            assertEquals(1, f.attempts());
            f.mode.result = BlockBreakResult.COMPLETED_WAIT;
            f.runtime.interactionUtils().onTick();
            int calls = f.mode.calls;
            f.level.time = 1000; f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(calls, f.mode.calls, "Unconfirmed destruction must not be submitted again");
            f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(2, f.attempts());
        }
    }

    @Test void waitingForToolPreparationIsNotAMiningAttempt() throws Exception {
        try (var f = new RailFixture()) {
            f.mode.result = BlockBreakResult.IN_PROGRESS;
            f.mode.toolWaiting=true;
            for (int tick : new int[]{0, 40, 80, 200}) { f.level.time=tick; f.inspect(); f.runtime.interactionUtils().onTick(); }
            assertEquals(0, f.attempts());
            f.mode.toolWaiting=false;
            f.runtime.interactionUtils().onTick();
            assertEquals(1, f.attempts());
        }
    }

    @ParameterizedTest @EnumSource(value=BlockBreakResult.class, names={"FAILED", "ABORTED"})
    void noMiningStartedDoesNotUseAnAttempt(BlockBreakResult result) throws Exception {
        try (var f = new RailFixture()) {
            f.mode.result = result;
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(0, f.attempts());
            f.mode.result = BlockBreakResult.COMPLETED_WAIT;
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(1, f.attempts());
        }
    }

    @Test void transientCorrectShapeDoesNotPermitEndlessRepair() throws Exception {
        try (var f = new RailFixture()) {
            for (int i=0; i<8; i++) {
                f.level.time=i*50;
                f.level.blocks.put(POS, f.wrong); f.inspect(); f.runtime.interactionUtils().onTick();
                f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
                f.level.blocks.put(POS, f.target); f.inspect();
                f.level.time++; f.level.blocks.put(POS, f.wrong); f.inspect();
            }
            assertEquals(3, f.mode.calls);
        }
    }

    @Test void correctShapeCanSettleWithoutAnotherScan() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick(); f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.level.blocks.put(POS, f.target); f.inspect();
            f.level.time = 40; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(0, f.attempts());
        }
    }

    @Test void settlingResumesAfterCancelledQueueDrainsWithoutAnotherScan() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick(); f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.level.time=50; f.inspect();
            f.level.blocks.put(POS, f.target); f.inspect();
            f.runtime.interactionUtils().onTick();
            f.level.time=51; RailGuide.tickRepairState(f.level, f.schema);
            f.level.time=91; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(0, f.attempts());
        }
    }

    @Test void settlingResumesAfterPlacementConfirmationWithoutAnotherScan() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick(); f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.runtime.hudStats().trackExpectedBlockState(me.aleksilassila.litematica.printer.handler.HudStatsManager.Mode.PRINT, POS, f.target);
            f.level.blocks.put(POS, f.target); f.inspect();
            f.level.time=100; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(1, f.attempts());
            f.runtime.hudStats().confirmBlockUpdate(POS, f.target);
            RailGuide.tickRepairState(f.level, f.schema);
            f.level.time=140; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(0, f.attempts());
        }
    }

    @Test void unconfirmedPlacementDoesNotRearmRepairBudget() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick(); f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.runtime.hudStats().trackExpectedBlockState(me.aleksilassila.litematica.printer.handler.HudStatsManager.Mode.PRINT, POS, f.target);
            f.level.blocks.put(POS, f.target);
            for (int tick : new int[]{0, 40, 80, 200}) { f.level.time=tick; f.inspect(); }
            assertEquals(1, f.attempts());
            f.runtime.hudStats().confirmBlockUpdate(POS, f.target);
            f.inspect(); f.level.time+=40; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(0, f.attempts());
        }
    }

    @Test void unloadingChunkInterruptsTheStableObservation() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick(); f.runtime.interactionUtils().confirmServerBlockUpdate(POS);
            f.level.blocks.put(POS, f.target); f.inspect();
            f.level.loaded=false; f.level.time=10; RailGuide.tickRepairState(f.level, f.schema);
            f.level.loaded=true; f.level.time=100; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(1, f.attempts());
            f.inspect(); f.level.time+=40; RailGuide.tickRepairState(f.level, f.schema);
            assertEquals(0, f.attempts());
        }
    }

    @Test void changingWorldBeforeExecutionDoesNotMineTheNewWorld() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect();
            var next = f.allocate(RailLevel.class); next.blocks = new HashMap<>(); next.blocks.put(POS, f.wrong);
            set(next, Level.class, "dimension", Level.OVERWORLD); next.loaded=true;
            f.client.level=next;
            f.runtime.interactionUtils().onTick();
            assertEquals(0, f.mode.calls);
        }
    }

    @ParameterizedTest @ValueSource(strings={"work_switch_off", "client_scope_changed", "disconnect"})
    void runtimeResetDropsOldRequests(String reason) throws Exception {
        try (var f = new RailFixture()) {
            f.inspect();
            f.runtime.interactionUtils().onEpochChanged(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), reason));
            RailGuide.clearRepairState();
            f.runtime.interactionUtils().onTick();
            assertEquals(0, f.mode.calls);
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
        }
    }

    @Test void ordinaryMiningQueueDoesNotDependOnRailOption() throws Exception {
        try (var f = new RailFixture()) {
            Configs.Print.REPAIR_RAIL_SHAPE.setBooleanValue(false);
            f.runtime.interactionUtils().add(POS); f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
        }
    }

    @Test void miningConfirmationOutlivesTheShortQueueCooldown() throws Exception {
        try (var f = new RailFixture()) {
            f.inspect(); f.runtime.interactionUtils().onTick();
            f.mode.awaitingServer=true;
            for (int i=0; i<250; i++) f.runtime.interactionUtils().preprocess();
            f.level.time=250; f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
            assertEquals(1, f.attempts());
            f.mode.awaitingServer=false;
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(2, f.attempts());
        }
    }

    @Test void repairDoesNotResubmitAnExistingMiningConfirmation() throws Exception {
        try (var f = new RailFixture()) {
            f.mode.awaitingServer=true;
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(0, f.mode.calls);
            assertEquals(0, f.attempts());
            f.mode.awaitingServer=false;
            f.inspect(); f.runtime.interactionUtils().onTick();
            assertEquals(1, f.mode.calls);
        }
    }

    @ParameterizedTest @ValueSource(booleans={true, false})
    void stoppingRepairOnlyAbortsItsOwnActiveMining(boolean sameTarget) throws Exception {
        try (var f = new RailFixture()) {
            f.mode.result=BlockBreakResult.IN_PROGRESS;
            f.inspect(); f.runtime.interactionUtils().onTick();
            if (!sameTarget) f.mode.miningPos=POS.east();
            Configs.Print.REPAIR_RAIL_SHAPE.setBooleanValue(false);
            f.runtime.interactionUtils().onTick();
            assertEquals(sameTarget ? 1 : 0, f.mode.stops);
            assertFalse(f.runtime.interactionUtils().hasActiveDestroyTarget());
        }
    }

    static class Schematic extends WorldSchematic {
        Map<BlockPos, BlockState> states;
        //#if MC == 11802
        //$$ Schematic() { super(null, null, null); }
        //#elseif MC >= 12006 && MC < 12102
        //$$ Schematic() { super(null, null, null, null, null); }
        //#else
        Schematic() { super(null, null, null, null); }
        //#endif
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }
    static class RailLevel extends TestLevel {
        long time;
        boolean loaded;
        @Override public long getGameTime() { return time; }
        @Override public boolean hasChunk(int x, int z) { return loaded; }
    }
    static class RailMode extends TestGameMode implements MiningInteractionPort {
        int calls;
        int stops;
        boolean toolWaiting;
        boolean awaitingServer;
        BlockPos miningPos;
        BlockBreakResult result;
        @Override public BlockBreakResult litematica_printer$continueDestroyBlock(boolean prediction, BlockPos pos,
                Direction direction, boolean delayed, boolean switchTool) { calls++; return result; }
        @Override public void stopDestroyBlock() { stops++; }
        @Override public net.minecraft.client.Minecraft client() { return net.minecraft.client.Minecraft.getInstance(); }
        @Override public BlockPos destroyPos() { return miningPos; }
        @Override public void destroyPos(BlockPos pos) { }
        @Override public ItemStack destroyingItem() { return ItemStack.EMPTY; }
        @Override public void destroyingItem(ItemStack stack) { }
        @Override public float destroyProgress() { return 0.5F; }
        @Override public void destroyProgress(float value) { }
        @Override public boolean isDestroying() { return result == BlockBreakResult.IN_PROGRESS && !toolWaiting; }
        @Override public void isDestroying(boolean value) { }
        @Override public boolean destroyBlock(BlockPos pos) { throw new AssertionError("Unexpected local destroy"); }
        @Override public boolean matchesDestroyTarget(BlockPos pos) { return POS.equals(pos); }
        @Override public boolean isInventorySwitchPending() { return toolWaiting; }
        @Override public void ensureCarriedItemSent() { }
        @Override public boolean litematica_printer$isPendingDelayedDestroy(BlockPos pos) { return awaitingServer; }
        @Override public net.minecraft.network.protocol.game.ServerboundPlayerActionPacket actionPacket(
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action action,
                BlockPos pos, Direction direction, int sequence) { throw new AssertionError("Unexpected packet"); }
    }
    static class RailFixture extends Fixture {
        final boolean oldRepair = Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue();
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final Object oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        final Object oldType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        final RailLevel level;
        final Schematic schema;
        final RailMode mode;
        final ActionBroker broker;
        final BlockState target = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
        final BlockState wrong = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH);
        RailFixture() throws Exception {
            RailGuide.clearRepairState();
            Configs.Print.REPAIR_RAIL_SHAPE.setBooleanValue(true);
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
            level = allocate(RailLevel.class); level.blocks = new HashMap<>(); level.loaded=true;
            set(level, Level.class, "dimension", Level.OVERWORLD);
            client.level = level;
            schema = allocate(Schematic.class); schema.states = new HashMap<>();
            schema.states.put(POS, target); level.blocks.put(POS, wrong);
            mode = allocate(RailMode.class); mode.result = BlockBreakResult.COMPLETED_WAIT; mode.miningPos=POS;
            client.gameMode = mode;
            client.player.containerMenu = ChestMenu.threeRows(1, client.player.getInventory());
            broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null);
            set(runtime, PrinterRuntime.class, "actionBroker", broker);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(client, () -> level.time));
        }
        void inspect() { new Guides().buildAction(new SchematicBlockContext(client, level, schema, POS)); }
        @SuppressWarnings("unchecked")
        int attempts() throws Exception {
            return ((Map<?, Integer>) get(null, RailGuide.class, "repairAttempts")).values().stream().mapToInt(Integer::intValue).sum();
        }
        @Override public void close() throws Exception {
            RailGuide.clearRepairState();
            Configs.Print.REPAIR_RAIL_SHAPE.setBooleanValue(oldRepair);
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Core.WORK_MODE.setOptionListValue((WorkingModeType) oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue((PrintModeType) oldType);
            super.close();
        }
    }
}
