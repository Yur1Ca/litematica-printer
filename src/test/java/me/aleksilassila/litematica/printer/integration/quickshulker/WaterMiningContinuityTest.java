//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.FeatureModuleSet;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.*;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WaterMiningContinuityTest {
    private static final BlockPos SECOND = TARGET.east();
    private static final BlockPos ORDINARY = TARGET.north();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void twoSlowIceTargetsFinishWithoutAbortingEachOther() throws Exception {
        try (var f = new PrintFixture()) {
            f.waterTask(true);
            for (int tick = 0; tick < 12; tick++) {
                f.level.tick++;
                f.controller.tick();
                f.handler.canIterationBlockPos(TARGET);
                f.handler.canIterationBlockPos(SECOND);
            }
            assertEquals(List.of(TARGET, SECOND), f.connection.packets.stream()
                    .filter(p -> p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList());
            assertTrue(f.connection.packets.stream().noneMatch(
                    p -> p.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK));
        }
    }

    @Test void ordinaryPlacementWaitsOnlyWhileReachableWaterMiningOwnsTheHand() throws Exception {
        try (var f = new PrintFixture()) {
            f.waterTask(true);
            f.handler.canIterationBlockPos(TARGET);
            assertTrue(f.port.destroying);
            assertFalse(f.handler.canIterationBlockPos(ORDINARY),
                    "A normal placement must not switch the hand during a slow ice break");
            assertEquals(List.of(ORDINARY), f.targets.retainedTargets());
            f.handler.reachable = false;
            assertTrue(f.handler.canIterationBlockPos(ORDINARY),
                    "An unreachable old mining target cannot stall reachable print work");
            f.handler.reachable = true;
            f.handler.selected = false;
            assertTrue(f.handler.canIterationBlockPos(ORDINARY),
                    "Removing a mining target from the selection must release other work");
            f.handler.selected = true;
            f.port.destroying = false;
            assertTrue(f.handler.canIterationBlockPos(ORDINARY));
        }
    }

    @Test void theActiveIceTargetComesFirstAndOnlyOnceInTheIteration() throws Exception {
        try (var f = new PrintFixture()) {
            f.waterTask(true);
            f.handler.canIterationBlockPos(SECOND);
            f.workflows.tick(f.level, (WorldSchematic) f.schematicField.get(SchematicWorldHandler.INSTANCE));
            assertEquals(List.of(TARGET, SECOND), f.workflows.readyTargetPositions());
            var positions = new ArrayList<BlockPos>();
            f.handler.positions().forEach(positions::add);
            assertEquals(List.of(SECOND, TARGET, ORDINARY), positions,
                    "A small scan budget must reach the active break before deferred targets");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reach", "selection", "schematic", "restricted", "hand"})
    void invalidatedWaterMiningIsReleasedByIdleInput(String change) throws Exception {
        try (var f = new PrintFixture()) {
            f.waterTask(true);
            f.handler.canIterationBlockPos(TARGET);
            assertTrue(f.handler.shouldKeepMiningOnIdleInput());
            switch (change) {
                case "reach" -> f.handler.reachable = false;
                case "selection" -> f.handler.selected = false;
                case "schematic" -> f.handler.schematic = false;
                case "restricted" -> f.player.restricted = true;
                case "hand" -> f.player.held = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
            }
            f.idleInput(false);
            assertFalse(f.port.destroying);
            assertEquals(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    f.connection.packets.getLast().getAction());
        }
    }

    static class Schematic extends WorldSchematic {
        Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return pos.equals(ORDINARY) ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState();
        }
    }

    static class TestHandler extends PrintHandler {
        boolean reachable;
        boolean selected;
        boolean schematic;
        TestHandler() { super(null); }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return reachable; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return selected; }
        @Override protected boolean isSchematicBlock(BlockPos pos) { return schematic; }
        Iterable<BlockPos> positions() { return super.getIterationPositions(new PrinterBox(TARGET)); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) { return List.of(box); }
        @Override protected Iterable<BlockPos> getCachedFilteredIterationPositions(
                PrinterBox box, ScanIntent intent, Predicate<BlockPos> filter) {
            return List.of(TARGET, SECOND, ORDINARY);
        }
    }

    static class PrintFixture extends Fixture {
        final Object previousConfigClient = get(null, ConfigUtils.class, "client");
        final Object previousBoxClient = get(null, PrinterBox.class, "client");
        final Field schematicField = SchematicWorldHandler.class.getDeclaredField("world");
        final Object previousSchematic;
        final TestHandler handler = alloc(TestHandler.class);
        final PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        final boolean previousSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();

        PrintFixture() throws Exception {
            super(0.2f, true);
            schematicField.setAccessible(true);
            previousSchematic = schematicField.get(SchematicWorldHandler.INSTANCE);
            schematicField.set(SchematicWorldHandler.INSTANCE, alloc(Schematic.class));
            set(null, ConfigUtils.class, "client", client);
            set(null, PrinterBox.class, "client", client);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(false);
            handler.reachable = true;
            handler.selected = true;
            handler.schematic = true;
            set(handler, FeatureModuleBase.class, "mc", client);
            set(handler, FeatureModuleBase.class, "level", level);
            set(handler, FeatureModuleBase.class, "gameMode", client.gameMode);
            set(handler, FeatureModuleBase.class, "player", player);
            set(handler, FeatureModuleBase.class, "connection", connection);
            set(RuntimeAccess.get().modules(), FeatureModuleSet.class, "print", handler);
            set(handler, FeatureModuleBase.class, "litematica", new LitematicaAdapter());
            set(handler, FeatureModuleBase.class, "hudStats", new HudStatsManager(null, () -> level.tick));
            set(handler, PrintHandler.class, "targets", targets);
            set(handler, PrintHandler.class, "printTasks", workflows);
            set(handler, PrintHandler.class, "printSkipFilters", new String[0]);
            set(handler, PrintHandler.class, "guides", new me.aleksilassila.litematica.printer.guide.Guides());
            level.states = new HashMap<>();
            level.states.put(TARGET, Blocks.ICE.defaultBlockState());
            level.states.put(SECOND, Blocks.ICE.defaultBlockState());
            level.states.put(ORDINARY, Blocks.AIR.defaultBlockState());
        }

        @Override public void close() throws Exception {
            schematicField.set(SchematicWorldHandler.INSTANCE, previousSchematic);
            set(null, ConfigUtils.class, "client", previousConfigClient);
            set(null, PrinterBox.class, "client", previousBoxClient);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(previousSorted);
            super.close();
        }
    }
}
//#endif
