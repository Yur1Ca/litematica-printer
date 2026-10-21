package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real placer; only the client world and outbound game-mode boundary are replaced. */
class BedrockPlacementRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //#if MC >= 260100
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                //#if MC >= 260300
                //$$ .build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup()).forEach(p -> p.apply());
                //#else
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(p -> p.apply());
                //#endif
        //#endif
    }

    @ParameterizedTest @EnumSource(Direction.class)
    void reversePlacementStillTargetsBaseAfterOldHeadDisappears(Direction primingFacing) throws Exception {
        try (var f = new Fixture()) {
            BlockPos piston = TARGET.relative(primingFacing);
            BlockPos head = piston.relative(primingFacing);
            f.machine(primingFacing);
            assertTrue(f.place(piston, primingFacing.getOpposite()));
            assertEquals(1, f.mode.hits.size());
            BlockHitResult hit = f.mode.hits.get(0);
            assertEquals(TARGET, hit.getBlockPos(), "Click the persistent bedrock, not the disappearing head");

            // The server has processed the earlier break packets, while the client sent
            // this hit using the old extended-piston snapshot.
            f.level.blocks.remove(piston);
            f.level.blocks.remove(head);
            assertEquals(piston, f.serverPlacementPosition(hit));
            assertTrue(f.mode.predictions.stream().noneMatch(Boolean::booleanValue));
        }
    }

    @Test void missingStableAnchorDoesNotFallBackToTheOldHead() throws Exception {
        try (var f = new Fixture()) {
            f.machine(Direction.UP);
            f.level.blocks.remove(TARGET);
            assertFalse(f.place(TARGET.above(), Direction.DOWN));
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void ordinaryInitialPistonPlacementKeepsItsSupport() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.place(TARGET.above(), Direction.UP));
            assertEquals(TARGET.above(), f.serverPlacementPosition(f.mode.hits.get(0)));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void oldPistonDoesNotCancelDelayedReversePlacement(boolean extended) throws Exception {
        try (var f = new Fixture()) {
            f.machine(Direction.UP);
            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, extended));
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            for (int tick = 1; tick < 8; tick++) {
                f.tick(tick);
                f.placer.tickPistonRetries();
            }
            assertTrue(f.mode.hits.isEmpty(), "Keep the RTT delay without cancelling on the old piston");

            // Deliver the delayed server removal, then reach the existing retry deadline.
            f.level.blocks.remove(TARGET.above());
            f.level.blocks.remove(TARGET.above(2));
            f.tick(8);
            f.placer.tickPistonRetries();
            assertEquals(1, f.mode.hits.size(), "The pending reverse placement must still be retried");
            assertEquals(TARGET.above(), f.serverPlacementPosition(f.mode.hits.get(0)));

            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN));
            f.tick(9);
            f.placer.tickPistonRetries();
            f.level.blocks.remove(TARGET.above());
            f.tick(16);
            f.placer.tickPistonRetries();
            assertEquals(1, f.mode.hits.size(), "A confirmed reverse piston completes the retry");
        }
    }

    @Test void matchingPistonCompletesBeforeTheRetryDeadline() throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN));
            f.tick(1);
            f.placer.tickPistonRetries();
            f.level.blocks.remove(TARGET.above());
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty(), "No fixed wait after the expected server state is observed");
        }
    }

    @Test void unchangedWrongFacingStillHasABoundedRetryBudget() throws Exception {
        try (var f = new Fixture()) {
            f.machine(Direction.UP);
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            for (int tick = 1; tick <= 48; tick++) {
                f.tick(tick);
                f.placer.tickPistonRetries();
            }
            assertEquals(4, f.mode.hits.size());
        }
    }

    @Test void completedTargetCancelsThePendingPlacement() throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.level.blocks.remove(TARGET);
            f.tick(1);
            f.placer.tickPistonRetries();
            f.level.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void cancelledTargetDoesNotLeaveALatePlacement() throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.placer.cancelPistonRetry(TARGET.above());
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void resetClearsInFlightRetriesBeforeAnotherWorldUsesTheSamePosition() throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            // BedrockEngine.reset uses this when the feature/world lifecycle ends.
            f.placer.clearHorizontalLookState();
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void disconnectedWorldClearsPendingRetries() throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.client.level = null;
            f.placer.tickPistonRetries();
            f.client.level = f.level;
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void restartingAnAttemptCancelsItsOldDelayedPlacement() throws Exception {
        try (var f = new Fixture()) {
            BedrockTarget target = f.target();
            target.setHasTried(true);
            target.setExecuteTick(0);
            var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
            state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            for (int tick = 0; tick < 4; tick++) state.beginTick(true);
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);

            // The existing collapse recovery can start a new attempt before an RTT-delayed retry.
            assertEquals(BedrockTarget.Status.UNINITIALIZED, new BedrockTargetStatusResolver(target).resolve(true));
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty(), "The previous attempt must not place into the rebuilt machine");

            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.tick(16);
            f.placer.tickPistonRetries();
            assertEquals(1, f.mode.hits.size(), "A later attempt can still schedule its own retry");
        }
    }

    @Test void resettingOneTargetPreservesAnotherTargetsRetry() throws Exception {
        try (var f = new Fixture()) {
            BlockPos other = TARGET.east(2);
            f.level.blocks.put(other, Blocks.BEDROCK.defaultBlockState());
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.placer.schedulePistonRetry(other, other.above(), Direction.DOWN);
            f.target().resetPostExecuteAttempt(BedrockTarget.Status.UNINITIALIZED);
            f.tick(8);
            f.placer.tickPistonRetries();
            assertEquals(1, f.mode.hits.size());
            assertEquals(other.above(), f.serverPlacementPosition(f.mode.hits.get(0)));
        }
    }

    @Test void completedBedrockDoesNotWaitForTheConfirmationTimeout() throws Exception {
        try (var f = new Fixture()) {
            BedrockTarget target = f.target();
            assertEquals(56, target.postExecuteSyncTimeoutTicks());
            target.setHasTried(true);
            target.setExecuteTick(0);
            f.level.blocks.remove(TARGET);
            assertEquals(BedrockTarget.Status.RETRACTED, target.refreshStatusOnly());
        }
    }

    @Test void restartingHorizontalAttemptReleasesItsPendingRetryLook() throws Exception {
        try (var f = new Fixture()) {
            BedrockTarget target = f.target(TARGET, Direction.EAST, f.placer);
            f.placer.schedulePistonRetry(TARGET, target.getPistonPos(), Direction.WEST);
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(target.hasPendingHorizontalLook());
            assertNotNull(NetworkUtils.getScopedLookOverride());

            target.resetPostExecuteAttempt(BedrockTarget.Status.UNINITIALIZED);
            assertFalse(target.hasPendingHorizontalLook(), "The old retry's look must not survive its attempt");
            assertNull(NetworkUtils.getScopedLookOverride());
            f.tick(16);
            f.placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void cancellingVerticalAttemptPreservesAnotherTargetsHorizontalLook() throws Exception {
        try (var f = new Fixture()) {
            BlockPos sidePiston = TARGET.east(3);
            f.placer.preparePistonPlacementLook(sidePiston, Direction.WEST);
            var look = NetworkUtils.getScopedLookOverride();
            assertNotNull(look);
            f.placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);

            f.target().resetPostExecuteAttempt(BedrockTarget.Status.UNINITIALIZED);
            assertTrue(f.placer.hasPendingHorizontalLook(sidePiston));
            assertSame(look, NetworkUtils.getScopedLookOverride());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void confirmedHorizontalRetryReleasesItsPendingLook(boolean targetCompleted) throws Exception {
        try (var f = new Fixture()) {
            f.placer.schedulePistonRetry(TARGET, TARGET.east(), Direction.WEST);
            f.tick(8);
            f.placer.tickPistonRetries();
            assertTrue(f.placer.hasPendingHorizontalLook(TARGET.east()));
            if (targetCompleted) f.level.blocks.remove(TARGET);
            else f.level.blocks.put(TARGET.east(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));

            f.tick(9);
            f.placer.tickPistonRetries();
            assertFalse(f.placer.hasPendingHorizontalLook(TARGET.east()));
            assertNull(NetworkUtils.getScopedLookOverride());
            assertTrue(f.mode.hits.isEmpty(), "The delayed confirmation needs no further placement");
        }
    }

    @Test void retiringHorizontalTargetPreservesAnotherTargetsDelayedRetry() throws Exception {
        try (var f = new Fixture()) {
            var targets = new BedrockTargetRegistry();
            BedrockTarget side = f.target(TARGET, Direction.EAST, f.placer);
            targets.add(side);
            f.placer.preparePistonPlacementLook(side.getPistonPos(), Direction.WEST);
            BlockPos other = TARGET.west(3);
            f.level.blocks.put(other, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(other.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            f.placer.schedulePistonRetry(other, other.above(), Direction.DOWN);
            f.tick(1);
            f.placer.tickPistonRetries();
            f.level.blocks.remove(TARGET);
            var executor = new BedrockTargetExecutor(targets,
                    new BedrockCleanupCoordinator(f.client, new CooldownUtils()), new BedrockRunStats(),
                    (pos, ticks) -> fail("Completed targets do not need a cooldown"),
                    f.placer, new BedrockNetworkSync(f.runtime::currentTick, () -> 8));

            executor.process(f.level, 0, false, new LinkedHashSet<>(), side);
            assertTrue(targets.isEmpty());
            assertFalse(side.hasPendingHorizontalLook());
            assertNull(NetworkUtils.getScopedLookOverride());
            f.level.blocks.remove(other.above());
            f.tick(8);
            f.placer.tickPistonRetries();
            assertEquals(1, f.mode.hits.size(), "Retiring one machine must not clear another machine's retry");
            assertEquals(other.above(), f.serverPlacementPosition(f.mode.hits.get(0)));
        }
    }

    @ParameterizedTest @ValueSource(ints = {4, 8})
    void leavingSelectionCancelsRetryEvenOnItsDeadline(int removalTick) throws Exception {
        try (var f = new Fixture()) {
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            var targets = (BedrockTargetRegistry) get(engine, BedrockEngine.class, "targets");
            targets.add(f.target(TARGET, Direction.UP, placer));
            f.machine(Direction.UP);
            placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            f.tick(1);
            placer.tickPistonRetries();
            f.level.blocks.remove(TARGET.above());
            f.level.blocks.remove(TARGET.above(2));
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(TARGET));

            f.tick(removalTick);
            engine.tick();
            assertTrue(targets.isEmpty());
            f.tick(16);
            engine.tick();
            assertTrue(f.mode.hits.isEmpty(), "A target removed from the selection must not dispatch a late placement");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work_switch_disabled", "execution_scope_changed", "client_scope_changed", "disconnect"})
    void runtimeResetCancelsDelayedPlacementAndLookBeforePositionReuse(String reason) throws Exception {
        try (var f = new Fixture()) {
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);
            placer.schedulePistonRetry(TARGET, TARGET.east(), Direction.WEST);
            placer.preparePistonPlacementLook(TARGET.east(), Direction.WEST);
            assertNotNull(NetworkUtils.getScopedLookOverride());

            f.runtime.reset(reason);
            assertFalse(placer.hasPendingHorizontalLook(TARGET.east()));
            assertNull(NetworkUtils.getScopedLookOverride());
            var nextLevel = f.allocate(TestLevel.class);
            nextLevel.blocks = new HashMap<>();
            nextLevel.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            f.client.level = nextLevel;
            set(f.client.player, LocalPlayer.class, "connection", f.allocate(TestConnection.class));
            f.tick(80);
            placer.tickPistonRetries();
            assertTrue(f.mode.hits.isEmpty(), "No old-world placement may reach the next connection");
        }
    }

    static class TestLevel extends ClientLevel {
        Map<BlockPos, BlockState> blocks;
        //#if MC >= 12102
        TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        //#else
        //$$ TestLevel() { super(null, null, null, null, 0, 0, null, null, false, 0L); }
        //#endif
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public long getGameTime() { return 0L; }
        //#if MC >= 12102
        @Override public int getMinY() { return -64; }
        //#else
        //$$ @Override public int getMinBuildHeight() { return -64; }
        //#endif
        @Override public int getHeight() { return 384; }
    }

    static class TestPlayer extends LocalPlayer {
        Inventory inventory;
        //#if MC >= 260300
        //$$ TestPlayer() { super(null, null, null, null, null, null, false, null, null); }
        //#elseif MC >= 260100
        TestPlayer() { super(null, null, null, null, null, null, false, null); }
        //#elseif MC >= 12106
        //$$ TestPlayer() { super(null, null, null, null, null, null, false); }
        //#else
        //$$ TestPlayer() { super(null, null, null, null, null, false, false); }
        //#endif
        @Override public Inventory getInventory() { return inventory; }
        @Override public boolean isShiftKeyDown() { return false; }
    }

    static class TestConnection extends ClientPacketListener {
        //#if MC >= 12002
        TestConnection() { super(null, null, null); }
        //#elseif MC >= 11904
        //$$ TestConnection() { super(null, null, null, null, null, null); }
        //#else
        //$$ TestConnection() { super(null, null, null, null, null); }
        //#endif
        @Override public void send(Packet<?> packet) { }
    }

    static class TestGameMode extends MultiPlayerGameMode implements MultiPlayerGameModeExtension {
        List<BlockHitResult> hits;
        List<Boolean> predictions;
        TestGameMode() { super(null, null); }
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertEquals(InteractionHand.OFF_HAND, hand);
            hits.add(hit);
            predictions.add(prediction);
            return InteractionResult.PASS;
        }
        @Override public BlockBreakResult litematica_printer$continueDestroyBlock(boolean prediction, BlockPos pos,
                Direction direction, boolean delayed, boolean switchTool) { throw new AssertionError("Unexpected mining"); }
    }

    @SuppressWarnings({"removal", "deprecation"})
    static class Fixture implements AutoCloseable {
        final sun.misc.Unsafe unsafe;
        final Object oldClient = get(null, Minecraft.class, "instance");
        final Object oldRuntime = get(null, RuntimeAccess.class, "current");
        final boolean oldRange = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        final boolean oldAdaptive = Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.getBooleanValue();
        final Map<Field, Object> cachedClients = new LinkedHashMap<>();
        final Minecraft client;
        final TestLevel level;
        final TestGameMode mode;
        final PrinterRuntime runtime;
        final BedrockPlacer placer;

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) get(null, sun.misc.Unsafe.class, "theUnsafe");
            client = allocate(Minecraft.class);
            level = allocate(TestLevel.class);
            set(level, Level.class, "dimension", Level.OVERWORLD);
            level.blocks = new HashMap<>();
            level.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            var player = allocate(TestPlayer.class);
            //#if MC >= 12105
            player.inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            //#else
            //$$ player.inventory = new Inventory(player);
            //#endif
            player.inventory.setItem(40, new ItemStack(Items.PISTON, 16));
            set(player, Entity.class, "position", new Vec3(0.5, 64, 0.5));
            set(player, Entity.class, "eyeHeight", 1.5F);
            set(player, LocalPlayer.class, "connection", allocate(TestConnection.class));
            mode = allocate(TestGameMode.class);
            mode.hits = new ArrayList<>();
            mode.predictions = new ArrayList<>();
            client.level = level;
            client.player = player;
            client.gameMode = mode;
            for (var type : List.of(BedrockInventory.class, InventoryUtils.class, InteractionUtils.class,
                    ConfigUtils.class, PlayerUtils.class, NetworkUtils.class)) {
                Class.forName(type.getName(), true, type.getClassLoader());
                for (var field : type.getDeclaredFields()) {
                    if (field.getType() != Minecraft.class) continue;
                    field.setAccessible(true);
                    cachedClients.put(field, field.get(null));
                    unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
                }
            }
            set(null, Minecraft.class, "instance", client);
            runtime = allocate(PrinterRuntime.class);
            set(runtime, PrinterRuntime.class, "interactionUtils", new InteractionUtils());
            set(runtime, PrinterRuntime.class, "hudStats", new HudStatsManager(client, runtime::currentTick, null));
            set(runtime, PrinterRuntime.class, "scope", new RuntimeScope());
            set(runtime, PrinterRuntime.class, "events", new RuntimeEventBus());
            set(runtime, PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL);
            RuntimeAccess.install(runtime);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
            Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.setBooleanValue(true);
            placer = new BedrockPlacer(client, new BedrockNetworkSync(runtime::currentTick, () -> 8));
        }

        <T> T allocate(Class<T> type) throws Exception { return type.cast(unsafe.allocateInstance(type)); }
        void tick(long tick) throws Exception { set(runtime, PrinterRuntime.class, "currentTick", tick); }
        void machine(Direction facing) {
            BlockPos piston = TARGET.relative(facing);
            level.blocks.put(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing)
                    .setValue(PistonBaseBlock.EXTENDED, true));
            level.blocks.put(piston.relative(facing), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, facing));
        }
        boolean place(BlockPos piston, Direction facing) throws Exception {
            placer.preparePistonPlacementLook(piston, facing);
            tick(runtime.currentTick() + 1);
            return placer.placePiston(piston, facing);
        }
        BedrockTarget target() throws Exception {
            return target(TARGET, Direction.UP, placer);
        }
        BedrockTarget target(BlockPos pos, Direction facing, BedrockPlacer targetPlacer) throws Exception {
            var ctor = BedrockMachineLayout.class.getDeclaredConstructor(BlockPos.class, Direction.class);
            ctor.setAccessible(true);
            return new BedrockTarget(pos, level, ctor.newInstance(pos, facing),
                    new BedrockTorchPlacement(pos.east(), Direction.UP), null,
                    new BedrockCriticalExecutor(targetPlacer), targetPlacer, new BedrockNetworkSync(runtime::currentTick, () -> 40));
        }
        BedrockEngine engine() throws Exception {
            var engine = new BedrockEngine(client, new CooldownUtils(), runtime::currentTick, new LitematicaAdapter());
            var sync = get(engine, BedrockEngine.class, "networkSync");
            set(sync, BedrockNetworkSync.class, "rttExtraTicks", (java.util.function.IntSupplier) () -> 8);
            set(runtime, PrinterRuntime.class, "bedrockEngine", engine);
            runtime.register(engine);
            return engine;
        }
        BlockPos serverPlacementPosition(BlockHitResult hit) throws Exception {
            // Protected in older versions; exercise vanilla's replacement/adjacent-position decision.
            var ctor = BlockPlaceContext.class.getDeclaredConstructor(Level.class, Player.class,
                    InteractionHand.class, ItemStack.class, BlockHitResult.class);
            ctor.setAccessible(true);
            return ctor.newInstance(level, client.player, InteractionHand.OFF_HAND,
                    new ItemStack(Items.PISTON), hit).getClickedPos();
        }
        @Override public void close() throws Exception {
            NetworkUtils.clearScopedLookOverride();
            for (var entry : cachedClients.entrySet()) {
                Field field = entry.getKey();
                unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), entry.getValue());
            }
            set(null, Minecraft.class, "instance", oldClient);
            set(null, RuntimeAccess.class, "current", oldRuntime);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(oldRange);
            Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.setBooleanValue(oldAdaptive);
        }
    }

    static Object get(Object obj, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(obj);
    }
    static void set(Object obj, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(obj, value);
    }
}
