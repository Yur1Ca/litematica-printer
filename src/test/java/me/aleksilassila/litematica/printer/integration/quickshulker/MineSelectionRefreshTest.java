//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.alloc;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real Litematica selection changes, MineHandler dispatch and vanilla mining cancellation. */
class MineSelectionRefreshTest {
    private static final BlockPos FIRST = BlockPos.ZERO;
    private static final BlockPos SECOND = FIRST.east();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void shrinkingTheSelectionDiscardsTheOldQueuedTargetAndDispatchesTheRetainedTarget() throws Exception {
        try (var f = new Fixture(true)) {
            f.bounds(FIRST, SECOND);
            f.queue(FIRST);
            f.queue(SECOND);
            assertEquals(2, f.candidates.refresh().size());
            f.cooldowns.setCooldown(f.candidates.level, MineHandler.NAME, FIRST, 13);

            f.bounds(SECOND, SECOND);
            f.dispatch();

            assertEquals(List.of(SECOND), f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList(),
                    "Dispatch must recheck queued targets against the edited selection");
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions());
            assertTrue(f.candidates.refresh().isEmpty(), "The excluded queued target must be removed");
            assertEquals(13, f.cooldowns.getRemainingCooldown(f.candidates.level, MineHandler.NAME, FIRST),
                    "A selection edit must preserve existing block cooldowns");
            assertEquals(7, f.cooldowns.getRemainingCooldown(f.candidates.level, MineHandler.NAME, SECOND),
                    "A retained target still receives the normal mining cooldown");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void switchingTheSelectionCancelsAnActiveMineAndNeverSendsAFollowupDestroy(boolean toolChanged) throws Exception {
        try (var f = new Fixture(false)) {
            f.bounds(FIRST, FIRST);
            f.start(FIRST);
            assertTrue(f.mining.port.destroying);
            if (toolChanged) {
                f.candidates.selectSlot(1);
                assertFalse(f.mining.port.matchesDestroyTarget(FIRST),
                        "The native position is still owned by Mine even when its starting tool changed");
            }

            f.replaceSelection(SECOND, SECOND);
            f.continueActive();

            assertFalse(f.mining.port.destroying, "Selection exclusion must cancel the native mining session");
            assertEquals(0.0F, f.mining.port.progress);
            assertEquals(List.of(Action.START_DESTROY_BLOCK, Action.ABORT_DESTROY_BLOCK), f.actions());
            assertTrue(f.mining.connection.packets.stream().allMatch(packet -> packet.getPos().equals(FIRST)));
            assertFalse(f.mining.mineHandler.shouldKeepMiningOnIdleInput());

            for (int tick = 0; tick < 8; tick++) {
                f.candidates.level.tick++;
                f.continueActive();
                f.dispatch();
            }
            assertEquals(List.of(Action.START_DESTROY_BLOCK, Action.ABORT_DESTROY_BLOCK), f.actions(),
                    "The excluded target must not restart or finish after cancellation");
            assertEquals(7, f.cooldowns.getRemainingCooldown(f.candidates.level, MineHandler.NAME, FIRST),
                    "Cancelling a stale target must not reset its normal cooldown");
        }
    }

    @Test void anActiveMineInsideTheEditedSelectionKeepsItsProgress() throws Exception {
        try (var f = new Fixture(false)) {
            f.bounds(FIRST, SECOND);
            f.start(FIRST);
            float before = f.mining.port.progress;

            f.bounds(FIRST, FIRST);
            f.continueActive();

            assertTrue(f.mining.port.destroying);
            assertEquals(FIRST, f.mining.port.pos);
            assertTrue(f.mining.port.progress > before, "A still-valid active target must continue accumulating");
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions(),
                    "Editing another boundary must not abort or restart the retained active target");
        }
    }

    @Test void cancellingAnExcludedMineDoesNotAbortANativeTargetThatAnotherCallerStarted() throws Exception {
        try (var f = new Fixture(false)) {
            f.bounds(FIRST, SECOND);
            f.start(FIRST);
            f.candidates.state(SECOND, Blocks.STONE.defaultBlockState());
            assertEquals(BlockBreakResult.IN_PROGRESS,
                    f.mining.controller.continueDestroy(true, SECOND, Direction.DOWN, false, false));
            assertEquals(SECOND, f.mining.port.pos);
            float before = f.mining.port.progress;
            f.mining.connection.packets.clear();

            f.bounds(SECOND, SECOND);
            f.continueActive();

            assertTrue(f.mining.port.destroying, "The other caller still owns an active native target");
            assertEquals(SECOND, f.mining.port.pos);
            assertEquals(before, f.mining.port.progress);
            assertTrue(f.mining.connection.packets.isEmpty(),
                    "Discarding an old Mine target cannot abort or replace a different native target");
        }
    }

    @Test void trenchDiscoveryUsesTheEditedSelectionAndItsNewHorizontalBoundary() throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            BlockPos moved = FIRST.offset(4, 0, 0);
            f.bounds(FIRST, FIRST);
            f.candidates.state(FIRST.west(), Blocks.WATER.defaultBlockState());
            f.candidates.state(moved.east(), Blocks.WATER.defaultBlockState());
            f.refreshTrench();
            assertEquals(List.of(FIRST.west()), f.readyFillTargets());

            f.bounds(moved, moved);
            f.refreshTrench();

            assertEquals(List.of(moved.east()), f.readyFillTargets(),
                    "A stationary player must discover the new boundary and discard the old one");
        }
    }

    @Test void anActiveWaterloggedTrenchBoundaryContinuesOutsideTheEditedSelection() throws Exception {
        try (var f = new Fixture(false)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            f.bounds(FIRST.west(), FIRST);
            f.candidates.state(SECOND,
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
            f.refreshTrench();
            f.iterate(SECOND);
            assertTrue(f.mining.port.destroying);
            assertEquals(SECOND, f.mining.port.pos);
            float before = f.mining.port.progress;

            f.bounds(FIRST, FIRST);
            f.refreshTrench();
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(SECOND),
                    "The waterlogged block deliberately lies one horizontal block outside the selection");
            f.continueActive();

            assertTrue(f.mining.port.destroying, "The refreshed trench scope still owns this boundary target");
            assertEquals(SECOND, f.mining.port.pos);
            assertTrue(f.mining.port.progress > before);
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions(),
                    "The ordinary selection guard must preserve a valid trench boundary session");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MineCandidateRefreshFixture candidates = new MineCandidateRefreshFixture(false);
        final MiningInteractionRegressionTest.Fixture mining = candidates.mining;
        final CooldownUtils cooldowns = new CooldownUtils();
        final DataManager data = dataManager();
        final SelectionManager manager = DataManager.getSelectionManager();
        final Object oldArea = get(data, DataManager.class, "areaSimple");
        final SelectionMode oldMode = manager.getSelectionMode();
        final Object oldSelectionType = Configs.Mine.MINE_SELECTION_TYPE.getOptionListValue();
        final boolean oldTrench = Configs.Mine.MINE_TRENCH_MODE.getBooleanValue();
        final int oldCooldown = Configs.Break.BREAK_COOLDOWN.getIntegerValue();
        final int oldBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        Box box;

        Fixture(boolean creative) throws Exception {
            Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(false);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(false);
            Configs.Break.BREAK_COOLDOWN.setIntegerValue(7);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
            mining.player.abilities.instabuild = creative;
            mining.player.speed = 0.15F * 45.0F;
            candidates.level.predictions = mining.level.predictions;
            candidates.level.bridge = mining.level.bridge;
            set(candidates.level, Level.class, "dimension", Level.OVERWORLD);
            set(mining.mineHandler, FeatureModuleBase.class, "id", MineHandler.NAME);
            set(mining.mineHandler, FeatureModuleBase.class, "litematica", new LitematicaAdapter());
            set(mining.mineHandler, FeatureModuleBase.class, "cooldownUtils", cooldowns);
            set(mining.mineHandler, FeatureModuleBase.class, "timing", RuntimeTiming.disabled());
            set(mining.mineHandler, FeatureModuleBase.class, "playerInteractionBox",
                    new AtomicReference<>(new PrinterBox(-8, -8, -8, 8, 8, 8)));
            set(mining.mineHandler, FeatureModuleBase.class, "actionBroker",
                    Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ActionPort.class},
                            (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null));
            Class<?> scopeType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleSelectionScope");
            Constructor<?> scopeConstructor = scopeType.getDeclaredConstructor(FeatureModuleBase.class, ConfigOptionList.class);
            scopeConstructor.setAccessible(true);
            set(mining.mineHandler, FeatureModuleBase.class, "selectionScope",
                    scopeConstructor.newInstance(mining.mineHandler, Configs.Mine.MINE_SELECTION_TYPE));
            Class<?> trenchType = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.TrenchFillState");
            Constructor<?> trenchConstructor = trenchType.getDeclaredConstructor();
            trenchConstructor.setAccessible(true);
            set(mining.mineHandler, MineHandler.class, "trenchFill", trenchConstructor.newInstance());
            var hud = new HudStatsManager(mining.client, () -> candidates.level.tick);
            set(RuntimeAccess.get(), PrinterRuntime.class, "hudStats", hud);
            set(mining.mineHandler, FeatureModuleBase.class, "hudStats", hud);

            var gameMode = alloc(MineGameMode.class);
            gameMode.fixture = mining;
            gameMode.port = mining.port;
            gameMode.controller = mining.controller;
            set(gameMode, MultiPlayerGameMode.class, "minecraft", mining.client);
            set(gameMode, MultiPlayerGameMode.class, "connection", mining.connection);
            set(gameMode, MultiPlayerGameMode.class, "localPlayerMode", GameType.SURVIVAL);
            mining.client.gameMode = gameMode;
            set(mining.mineHandler, FeatureModuleBase.class, "gameMode", gameMode);
            replaceSelection(FIRST, SECOND);
            candidates.beginTick();
            callToolSession("beginTick");
        }

        void bounds(BlockPos first, BlockPos second) { box.setPos1(first); box.setPos2(second); }

        void replaceSelection(BlockPos first, BlockPos second) throws Exception {
            var selection = new AreaSelectionSimple(true);
            box = selection.getAllSubRegionBoxes().get(0);
            bounds(first, second);
            set(data, DataManager.class, "areaSimple", selection);
            set(manager, SelectionManager.class, "mode", SelectionMode.SIMPLE);
        }

        void queue(BlockPos pos) throws Exception {
            candidates.state(pos, Blocks.STONE.defaultBlockState());
            iterate(pos);
        }

        void iterate(BlockPos pos) throws Exception {
            invoke(MineHandler.class, mining.mineHandler, "executeIteration",
                    new Class<?>[]{BlockPos.class, AtomicReference.class}, pos, new AtomicReference<>(false));
        }

        void start(BlockPos pos) throws Exception { queue(pos); dispatch(); }
        void dispatch() throws Exception { invoke(MineHandler.class, mining.mineHandler, "stopIteration", new Class<?>[]{boolean.class}, false); }
        void continueActive() throws Exception { invoke(MineHandler.class, mining.mineHandler, "continueActiveMineTarget", new Class<?>[0]); }
        void refreshTrench() throws Exception { invoke(MineHandler.class, mining.mineHandler, "refreshTrenchFillTargets", new Class<?>[0]); }

        List<Action> actions() { return mining.connection.packets.stream().map(packet -> packet.getAction()).toList(); }

        @SuppressWarnings("unchecked")
        List<BlockPos> readyFillTargets() throws Exception {
            Object state = get(mining.mineHandler, MineHandler.class, "trenchFill");
            return (List<BlockPos>) invoke(state.getClass(), state, "readyFillTargets",
                    new Class<?>[]{long.class, Predicate.class, Predicate.class}, candidates.level.tick,
                    (Predicate<BlockPos>) pos -> candidates.level.getBlockState(pos).is(Blocks.WATER),
                    (Predicate<BlockPos>) pos -> true);
        }

        void callToolSession(String name) throws Exception {
            Object session = get(mining.mineHandler, MineHandler.class, "toolSession");
            invoke(session.getClass(), session, name, new Class<?>[0]);
        }

        @Override public void close() throws Exception {
            set(data, DataManager.class, "areaSimple", oldArea);
            set(manager, SelectionManager.class, "mode", oldMode);
            Configs.Mine.MINE_SELECTION_TYPE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldSelectionType);
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(oldTrench);
            Configs.Break.BREAK_COOLDOWN.setIntegerValue(oldCooldown);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(oldBudget);
            candidates.close();
        }
    }

    /** Bridges the field port to vanilla's real cancellation method, as the production mixin does. */
    static class MineGameMode extends MiningInteractionRegressionTest.GameMode {
        MiningInteractionRegressionTest.Fixture fixture;
        @Override public BlockBreakResult litematica_printer$continueDestroyBlockForMine(
                BlockPos pos, Direction direction, boolean allowToolSwitch) {
            return controller.continueForMine(pos, direction, allowToolSwitch);
        }

        @Override public void stopDestroyBlock() {
            try {
                fixture.copyToVanilla();
                super.stopDestroyBlock();
                fixture.copyFromVanilla();
            } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    private static DataManager dataManager() throws Exception {
        return (DataManager) invoke(DataManager.class, null, "getInstance", new Class<?>[0]);
    }

    private static Object invoke(Class<?> type, Object receiver, String name, Class<?>[] parameters,
                                 Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(receiver, arguments);
    }
}
//#endif
