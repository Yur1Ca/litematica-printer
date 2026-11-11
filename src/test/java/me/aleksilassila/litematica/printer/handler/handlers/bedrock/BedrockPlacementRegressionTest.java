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
import me.aleksilassila.litematica.printer.utils.minecraft.PacketBatchConnection;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real placer; only the client world and outbound game-mode boundary are replaced. */
class BedrockPlacementRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                //#if MC >= 260300
                //$$ .build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup()).forEach(p -> p.apply());
                //#else
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(p -> p.apply());
                //#endif
    }

    @ParameterizedTest @EnumSource(Direction.class)
    void reversePlacementStillTargetsBaseAfterOldHeadDisappears(Direction primingFacing) throws Exception {
        try (var f = new Fixture()) {
            BlockPos piston = TARGET.relative(primingFacing);
            BlockPos head = piston.relative(primingFacing);
            f.machine(primingFacing);
            f.synchronizeInventory();
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
            f.synchronizeInventory();
            assertFalse(f.place(TARGET.above(), Direction.DOWN));
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void ordinaryInitialPistonPlacementKeepsItsSupport() throws Exception {
        try (var f = new Fixture()) {
            f.synchronizeInventory();
            assertTrue(f.place(TARGET.above(), Direction.UP));
            assertEquals(TARGET.above(), f.serverPlacementPosition(f.mode.hits.get(0)));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialInitializeUpdatesDoNotRebuildBeforeTheCurrentAttemptSettles(boolean torchReceived) throws Exception {
        try (var f = new Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            if (torchReceived) f.level.blocks.put(TARGET.east().above(), Blocks.REDSTONE_TORCH.defaultBlockState());
            var target = f.target();
            var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
            // A rebuilt target can already be old when a fresh initialization is sent.
            state.beginTick(0);
            state.beginTick(100);
            f.tick(100);
            target.setInitializeTick(state.tickTimes());
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            f.tick(f.runtime.currentTick() + 1);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.refreshStatusOnly(),
                    "The observation must keep the freshly initialized machine out of powered-stall recovery");
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.tick(false, false),
                    "An unextended piston is an intermediate update, not permission to dismantle it");
            assertTrue(f.mode.hits.isEmpty(), "Do not send another placement while initialization is in flight");

            f.machine(Direction.UP);
            assertEquals(BedrockTarget.Status.EXTENDED, target.tick(false, false),
                    "A confirmed extension must advance immediately without waiting out the timeout");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void unextendedInitializationEventuallyBecomesRecoverable() throws Exception {
        try (var f = new Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(TARGET.east().above(), Blocks.REDSTONE_TORCH.defaultBlockState());
            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            var target = f.target();
            target.setInitializeTick(0);
            var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
            state.beginTick(0);
            for (int tick = 1; tick < target.postExecuteSyncTimeoutTicks(); tick++) {
                f.tick(f.runtime.currentTick() + 1);
                assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.refreshStatusOnly());
            }
            f.tick(f.runtime.currentTick() + 1);
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE, target.refreshStatusOnly(),
                    "A genuinely stalled initialization must still recover after its bounded confirmation window");
        }
    }

    @Test void repoweringStartsANewConfirmationWindowInsteadOfImmediatelyRebuilding() throws Exception {
        try (var f = new Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            f.client.player.getInventory().setItem(0, new ItemStack(Items.REDSTONE_TORCH));
            f.synchronizeInventory();
            var target = f.target();
            var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
            state.beginTick(0);
            state.beginTick(100);
            f.tick(100);
            target.setInitializeTick(0);
            assertTrue(target.tryRepowerTorch());
            assertEquals(1, f.mode.hits.size());

            f.level.blocks.put(TARGET.east().above(), Blocks.REDSTONE_TORCH.defaultBlockState());
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.refreshStatusOnly(),
                    "The new torch must be allowed to extend the piston before dismantling the machine");
            f.machine(Direction.UP);
            assertEquals(BedrockTarget.Status.EXTENDED, target.refreshStatusOnly());
        }
    }


    @Test void dispatchedAttemptNeverAddsLateReversePlacements() throws Exception {
        try (var f = new Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = f.target();
            f.machine(Direction.UP);
            target.setHasTried(true);
            target.setExecuteTick(0);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            for (int tick = 0; tick <= target.postExecuteSyncTimeoutTicks(); tick++) {
                f.tick(tick);
                target.settleDispatchedAttempt(false);
            }
            assertEquals(BedrockTarget.Status.RECOVERING, target.getStatus());
            assertFalse(target.hasTried());
            assertTrue(f.mode.hits.isEmpty(), "An expired retract event is never rescued by a later use packet");
        }
    }

    @Test void collapsedAttemptClosesBeforeANewMachineIsPrepared() throws Exception {
        try (var f = new Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = f.target();
            target.setHasTried(true);
            target.setExecuteTick(0);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            for (int tick = 0; tick <= 4; tick++) {
                f.tick(tick);
                target.settleDispatchedAttempt(false);
            }
            assertEquals(BedrockTarget.Status.RECOVERING, target.getStatus());
            assertFalse(target.hasTried());
            assertTrue(f.mode.hits.isEmpty(), "Draining an old attempt does not begin its successor");
        }
    }

    @Test void completedBedrockDoesNotWaitForTheConfirmationTimeout() throws Exception {
        try (var f = new Fixture()) {
            var target = f.target();
            target.setHasTried(true);
            target.setExecuteTick(0);
            f.level.blocks.remove(TARGET);
            assertEquals(BedrockTarget.Status.RETRACTED, target.refreshStatusOnly());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void restartingHorizontalAttemptReleasesItsPreparedLook() throws Exception {
        try (var f = new Fixture()) {
            var target = f.target(TARGET, Direction.EAST, f.placer);
            f.placer.preparePistonPlacementLook(target.getPistonPos(), Direction.WEST);
            assertTrue(target.hasPendingHorizontalLook());
            assertNotNull(NetworkUtils.getScopedLookOverride());

            target.resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);

            assertFalse(target.hasPendingHorizontalLook());
            assertNull(NetworkUtils.getScopedLookOverride());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void cancellingVerticalAttemptPreservesAnotherTargetsHorizontalLook() throws Exception {
        try (var f = new Fixture()) {
            BlockPos sidePiston = TARGET.east(3);
            f.placer.preparePistonPlacementLook(sidePiston, Direction.WEST);
            var look = NetworkUtils.getScopedLookOverride();

            f.target().resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);

            assertTrue(f.placer.hasPendingHorizontalLook(sidePiston));
            assertSame(look, NetworkUtils.getScopedLookOverride());
        }
    }

    @Test void retiringCompletedHorizontalTargetReleasesItsLook() throws Exception {
        try (var f = new Fixture()) {
            var targets = new BedrockTargetRegistry();
            var side = f.target(TARGET, Direction.EAST, f.placer);
            targets.add(side);
            f.placer.preparePistonPlacementLook(side.getPistonPos(), Direction.WEST);
            f.level.blocks.remove(TARGET);
            var executor = new BedrockTargetExecutor(targets,
                    new BedrockCleanupCoordinator(f.client, () -> false, f.placer), new BedrockRunStats(),
                    (pos, ticks) -> fail("Completed targets do not need a cooldown"),
                    f.placer, new BedrockNetworkSync(f.runtime::currentTick), new BedrockCriticalExecutor(f.placer),
                    new BedrockThroughputScheduler<>());

            executor.observeAndRetire(f.level);

            assertTrue(targets.isEmpty());
            assertFalse(side.hasPendingHorizontalLook());
            assertNull(NetworkUtils.getScopedLookOverride());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(ints = {4, 8})
    void leavingSelectionDropsPreparedPlacementAndNetworkObservation(int removalTick) throws Exception {
        try (var f = new Fixture()) {
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            var targets = (BedrockTargetRegistry) get(engine, BedrockEngine.class, "targets");
            var sync = (BedrockNetworkSync) get(engine, BedrockEngine.class, "networkSync");
            var target = f.target(TARGET, Direction.EAST, placer);
            targets.add(target);
            placer.preparePistonPlacementLook(target.getPistonPos(), Direction.WEST);
            sync.beginAttempt(TARGET, target.getMachineFootprint());
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(TARGET));

            f.tick(removalTick);
            engine.tick();

            assertTrue(targets.isEmpty());
            assertEquals(0, sync.pendingCount());
            assertFalse(target.hasPendingHorizontalLook());
            sync.onServerBlockUpdate(TARGET);
            f.tick(80);
            engine.tick();
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void networkObservationKeepsContinuationAliveAndRetryClosesIt() throws Exception {
        try (var f = new Fixture()) {
            var engine = f.engine();
            var sync = (BedrockNetworkSync) get(engine, BedrockEngine.class, "networkSync");
            sync.beginAttempt(TARGET, java.util.Set.of(TARGET));
            assertTrue(engine.hasActiveWork());

            sync.recordRetry(TARGET);

            assertFalse(engine.hasActiveWork());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work_switch_disabled", "execution_scope_changed", "client_scope_changed", "disconnect"})
    void runtimeResetDropsPreparedLookAndNetworkObservationBeforePositionReuse(String reason) throws Exception {
        try (var f = new Fixture()) {
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            var sync = (BedrockNetworkSync) get(engine, BedrockEngine.class, "networkSync");
            placer.preparePistonPlacementLook(TARGET.east(), Direction.WEST);
            sync.beginAttempt(TARGET, java.util.Set.of(TARGET));
            assertNotNull(NetworkUtils.getScopedLookOverride());

            f.runtime.reset(reason);

            assertFalse(placer.hasPendingHorizontalLook(TARGET.east()));
            assertNull(NetworkUtils.getScopedLookOverride());
            assertEquals(0, sync.pendingCount());
            assertFalse(engine.hasActiveWork());
            sync.onServerBlockUpdate(TARGET);
            assertEquals(0, sync.pendingCount());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    static class TestLevel extends ClientLevel implements NetworkUtils.SequenceExtension {
        Map<BlockPos, BlockState> blocks;
        net.minecraft.world.level.border.WorldBorder border;
        TestChunkCache chunks;
        TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public List<Entity> getEntities(Entity except, net.minecraft.world.phys.AABB bounds,
                                                  java.util.function.Predicate<? super Entity> filter) {
            // These Unsafe-created fixtures have no entity manager or installed entities.
            // Keep vanilla block collision queries available through an explicit empty entity port.
            return List.of();
        }
        @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
            if (border == null) border = new net.minecraft.world.level.border.WorldBorder();
            return border;
        }
        @Override public long getGameTime() { return 0L; }
        boolean isChunkCached(int x, int z) { return true; }
        @SuppressWarnings({"removal", "deprecation"})
        @Override public ClientChunkCache getChunkSource() {
            if (chunks == null) {
                try {
                    var unsafe = (sun.misc.Unsafe) get(null, sun.misc.Unsafe.class, "theUnsafe");
                    chunks = (TestChunkCache) unsafe.allocateInstance(TestChunkCache.class);
                    chunks.world = this;
                    chunks.present = (net.minecraft.world.level.chunk.LevelChunk) unsafe.allocateInstance(
                            net.minecraft.world.level.chunk.LevelChunk.class);
                } catch (Exception error) { throw new AssertionError(error); }
            }
            return chunks;
        }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
    }

    /** The real ChunkSource.hasChunk probes getChunk(FULL, false); only cache presence is replaced. */
    static class TestChunkCache extends ClientChunkCache {
        TestLevel world;
        net.minecraft.world.level.chunk.LevelChunk present;
        TestChunkCache() { super(null, 0); }
        @Override public net.minecraft.world.level.chunk.LevelChunk getChunk(int x, int z,
                net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load) {
            // A forced missing query also returns a non-null empty-chunk placeholder in vanilla.
            return world.isChunkCached(x, z) || load ? present : null;
        }
    }

    static class TestPlayer extends LocalPlayer {
        Inventory inventory;
        //#if MC >= 260300
        //$$ TestPlayer() { super(null, null, null, null, null, null, false, null, null); }
        //#else
        TestPlayer() { super(null, null, null, null, null, null, false, null); }
        //#endif
        @Override public Inventory getInventory() { return inventory; }
        @Override public ItemStack getMainHandItem() { return inventory.getItem(InventoryUtils.getSelectedSlot(inventory)); }
        @Override public ItemStack getOffhandItem() { return inventory.getItem(40); }
        @Override public boolean isShiftKeyDown() { return false; }
    }

    static class TestConnection extends ClientPacketListener {
        CaptureTransport transport;
        TestConnection() { super(null, null, null); }
        @Override public void send(Packet<?> packet) {
            if (this.transport != null) this.transport.capture(packet);
        }
        @Override public net.minecraft.network.Connection getConnection() { return this.transport; }
    }

    static class CaptureTransport extends net.minecraft.network.Connection implements PacketBatchConnection {
        List<Packet<?>> packets;
        CaptureTransport() { super(PacketFlow.CLIENTBOUND); }
        void capture(Packet<?> packet) {
            InventoryRecoveryFence.inputSent(this, packet);
            this.packets.add(packet);
            InventoryRecoveryFence.sent(this, packet);
        }
        @Override public boolean litematica_printer$sendBatch(List<Packet<?>> packets) {
            packets.forEach(this::capture);
            return true;
        }
    }

    static class TestGameMode extends MultiPlayerGameMode implements MultiPlayerGameModeExtension {
        List<BlockHitResult> hits;
        List<Boolean> predictions;
        boolean rejectMutations;
        TestGameMode() { super(null, null); }
        @Override public net.minecraft.world.level.GameType getPlayerMode() { return net.minecraft.world.level.GameType.SURVIVAL; }
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            if (rejectMutations) throw new AssertionError("An observing attempt attempted to place a block");
            assertEquals(prediction ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, hand);
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
        final Map<Field, Object> cachedClients = new LinkedHashMap<>();
        final Minecraft client;
        final TestLevel level;
        final TestGameMode mode;
        final PrinterRuntime runtime;
        final BedrockPlacer placer;
        final TestConnection connection;
        final CaptureTransport transport;

        Fixture() throws Exception {
            unsafe = (sun.misc.Unsafe) get(null, sun.misc.Unsafe.class, "theUnsafe");
            client = allocate(Minecraft.class);
            level = allocate(TestLevel.class);
            set(level, Level.class, "isClientSide", true);
            level.border = new net.minecraft.world.level.border.WorldBorder();
            set(level, Level.class, "dimension", Level.OVERWORLD);
            level.blocks = new HashMap<>();
            level.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            var player = allocate(TestPlayer.class);
            set(player, Entity.class, "level", level);
            player.inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            set(player, Player.class, "inventory", player.inventory);
            set(player, Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            set(player, Player.class, "abilities", new net.minecraft.world.entity.player.Abilities());
            player.containerMenu = player.inventoryMenu;
            player.inventory.setItem(40, new ItemStack(Items.PISTON, 16));
            set(player, Entity.class, "position", new Vec3(0.5, 64, 0.5));
            set(player, Entity.class, "eyeHeight", 1.5F);
            connection = allocate(TestConnection.class);
            transport = allocate(CaptureTransport.class);
            transport.packets = new ArrayList<>();
            connection.transport = transport;
            set(player, LocalPlayer.class, "connection", connection);
            mode = allocate(TestGameMode.class);
            mode.hits = new ArrayList<>();
            mode.predictions = new ArrayList<>();
            client.level = level;
            client.player = player;
            client.gameMode = mode;
            for (var type : List.of(BedrockInventory.class, BedrockBreaker.class, InventoryUtils.class, InteractionUtils.class,
                    ConfigUtils.class, PlayerUtils.class, NetworkUtils.class)) {
                Class.forName(type.getName(), true, type.getClassLoader());
                for (var field : type.getDeclaredFields()) {
                    if (field.getType() != Minecraft.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    cachedClients.put(field, field.get(null));
                    unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
                }
            }
            set(null, Minecraft.class, "instance", client);
            runtime = allocate(PrinterRuntime.class);
            set(runtime, PrinterRuntime.class, "interactionUtils", new InteractionUtils());
            set(runtime, PrinterRuntime.class, "hudStats", new HudStatsManager(client, runtime::currentTick));
            set(runtime, PrinterRuntime.class, "scope", new RuntimeScope());
            set(runtime, PrinterRuntime.class, "events", new RuntimeEventBus());
            set(runtime, PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL);
            RuntimeAccess.install(runtime);
            var guard = new me.aleksilassila.litematica.printer.utils.InventorySwitchGuard(client, runtime::currentTick);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            var tweakeroo = (me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort.class},
                            (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
            set(runtime, PrinterRuntime.class, "toolSwitchService",
                    new me.aleksilassila.litematica.printer.interaction.ToolSwitchService(client, tweakeroo, guard));
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
            placer = new BedrockPlacer(client);
        }

        <T> T allocate(Class<T> type) throws Exception { return type.cast(unsafe.allocateInstance(type)); }
        void synchronizeInventory() {
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.synchronizeInventory(placer, client);
        }
        void readyTool() {
            assertTrue(me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.acknowledgeSelectedTool(placer, client));
        }
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
                    new BedrockCriticalExecutor(targetPlacer), targetPlacer, new BedrockNetworkSync(runtime::currentTick));
        }
        BedrockEngine engine() throws Exception {
            var engine = new BedrockEngine(client, new CooldownUtils(), runtime::currentTick, new LitematicaAdapter());
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
        }
    }

    static Object get(Object obj, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(obj);
    }
    static void set(Object obj, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(obj, value);
    }
}
