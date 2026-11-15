//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.feature.SharedPlacementSettings;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.handlers.CoverHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.integration.inventory.*;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementRetryCapacityTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"fill", "cover", "fluid"})
    void missingMaterialRetriesStayBoundedAndEvictedCoordinatesReenterOnInventoryWakeup(String mode) throws Exception {
        try (var f = new Fixture(mode)) {
            int offered = 0;
            for (int tick = 1; tick <= 3; tick++) {
                f.tick(tick);
                for (BlockPos pos : f.positions()) {
                    f.execute(pos);
                    offered++;
                }
            }
            assertTrue(offered > 1024, "The real handler must have encountered more targets than the retained retry capacity");
            assertTrue(f.retries().size() <= 1024, "Missing material cannot retain the whole selected volume as retries");
            assertFalse(f.retries().contains(BlockPos.ZERO), "The oldest offered retry must yield bounded delivery ownership");
            assertEquals(0, f.sent);
            long dirty = f.scan.dirtyVersion();

            f.world.setSlot(0, new ItemStack(Items.STONE, 64));
            f.world.selectSlot(0);
            f.tick(4);
            Method wake = FeatureModuleBase.class.getDeclaredMethod("onInventoryAvailabilityChanged");
            wake.setAccessible(true);
            wake.invoke(f.handler);
            var resumed = f.positions().iterator();
            assertTrue(resumed.hasNext());
            BlockPos recovered = resumed.next();
            assertEquals(BlockPos.ZERO, recovered, "Inventory wakeup must recover an evicted target at its original rank");
            f.execute(recovered);

            assertEquals(1, f.sent);
            assertTrue(f.inFlight().contains(recovered), "A real sent action keeps its confirmation ownership");
            assertEquals(dirty, f.scan.dirtyVersion(), "Retry eviction is not world completion or invalidation");
            assertTrue(f.retries().size() <= 1024);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"fill", "cover", "fluid"})
    void capacityEvictionReoffersTheEarliestTargetWithoutResetOrInventoryWakeup(String mode) throws Exception {
        try (var f = new Fixture(mode)) {
            ScanCandidateIterable lastSource = null;
            int nextTick = 1;
            for (; nextTick <= 12; nextTick++) {
                f.tick(nextTick);
                var source = f.positions();
                lastSource = assertInstanceOf(ScanCandidateIterable.class, source);
                for (BlockPos pos : source) f.execute(pos);
                if (f.scan.metricsFor(mode).completedPasses() > 0) {
                    nextTick++;
                    break;
                }
            }
            assertTrue(f.scan.metricsFor(mode).completedPasses() > 0,
                    "The original cold offer pass has actually completed");
            assertEquals(ScanAvailability.PAUSED, lastSource.availability(),
                    "Unowned reoffers remain pending for a later delivery iterator after the cold pass completes");
            assertFalse(f.retries().contains(BlockPos.ZERO), "This recovery must concern an evicted delivery reference");
            assertTrue(f.retries().size() <= 1024);
            long dirty = f.scan.dirtyVersion();
            f.world.setSlot(0, new ItemStack(Items.STONE, 64));
            f.world.selectSlot(0);
            BlockPos recovered = null;
            int laterOffers = 0;
            for (int tick = nextTick; tick < nextTick + 16 && recovered == null; tick++) {
                f.tick(tick);
                for (BlockPos pos : f.positions()) {
                    laterOffers++;
                    if (pos.equals(BlockPos.ZERO)) { recovered = pos; break; }
                }
            }
            assertNotNull(recovered,
                    "An evicted fact must regain delivery in a finite follow-up before repair, without reset or inventory hook; "
                            + "laterOffers=" + laterOffers + "; " + f.deliveryState(mode));
            f.execute(recovered);
            assertEquals(1, f.sent);
            assertTrue(f.inFlight().contains(recovered));
            assertEquals(dirty, f.scan.dirtyVersion());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MineCandidateRefreshFixture world = new MineCandidateRefreshFixture(false);
        final Object oldInventoryClient = get(null, InventoryUtils.class, "client");
        final Object oldFillMode = Configs.Fill.FILL_BLOCK_MODE.getOptionListValue();
        final Object oldCoverMode = Configs.Cover.COVER_BLOCK_MODE.getOptionListValue();
        final Object oldFacing = Configs.Fill.FILL_BLOCK_FACING.getOptionListValue();
        final PrinterBox box = new PrinterBox(0, 0, 0, 31, 0, 47);
        final ScanEngine scan;
        final FeatureModuleBase handler;
        final Method positions;
        final Method execute;
        int sent;

        Fixture(String mode) throws Exception {
            set(world.mining.player, net.minecraft.world.entity.Entity.class, "blockPosition", BlockPos.ZERO);
            set(world.level, net.minecraft.world.level.Level.class, "dimension", net.minecraft.world.level.Level.OVERWORLD);
            set(null, InventoryUtils.class, "client", world.mining.client);
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
            Configs.Cover.COVER_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
            Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(FillModeFacingType.NORTH);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                world.state(new BlockPos(x, 0, z), (mode.equals("fluid") ? Blocks.WATER : Blocks.AIR).defaultBlockState());
                world.state(new BlockPos(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            var cache = new ScanCache();
            Object budget = get(cache, ScanCache.class, "budget");
            set(budget, budget.getClass(), "nanoClock", (LongSupplier) () -> 0L);
            scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> world.level, cache);
            var runtime = RuntimeAccess.get();
            set(runtime, PrinterRuntime.class, "events", new RuntimeEventBus());
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", scan);
            set(runtime, PrinterRuntime.class, "actionBroker", new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null));
            set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
            set(runtime, PrinterRuntime.class, "hudStats", new HudStatsManager(world.mining.client, () -> world.level.tick));
            set(runtime, PrinterRuntime.class, "missingMaterials", new MissingMaterialTracker());
            set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(world.mining.client, () -> world.level.tick));
            set(runtime, PrinterRuntime.class, "materialRequests", new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "unavailable-test"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
                }
            })));
            SharedPlacementSettings settings = new SharedPlacementSettings() {
                @Override public boolean placeInAir() { return true; }
                @Override public List<String> replaceableList() { return List.of(); }
            };
            handler = switch (mode) {
                case "fill" -> new FillHandler(runtime, settings);
                case "cover" -> new CoverHandler(runtime);
                default -> new FluidHandler(runtime, settings);
            };
            Class<?> type = handler.getClass();
            if (handler instanceof FillHandler) set(handler, type, "fillModeItemList", new Item[]{Items.STONE});
            else if (handler instanceof CoverHandler) set(handler, type, "coverItems", new Item[]{Items.STONE});
            else {
                set(handler, type, "fluids", Set.of(Fluids.WATER));
                set(handler, type, "fillItemArray", new Item[]{Items.STONE});
            }
            ActionPort actions = (ActionPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ActionPort.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("queueClick")) return true;
                        if (method.getName().equals("sendQueue")) { sent++; return ActionPort.SendResult.SENT; }
                        return method.getReturnType() == boolean.class ? false : null;
                    });
            set(handler, FeatureModuleBase.class, "actionBroker", actions);
            handler.playerInteractionBox.set(box);
            positions = type.getDeclaredMethod("getIterationPositions", PrinterBox.class);
            positions.setAccessible(true);
            execute = type.getDeclaredMethod("executeIteration", BlockPos.class, AtomicReference.class);
            execute.setAccessible(true);
        }

        void tick(long tick) { world.level.tick = tick; scan.beginTick(world.level, null, tick); }
        @SuppressWarnings("unchecked") Iterable<BlockPos> positions() throws Exception { return (Iterable<BlockPos>) positions.invoke(handler, box); }
        void execute(BlockPos pos) throws Exception { execute.invoke(handler, pos, new AtomicReference<>(false)); }
        @SuppressWarnings("unchecked") Set<BlockPos> retries() throws Exception { return (Set<BlockPos>) get(handler, handler.getClass(), "retryTargets"); }
        @SuppressWarnings("unchecked") Set<BlockPos> inFlight() throws Exception { return (Set<BlockPos>) get(handler, handler.getClass(), "inFlightTargets"); }
        @SuppressWarnings("unchecked") String deliveryState(String owner) throws Exception {
            Object cache = get(scan, ScanEngine.class, "cache");
            Object store = get(cache, ScanCache.class, "sessions");
            var sessions = (java.util.Map<String, ?>) get(store, store.getClass(), "sessions");
            var entry = sessions.entrySet().stream().filter(item -> item.getKey().startsWith(owner + ":")).findFirst().orElseThrow();
            Object session = entry.getValue();
            var dirty = (it.unimi.dsi.fastutil.longs.LongSet) get(session, session.getClass(), "dirtyPositionKeys");
            var offered = (it.unimi.dsi.fastutil.longs.LongSet) get(session, session.getClass(), "offeredDirtyPositions");
            return entry.getKey() + ": dirtyZERO=" + dirty.contains(BlockPos.ZERO.asLong())
                    + ", offeredZERO=" + offered.contains(BlockPos.ZERO.asLong())
                    + ", deferred=" + get(session, session.getClass(), "deferredDirtyRescan")
                    + ", exhausted=" + get(session, session.getClass(), "exhaustedUntilTick");
        }
        @Override public void close() throws Exception {
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldFillMode);
            Configs.Cover.COVER_BLOCK_MODE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldCoverMode);
            Configs.Fill.FILL_BLOCK_FACING.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldFacing);
            set(null, InventoryUtils.class, "client", oldInventoryClient);
            world.close();
        }
    }
}
//#endif
