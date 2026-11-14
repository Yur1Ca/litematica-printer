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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.alloc;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real Litematica selection changes, MineHandler dispatch and vanilla mining cancellation. */
class MineSelectionRefreshTest {
    private static final BlockPos FIRST = BlockPos.ZERO;
    private static final BlockPos SECOND = FIRST.east();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void aHugeTrenchSelectionUsesBoundedWorldReadsAndResumesInsteadOfWalkingTheWholeVolume() throws Exception {
        int previousWork = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new Fixture(true)) {
            Configs.Core.WORK_PER_TICK.setIntegerValue(256);
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            Configs.Core.WORK_RANGE.setIntegerValue(256);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(me.aleksilassila.litematica.printer.enums.RadiusShapeType.CUBE);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
            f.bounds(new BlockPos(0, 0, 0), new BlockPos(256, 255, 0));
            set(f.mining.mineHandler, FeatureModuleBase.class, "playerInteractionBox",
                    new AtomicReference<>(new PrinterBox(0, 0, 0, 256, 255, 0)));
            var scan = f.installScan();
            BlockPos early = new BlockPos(0, -1, 0);
            BlockPos late = new BlockPos(256, -1, 0);
            f.candidates.level.trenchWorld = java.util.Map.of(early, Blocks.WATER.defaultBlockState(),
                    late, Blocks.WATER.defaultBlockState());
            f.candidates.level.trenchReadLimit = 10_000;
            boolean recovered = false;
            for (int tick = 1; tick <= 100; tick++) {
                f.candidates.level.tick = tick;
                scan.beginTick(f.candidates.level, null, tick);
                f.candidates.level.trenchReads = 0;
                assertDoesNotThrow(f::refreshTrench,
                        "A large trench cannot perform a full-volume preprocessing pass in one tick");
                if (tick == 1) {
                    assertTrue(f.readyFillTargets().contains(early), "The global first floor coordinate belongs to the first slice");
                    assertFalse(f.readyFillTargets().contains(late), "X256 is after the 256-point global floor prefix");
                }
                if (f.readyFillTargets().contains(late)) { recovered = true; break; }
            }
            assertTrue(recovered, "Resumable discovery must eventually reach later tiles without another world event");
            assertEquals(java.util.Set.of(early, late), new java.util.HashSet<>(f.readyFillTargets()),
                    "The first slice's retained floor target and the later global coordinate both remain available");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

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
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions());
            float before = f.mining.port.progress;

            f.bounds(FIRST, FIRST);
            f.advanceTick();
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void trenchDiscoveryIncludesOnlyTheFirstFluidLayerBelowTheSelection(boolean lava) throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            f.bounds(FIRST, FIRST);
            BlockPos bottom = FIRST.below();
            f.candidates.state(bottom, (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState());
            f.candidates.state(FIRST.above(), Blocks.WATER.defaultBlockState());
            f.candidates.state(FIRST.below(2), Blocks.WATER.defaultBlockState());
            f.candidates.state(bottom.east(), Blocks.WATER.defaultBlockState());
            f.candidates.state(FIRST.offset(1, 0, 1), Blocks.WATER.defaultBlockState());

            f.refreshTrench();

            assertEquals(List.of(bottom), f.readyFillTargets(),
                    "Only the direct bottom face joins the existing horizontal boundary");
        }
    }

    @Test void trenchDiscoveryDoesNotTurnBottomAirIntoAPlatformTarget() throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            f.bounds(FIRST, FIRST);
            f.candidates.state(FIRST.below(), Blocks.AIR.defaultBlockState());

            f.refreshTrench();

            assertTrue(f.readyFillTargets().isEmpty(), "Trench filling still targets fluids, not ordinary air");
            assertTrue(f.readyWaterloggedTargets().isEmpty());
        }
    }

    @Test void trenchDiscoveryReplacesTheOldBottomAfterTheSelectionMoves() throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            BlockPos moved = FIRST.offset(4, 0, 0);
            f.bounds(FIRST, FIRST);
            f.candidates.state(FIRST.below(), Blocks.WATER.defaultBlockState());
            f.candidates.state(moved.below(), Blocks.LAVA.defaultBlockState());
            f.refreshTrench();
            assertEquals(List.of(FIRST.below()), f.readyFillTargets());

            f.bounds(moved, moved);
            f.refreshTrench();

            assertEquals(List.of(moved.below()), f.readyFillTargets(),
                    "Moving the selected trench must discard its old, unsent bottom targets");
        }
    }

    @Test void trenchDiscoveryIncludesAWaterloggedBlockOnTheBottomFace() throws Exception {
        try (var f = new Fixture(true)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            f.bounds(FIRST, FIRST);
            BlockPos bottom = FIRST.below();
            f.candidates.state(bottom,
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));

            f.refreshTrench();

            assertEquals(List.of(bottom), f.readyWaterloggedTargets());
            assertTrue(f.readyFillTargets().isEmpty(), "A waterlogged solid uses the existing break-before-fill path");
        }
    }

    @Test void anActiveWaterloggedTrenchFloorContinuesOutsideTheEditedSelection() throws Exception {
        try (var f = new Fixture(false)) {
            Configs.Mine.MINE_TRENCH_MODE.setBooleanValue(true);
            BlockPos bottom = FIRST.below();
            f.bounds(bottom, FIRST);
            f.candidates.state(bottom,
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
            f.refreshTrench();
            f.iterate(bottom);
            assertTrue(f.mining.port.destroying);
            assertEquals(bottom, f.mining.port.pos);
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions());
            float before = f.mining.port.progress;

            f.bounds(FIRST, FIRST);
            f.refreshTrench();
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(bottom));
            f.advanceTick();
            f.continueActive();

            assertTrue(f.mining.port.destroying, "The new bottom face still owns this waterlogged target");
            assertEquals(bottom, f.mining.port.pos);
            assertTrue(f.mining.port.progress > before);
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions(),
                    "A valid bottom target must retain progress without aborting or restarting");
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
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions());
            float before = f.mining.port.progress;

            f.bounds(FIRST, FIRST);
            f.refreshTrench();
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(SECOND),
                    "The waterlogged block deliberately lies one horizontal block outside the selection");
            f.advanceTick();
            f.continueActive();

            assertTrue(f.mining.port.destroying, "The refreshed trench scope still owns this boundary target");
            assertEquals(SECOND, f.mining.port.pos);
            assertTrue(f.mining.port.progress > before);
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions(),
                    "The ordinary selection guard must preserve a valid trench boundary session");
        }
    }

    @Test void freshDispatchUsesDescendingLayersThenSerpentineRows() throws Exception {
        try (var f = new Fixture(true)) {
            BlockPos upper = FIRST.above();
            BlockPos rowStart = new BlockPos(7, 0, 1);
            BlockPos rowEnd = new BlockPos(0, 0, 1);
            f.bounds(FIRST, new BlockPos(7, 1, 1));
            for (BlockPos pos : List.of(rowEnd, FIRST, upper, rowStart)) f.queue(pos);
            assertEquals(0L, (long) get(f.mining.mineHandler, FeatureModuleBase.class, "additionalIterationActionNanos"),
                    "Candidate analysis does not count as dispatched action time");

            f.dispatch();

            assertEquals(List.of(upper, FIRST, rowStart, rowEnd),
                    f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList());
            assertTrue((long) get(f.mining.mineHandler, FeatureModuleBase.class, "additionalIterationActionNanos") > 0L,
                    "The real stop-hook mining dispatch must report its action time separately from planning");
        }
    }

    @ParameterizedTest @CsvSource({"true, true", "false, true", "true, false"})
    void creativeMineKeepsItsExistingInstantHandPolicy(boolean enabled, boolean availableTool) throws Exception {
        Object oldInventoryClient = get(null, me.aleksilassila.litematica.printer.utils.InventoryUtils.class, "client");
        try (var f = new Fixture(true)) {
            var player = alloc(InventoryBackedMinePlayer.class);
            var previousPlayer = f.mining.player;
            player.abilities = previousPlayer.abilities;
            player.inventory = previousPlayer.inventory;
            player.speed = previousPlayer.speed;
            player.setId(42);
            set(player, net.minecraft.world.entity.player.Player.class, "inventory", player.inventory);
            set(player, net.minecraft.world.entity.Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(player, net.minecraft.world.entity.Entity.class, "blockPosition", FIRST);
            set(player, net.minecraft.world.entity.Entity.class, "level", f.candidates.level);
            for (String field : List.of("activeEffects", "attributes")) {
                set(player, net.minecraft.world.entity.LivingEntity.class, field,
                        get(previousPlayer, net.minecraft.world.entity.LivingEntity.class, field));
            }
            set(player, net.minecraft.world.entity.Entity.class, "fluidInteraction",
                    get(previousPlayer, net.minecraft.world.entity.Entity.class, "fluidInteraction"));
            set(player, net.minecraft.world.entity.Entity.class, "onGround", true);
            var connection = alloc(ToolPacketConnection.class);
            connection.player = player;
            connection.packets = f.mining.connection.packets;
            connection.heldAtDestroy = new java.util.ArrayList<>();
            connection.traffic = new java.util.ArrayList<>();
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", connection);
            f.mining.connection = connection;
            f.mining.player = player;
            f.mining.client.player = player;
            f.candidates.level.player = player;
            f.mining.level.player = player;
            set(f.mining.mineHandler, FeatureModuleBase.class, "player", player);
            set(f.mining.client.gameMode, MultiPlayerGameMode.class, "connection", connection);
            set(null, me.aleksilassila.litematica.printer.utils.InventoryUtils.class, "client", f.mining.client);
            var guard = new me.aleksilassila.litematica.printer.utils.InventorySwitchGuard(
                    f.mining.client, () -> f.candidates.level.tick);
            set(RuntimeAccess.get(), PrinterRuntime.class, "inventorySwitchGuard", guard);
            var service = new me.aleksilassila.litematica.printer.interaction.ToolSwitchService(f.mining.client,
                    new me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter(), guard);
            set(RuntimeAccess.get(), PrinterRuntime.class, "toolSwitchService", service);
            set(f.mining.controller, me.aleksilassila.litematica.printer.interaction.MiningInteractionController.class,
                    "toolSwitchService", service);
            f.candidates.setSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
            f.candidates.setSlot(1, availableTool
                    ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE)
                    : net.minecraft.world.item.ItemStack.EMPTY);
            f.candidates.selectSlot(0);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(enabled);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            f.bounds(FIRST, FIRST);
            f.candidates.state(FIRST, Blocks.STONE.defaultBlockState());
            assertTrue(f.mining.mineHandler.canIterationBlockPos(FIRST), "The real creative target passes qualification");
            f.iterate(FIRST);

            f.dispatch();
            for (int tick = 0; tick < 3 && connection.packets.isEmpty(); tick++) {
                f.advanceTick();
                f.continueActive();
                f.dispatch();
            }

            assertEquals(List.of(FIRST), connection.packets.stream().map(packet -> packet.getPos()).toList());
            assertEquals(List.of(Action.START_DESTROY_BLOCK), f.actions(), "Creative dispatch keeps its existing single START sequence");
            assertEquals(List.of(net.minecraft.world.item.Items.STICK),
                    connection.heldAtDestroy, "Creative keeps its original instant hand policy");
            assertEquals(0,
                    me.aleksilassila.litematica.printer.utils.InventoryUtils.getSelectedSlot(player.inventory));
            assertTrue(connection.traffic.stream().noneMatch(event -> event.startsWith("select:")));
        } finally {
            set(null, me.aleksilassila.litematica.printer.utils.InventoryUtils.class, "client", oldInventoryClient);
        }
    }

    @Test void aToolBatchStopsBeforeAnInterveningDifferentToolInsteadOfSkippingItsRank() throws Exception {
        try (var f = new Fixture(true)) {
            BlockPos third = FIRST.east(2);
            f.bounds(FIRST, third);
            for (BlockPos pos : List.of(FIRST, SECOND, third)) f.queue(pos);
            Class<?> targetType = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.MineBreakExecutor$Target");
            Constructor<?> constructor = targetType.getDeclaredConstructor(BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class, float.class, float.class,
                    Direction.class, net.minecraft.world.item.Item.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            Object first = constructor.newInstance(FIRST, Blocks.STONE.defaultBlockState(), 1F, 1F,
                    Direction.DOWN, net.minecraft.world.item.Items.IRON_PICKAXE, false, false);
            Object second = constructor.newInstance(SECOND, Blocks.STONE.defaultBlockState(), 1F, 1F,
                    Direction.DOWN, net.minecraft.world.item.Items.IRON_SHOVEL, false, false);
            Object last = constructor.newInstance(third, Blocks.STONE.defaultBlockState(), 1F, 1F,
                    Direction.DOWN, net.minecraft.world.item.Items.IRON_PICKAXE, false, false);

            invoke(MineHandler.class, f.mining.mineHandler, "executeToolSession",
                    new Class<?>[]{targetType, List.class}, first, List.of(first, second, last));

            assertEquals(List.of(FIRST), f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList());
            assertTrue((long) get(f.mining.mineHandler, FeatureModuleBase.class, "additionalIterationActionNanos") > 0L);
            assertEquals(List.of(SECOND, third), f.candidates.views(f.candidates.refresh()).stream()
                    .map(MineCandidateRefreshFixture.TargetView::pos).toList());
        }
    }

    @Test void aDurabilityBlockedFirstRankCannotPermanentlyHideALaterUsableToolTarget() throws Exception {
        try (var policy = new TweakerooOnlyPolicy(); var f = new Fixture(false)) {
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(4);
            var wornPick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE);
            wornPick.setDamageValue(wornPick.getMaxDamage() - 1);
            var shovel = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SHOVEL);
            f.candidates.setSlot(0, wornPick);
            f.candidates.setSlot(1, net.minecraft.world.item.ItemStack.EMPTY);
            f.candidates.setSlot(2, shovel);
            f.candidates.selectSlot(0);
            f.bounds(FIRST, SECOND);
            f.queue(FIRST);
            f.queue(SECOND);
            f.candidates.state(SECOND, Blocks.DIRT.defaultBlockState());
            try (var bindings = new DurabilityGuardBindings(pos -> {
                if (SECOND.equals(pos)) f.candidates.selectSlot(2);
            })) {
                var adapter = new me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter();
                assertTrue(adapter.isDurabilityGuardActive());
                assertFalse(adapter.isCurrentToolUsable(wornPick));
                assertTrue(adapter.isCurrentToolUsable(shovel));
                f.candidates.beginTick();
                f.callToolSession("beginTick");

                f.dispatch();

                assertEquals(List.of(SECOND), f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList(),
                        "A guarded first rank must keep its retry while the later safe target can make progress");
                assertTrue(f.candidates.views(f.candidates.refresh()).stream()
                        .anyMatch(target -> FIRST.equals(target.pos())), "The blocked first rank must not be discarded");
                assertTrue(bindings.switches.contains(FIRST), "The first target really tried and failed protection");
                assertTrue(bindings.switches.contains(SECOND), "The later target prepares its own safe tool");
                assertSame(shovel, f.mining.player.getMainHandItem());
                assertEquals(wornPick.getMaxDamage() - 1, wornPick.getDamageValue(), "No guarded tool is damaged");
            }
        }
    }

    @Test void relaxingMineRestrictionsWakesACompletedSourceWithoutMovementOrWorldEvents() throws Exception {
        try (var f = new Fixture(false); var schematic = new HeadlessSchematicBinding()) {
            set(f.mining.player, net.minecraft.world.entity.Entity.class, "blockPosition", FIRST);
            f.bounds(FIRST, FIRST);
            f.candidates.state(FIRST, Blocks.STONE.defaultBlockState());
            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(fi.dy.masa.malilib.util.restrictions.UsageRestriction.ListType.BLACKLIST);
            Configs.Mine.EXCAVATE_BLACKLIST.setStrings(List.of("minecraft:stone"));
            var cache = new me.aleksilassila.litematica.printer.handler.scan.ScanCache();
            var budget = get(cache, cache.getClass(), "budget");
            set(budget, budget.getClass(), "nanoClock", (java.util.function.LongSupplier) () -> 0L);
            var scan = new me.aleksilassila.litematica.printer.handler.scan.ScanEngine(
                    () -> me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                    () -> f.candidates.level, cache);
            set(f.mining.mineHandler, FeatureModuleBase.class, "scanEngine", scan);
            var hostType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanHost");
            var hostConstructor = hostType.getDeclaredConstructor(FeatureModuleBase.class);
            hostConstructor.setAccessible(true);
            var coordinatorType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanCoordinator");
            var coordinatorConstructor = coordinatorType.getDeclaredConstructor(
                    Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanCoordinator$Host"),
                    AtomicReference.class);
            coordinatorConstructor.setAccessible(true);
            set(f.mining.mineHandler, FeatureModuleBase.class, "scanCoordinator",
                    coordinatorConstructor.newInstance(hostConstructor.newInstance(f.mining.mineHandler), null));
            f.candidates.level.tick = 1;
            scan.beginTick(f.candidates.level, null, 1);
            invoke(MineHandler.class, f.mining.mineHandler, "preprocess", new Class<?>[0]);
            @SuppressWarnings("unchecked")
            var first = (Iterable<BlockPos>) invoke(MineHandler.class, f.mining.mineHandler, "getMineScanPositions",
                    new Class<?>[]{PrinterBox.class}, new PrinterBox(FIRST));
            assertFalse(first.iterator().hasNext());
            assertEquals(me.aleksilassila.litematica.printer.handler.scan.ScanAvailability.COMPLETE,
                    ((me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable) first).availability());
            long dirty = scan.dirtyVersion();

            Configs.Mine.EXCAVATE_LIMIT.setOptionListValue(fi.dy.masa.malilib.util.restrictions.UsageRestriction.ListType.NONE);
            f.candidates.level.tick = 2;
            scan.beginTick(f.candidates.level, null, 2);
            invoke(MineHandler.class, f.mining.mineHandler, "preprocess", new Class<?>[0]);
            @SuppressWarnings("unchecked")
            var next = (Iterable<BlockPos>) invoke(MineHandler.class, f.mining.mineHandler, "getMineScanPositions",
                    new Class<?>[]{PrinterBox.class}, new PrinterBox(FIRST));
            var positions = new java.util.ArrayList<BlockPos>();
            next.forEach(positions::add);

            assertEquals(List.of(FIRST), positions, "A configuration gain must wake eligibility before staggered repair");
            assertEquals(dirty, scan.dirtyVersion(), "Eligibility change does not invalidate shared world facts");
        }
    }

    @Test void repeatedBlockedPreparationIsTriedOncePerConditionBeforeALaterSafeTarget() throws Exception {
        try (var policy = new TweakerooOnlyPolicy(); var f = new Fixture(false)) {
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(4);
            var wornPick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE);
            wornPick.setDamageValue(wornPick.getMaxDamage() - 1);
            var shovel = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SHOVEL);
            f.candidates.setSlot(0, wornPick);
            f.candidates.setSlot(1, net.minecraft.world.item.ItemStack.EMPTY);
            f.candidates.setSlot(2, shovel);
            f.candidates.selectSlot(0);
            BlockPos safe = FIRST.east(2);
            f.bounds(FIRST, safe);
            for (BlockPos pos : List.of(FIRST, SECOND, safe)) f.queue(pos);
            f.candidates.state(safe, Blocks.DIRT.defaultBlockState());
            try (var bindings = new DurabilityGuardBindings(pos -> {
                if (safe.equals(pos)) f.candidates.selectSlot(2);
            })) {
                f.candidates.beginTick();
                f.callToolSession("beginTick");

                f.dispatch();

                assertEquals(List.of(safe), f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList());
                assertEquals(List.of(FIRST, safe), bindings.switches,
                        "Identical failed state/tool/held conditions must not repeat an expensive optional-mod search");
                assertTrue(f.candidates.views(f.candidates.refresh()).stream().anyMatch(target -> FIRST.equals(target.pos())));
                assertTrue(f.candidates.views(f.candidates.refresh()).stream().anyMatch(target -> SECOND.equals(target.pos())));
                assertEquals(wornPick.getMaxDamage() - 1, wornPick.getDamageValue());
            }
        }
    }

    @Test void anEntireDurabilityBlockedBatchYieldsToLaterSourceWorkAndReentersOnMaterialWakeup() throws Exception {
        try (var policy = new TweakerooOnlyPolicy(); var f = new Fixture(false); var schematic = new HeadlessSchematicBinding()) {
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(4);
            var wornPick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WOODEN_PICKAXE);
            wornPick.setDamageValue(wornPick.getMaxDamage() - 1);
            f.candidates.setSlot(0, wornPick);
            f.candidates.setSlot(1, net.minecraft.world.item.ItemStack.EMPTY);
            f.candidates.setSlot(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SHOVEL));
            f.candidates.selectSlot(0);
            f.bounds(FIRST, SECOND);
            f.candidates.state(FIRST, Blocks.STONE.defaultBlockState());
            f.candidates.state(SECOND, Blocks.DIRT.defaultBlockState());
            var scan = f.installScan();
            PrinterBox box = new PrinterBox(FIRST, SECOND);
            try (var bindings = new DurabilityGuardBindings(pos -> {
                if (SECOND.equals(pos)) f.candidates.selectSlot(2);
            })) {
                f.candidates.level.tick = 1;
                scan.beginTick(f.candidates.level, null, 1);
                invoke(MineHandler.class, f.mining.mineHandler, "preprocess", new Class<?>[0]);
                @SuppressWarnings("unchecked")
                var firstSlice = (Iterable<BlockPos>) invoke(MineHandler.class, f.mining.mineHandler, "getIterationPositions",
                        new Class<?>[]{PrinterBox.class}, box);
                assertEquals(FIRST, firstSlice.iterator().next());
                // End this source slice after its first offer, leaving safe dirt undiscovered.
                f.iterate(FIRST);
                f.dispatch();
                assertTrue(f.mining.connection.packets.isEmpty(), "The first offered target really fails durability protection");
                assertEquals(List.of(FIRST), bindings.switches);
                long dirty = scan.dirtyVersion();

                f.advanceTick();
                scan.beginTick(f.candidates.level, null, 2);
                invoke(MineHandler.class, f.mining.mineHandler, "preprocess", new Class<?>[0]);
                @SuppressWarnings("unchecked")
                var laterSlice = (Iterable<BlockPos>) invoke(MineHandler.class, f.mining.mineHandler, "getIterationPositions",
                        new Class<?>[]{PrinterBox.class}, box);
                var discovered = new java.util.ArrayList<BlockPos>();
                laterSlice.forEach(discovered::add);
                for (BlockPos pos : discovered) f.iterate(pos);
                f.dispatch();

                assertEquals(List.of(SECOND), f.mining.connection.packets.stream().map(packet -> packet.getPos()).toList(),
                        "A wholly blocked batch must yield to a later source slice without restarting the source prefix");
                assertEquals(wornPick.getMaxDamage() - 1, wornPick.getDamageValue());
                invoke(MineHandler.class, f.mining.mineHandler, "onInventoryAvailabilityChanged", new Class<?>[0]);
                @SuppressWarnings("unchecked")
                var woken = (Iterable<BlockPos>) invoke(MineHandler.class, f.mining.mineHandler, "getMineScanPositions",
                        new Class<?>[]{PrinterBox.class}, box);
                var recovered = new java.util.ArrayList<BlockPos>();
                woken.forEach(recovered::add);
                assertTrue(recovered.contains(FIRST), "Yielding action ownership cannot mark the blocked coordinate complete");
                assertEquals(dirty, scan.dirtyVersion(), "Inventory wakeup reuses the retained positive world facts");
            }
        }
    }

    /** These three regressions exercise Tweakeroo's own callbacks and virtual inventory policy. */
    private static final class TweakerooOnlyPolicy implements AutoCloseable {
        private final int previous = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();

        TweakerooOnlyPolicy() { Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(0); }

        @Override public void close() { Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previous); }
    }

    static final class HeadlessSchematicBinding implements AutoCloseable {
        private final java.lang.reflect.Field field = fi.dy.masa.litematica.world.SchematicWorldHandler.class.getDeclaredField("world");
        private final Object previous;

        HeadlessSchematicBinding() throws Exception {
            field.setAccessible(true);
            previous = field.get(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE);
            var world = alloc(fi.dy.masa.litematica.world.WorldSchematic.class);
            var manager = alloc(fi.dy.masa.litematica.world.ChunkManagerSchematic.class);
            set(manager, fi.dy.masa.litematica.world.ChunkManagerSchematic.class, "loadedChunks",
                    new java.util.concurrent.ConcurrentHashMap<>());
            set(world, fi.dy.masa.litematica.world.WorldSchematic.class, "chunkManagerSchematic", manager);
            set(world, fi.dy.masa.litematica.world.WorldSchematic.class, "mc", alloc(net.minecraft.client.Minecraft.class));
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, world);
        }

        @Override public void close() throws Exception {
            field.set(fi.dy.masa.litematica.world.SchematicWorldHandler.INSTANCE, previous);
        }
    }

    private static final class InventoryBackedMinePlayer extends MiningInteractionRegressionTest.Player {
        @Override public net.minecraft.world.item.ItemStack getMainHandItem() {
            return this.inventory.getItem(me.aleksilassila.litematica.printer.utils.InventoryUtils.getSelectedSlot(this.inventory));
        }
    }

    private static final class ToolPacketConnection extends MiningInteractionRegressionTest.Connection {
        InventoryBackedMinePlayer player;
        List<net.minecraft.world.item.Item> heldAtDestroy;
        List<String> traffic;
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
            if (packet instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket carried) {
                this.traffic.add("select:" + carried.getSlot());
            } else if (packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket destroy) {
                this.traffic.add("destroy:" + destroy.getAction());
                this.heldAtDestroy.add(this.player.getMainHandItem().getItem());
            }
            super.send(packet);
        }
    }

    /** Real adapter/durability checks with only optional-mod reflection endpoints simulated. */
    private static final class DurabilityGuardBindings implements AutoCloseable {
        private static java.util.function.Consumer<BlockPos> switchAction;
        final List<BlockPos> switches = new java.util.ArrayList<>();
        final Object loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        final Object previousModMap;
        final java.util.Map<String, Object> previous = new java.util.LinkedHashMap<>();
        final java.util.function.Consumer<BlockPos> previousSwitchAction = switchAction;

        @SuppressWarnings("unchecked")
        DurabilityGuardBindings(java.util.function.Consumer<BlockPos> prepare) throws Exception {
            // Initialize the optional binding holder before publishing the fake loaded-mod entry.
            var utils = me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils.class;
            me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils.isSwapAlmostBrokenToolsEnabled();
            previousModMap = get(loader, loader.getClass(), "modMap");
            var mods = new java.util.HashMap<>((java.util.Map<String, Object>) previousModMap);
            mods.put("tweakeroo", null);
            set(loader, loader.getClass(), "modMap", mods);
            for (String field : List.of("tweakToolSwitchEnum", "tweakSwapAlmostBrokenToolsEnum", "getBooleanValueMethod",
                    "trySwitchToEffectiveToolMethod", "trySwapCurrentToolIfNearlyBrokenMethod")) {
                previous.put(field, get(null, utils, field));
            }
            var enabled = new fi.dy.masa.malilib.config.options.ConfigBoolean("testDurability", true);
            set(null, utils, "tweakToolSwitchEnum", enabled);
            set(null, utils, "tweakSwapAlmostBrokenToolsEnum", enabled);
            set(null, utils, "getBooleanValueMethod", enabled.getClass().getMethod("getBooleanValue"));
            var effective = DurabilityGuardBindings.class.getDeclaredMethod("switchEffective", BlockPos.class);
            effective.setAccessible(true);
            var swap = DurabilityGuardBindings.class.getDeclaredMethod("swapNearlyBroken");
            swap.setAccessible(true);
            set(null, utils, "trySwitchToEffectiveToolMethod", effective);
            set(null, utils, "trySwapCurrentToolIfNearlyBrokenMethod", swap);
            switchAction = pos -> { this.switches.add(pos); prepare.accept(pos); };
        }

        public static void switchEffective(BlockPos pos) { switchAction.accept(pos); }
        public static void swapNearlyBroken() { }

        @Override public void close() throws Exception {
            for (var entry : previous.entrySet()) {
                set(null, me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils.class, entry.getKey(), entry.getValue());
            }
            set(loader, loader.getClass(), "modMap", previousModMap);
            switchAction = previousSwitchAction;
        }
    }

    static final class Fixture implements AutoCloseable {
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
            this.installScan().beginTick(candidates.level, null, candidates.level.tick);
        }

        void bounds(BlockPos first, BlockPos second) { box.setPos1(first); box.setPos2(second); }

        me.aleksilassila.litematica.printer.handler.scan.ScanEngine installScan() throws Exception {
            set(mining.player, net.minecraft.world.entity.Entity.class, "blockPosition", FIRST);
            var cache = new me.aleksilassila.litematica.printer.handler.scan.ScanCache();
            var budget = get(cache, cache.getClass(), "budget");
            set(budget, budget.getClass(), "nanoClock", (java.util.function.LongSupplier) () -> 0L);
            var scan = new me.aleksilassila.litematica.printer.handler.scan.ScanEngine(
                    () -> me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                    () -> candidates.level, cache);
            set(mining.mineHandler, FeatureModuleBase.class, "scanEngine", scan);
            var hostType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanHost");
            var hostConstructor = hostType.getDeclaredConstructor(FeatureModuleBase.class);
            hostConstructor.setAccessible(true);
            var coordinatorType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanCoordinator");
            var coordinatorConstructor = coordinatorType.getDeclaredConstructor(
                    Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanCoordinator$Host"), AtomicReference.class);
            coordinatorConstructor.setAccessible(true);
            set(mining.mineHandler, FeatureModuleBase.class, "scanCoordinator",
                    coordinatorConstructor.newInstance(hostConstructor.newInstance(mining.mineHandler), null));
            return scan;
        }

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
        void advanceTick() throws Exception {
            candidates.level.tick++;
            set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", candidates.level.tick);
            mining.controller.tick();
        }
        void dispatch() throws Exception { invoke(MineHandler.class, mining.mineHandler, "stopIteration", new Class<?>[]{boolean.class}, false); }
        void continueActive() throws Exception { invoke(MineHandler.class, mining.mineHandler, "continueActiveMineTarget", new Class<?>[0]); }
        void refreshTrench() throws Exception { invoke(MineHandler.class, mining.mineHandler, "refreshTrenchFillTargets", new Class<?>[0]); }

        List<Action> actions() { return mining.connection.packets.stream().map(packet -> packet.getAction()).toList(); }

        @SuppressWarnings("unchecked")
        List<BlockPos> readyFillTargets() throws Exception {
            Object state = get(mining.mineHandler, MineHandler.class, "trenchFill");
            return (List<BlockPos>) invoke(state.getClass(), state, "readyFillTargets",
                    new Class<?>[]{long.class, Predicate.class, Predicate.class}, candidates.level.tick,
                    (Predicate<BlockPos>) pos -> candidates.level.getBlockState(pos).getBlock() instanceof LiquidBlock,
                    (Predicate<BlockPos>) pos -> true);
        }

        @SuppressWarnings("unchecked")
        List<BlockPos> readyWaterloggedTargets() throws Exception {
            Object state = get(mining.mineHandler, MineHandler.class, "trenchFill");
            return (List<BlockPos>) invoke(state.getClass(), state, "readyWaterloggedTargets",
                    new Class<?>[]{Predicate.class}, (Predicate<BlockPos>) pos -> true);
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
