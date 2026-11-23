//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The real executor may become ready without any increase in per-item inventory totals. */
class PrintMaterialWaitRecoveryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void unrelatedGainsPreserveMaterialWaitsAndMatchingGainsWakeTheOriginalTarget() throws Exception {
        try (var f = new MaterialFixture()) {
            var loop = get(f.handler, FeatureModuleBase.class, "runtimeLoop");
            var inventoryChanged = loop.getClass().getDeclaredMethod("wakeForInventoryChange");
            inventoryChanged.setAccessible(true);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            f.availability.tick(f.client.player);
            inventoryChanged.invoke(loop);
            f.advance(1);
            assertEquals(List.of(TARGET), f.handler.positions());
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            f.handler.execute();
            assertEquals(1, f.handler.getPendingIterationWorkCount());

            f.client.player.getInventory().setItem(1, new ItemStack(Items.DIRT));
            f.availability.tick(f.client.player);
            inventoryChanged.invoke(loop);
            f.advance(2);
            assertTrue(f.handler.positions().isEmpty());
            assertEquals(1, f.handler.getPendingIterationWorkCount(), "Dirt must preserve the stone wait");

            long beforeDelivery = f.availability.gainRevision();
            f.client.player.getInventory().setItem(0, new ItemStack(Items.STONE));
            f.client.player.getInventory().setItem(3, new ItemStack(Items.SAND));
            f.availability.tick(f.client.player);
            assertTrue(f.availability.gainedSince(new Item[]{Items.SAND}, beforeDelivery));
            f.client.player.getInventory().setItem(2, new ItemStack(Items.GRAVEL));
            f.availability.tick(f.client.player);
            inventoryChanged.invoke(loop);
            f.advance(3);
            assertEquals(List.of(TARGET), f.handler.positions(), "A slower module must still observe the stone gain");
        }
    }

    @Test void aNewQuickShulkerMakesItsContentsAvailableBeforeTheFallback() throws Exception {
        boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        try (var f = new MaterialFixture()) {
            Configs.Placement.QUICK_SHULKER.setBooleanValue(true);
            f.availability.tick(f.client.player);
            long before = f.availability.gainRevision();
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE))));
            f.client.player.getInventory().setItem(0, box);
            f.availability.tick(f.client.player);
            assertTrue(f.availability.gainedSince(new Item[]{Items.STONE}, before));
            assertFalse(f.availability.gainedSince(new Item[]{Items.DIRT}, before));
        } finally {
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
        }
    }

    @Test void providerStockWakesMaterialWaitsWithoutDiscardingBufferedOrWorldBlockedWork() throws Exception {
        try (var f = new MaterialFixture()) {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(true);
            f.advance(1);
            var targets = (PrintTargetScheduler) get(f.handler, PrintHandler.class, "targets");
            var sorted = (SortedSchematicTargetQueue) get(targets, PrintTargetScheduler.class, "sortedTargets");
            BlockPos ready = TARGET.below(), worldBlocked = TARGET.above();
            sorted.requeue(ready);
            targets.deferUntil(worldBlocked, 10);
            targets.waitForMaterial(TARGET, 1, false, new Item[]{Items.STONE});

            f.runtime.events().publish(new RuntimeEvent.MaterialAvailabilityChanged());

            var round = targets.readyOrdinaryTargets(2).iterator();
            assertEquals(ready, round.next());
            assertEquals(TARGET, round.next());
            assertFalse(round.hasNext());
            assertTrue(targets.retainedTargets().contains(worldBlocked));
        }
    }

    @Test void loweringTheReserveWithoutAnInventoryGainCannotParkAnOrdinaryTargetForever() throws Exception {
        try (var f = new MaterialFixture()) {
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(true);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(16);
            f.client.player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 0);
            f.availability.tick(f.client.player);
            long gain = f.availability.gainRevision();
            f.advance(1);
            assertEquals(List.of(TARGET), f.handler.positions());
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            f.handler.execute();
            assertEquals(0, f.queueAttempts, "The reserve guard must still reject consumption of the last reserved items");
            assertTrue(f.handler.getPendingIterationWorkCount() > 0);

            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(0);
            f.availability.tick(f.client.player);
            assertEquals(gain, f.availability.gainRevision(), "Changing a reserve cannot masquerade as inventory gain");
            f.advance(2);
            assertTrue(f.handler.positions().isEmpty());
            f.wake(TARGET.below());
            f.advance(6);
            assertTrue(f.handler.positions().isEmpty(), "Neighbour traffic must not turn material waits into hot retries");
            f.advance(21);

            assertEquals(List.of(TARGET), f.handler.positions(), "A bounded slow material recheck must recover without movement, a gain or a world event");
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            assertSame(f.client, f.handler.getContext().client, "The headless action context must use this fixture's singleton client");
            f.handler.execute();
            assertEquals(1, f.queueAttempts, "The real executor must progress after the reserve becomes satisfiable: "
                    + f.runtime.hudStats().snapshot(me.aleksilassila.litematica.printer.handler.HudStatsManager.Mode.PRINT).lastReason());
            assertEquals(16, f.client.player.getMainHandItem().getCount(), "The boundary fixture does not predict material consumption");
        }
    }

    @Test void aPendingProviderAndPredictedSlotConfirmationResumeWithoutANetMaterialGain() throws Exception {
        try (var f = new MaterialFixture()) {
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            f.client.player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 0);
            f.availability.tick(f.client.player);
            long gain = f.availability.gainRevision();
            f.advance(1);
            f.providerPending = true;
            f.requests.request(Items.STONE, MaterialRequest.Source.PRINT);
            f.runtime.inventorySwitchGuard().markSwitchIfNeeded(Items.STONE);
            assertTrue(f.runtime.inventorySwitchGuard().isWaiting(), "A same-tick matching hand is still only a predicted slot change");
            assertEquals(List.of(TARGET), f.handler.positions());
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            assertTrue(f.handler.execute(), "A busy provider's PENDING result really stops this printer iteration");
            assertEquals(0, f.queueAttempts);

            f.providerPending = false;
            f.requests.tick();
            f.advance(2);
            f.availability.tick(f.client.player);
            assertFalse(f.runtime.inventorySwitchGuard().isWaiting());
            assertFalse(f.requests.isBusy());
            assertEquals(gain, f.availability.gainRevision(), "Slot confirmation and provider completion do not imply a material gain");

            assertEquals(List.of(TARGET), f.handler.positions(), "An active retrieval wait must get a next-tick retry after it completes");
            assertTrue(f.handler.canIterationBlockPos(TARGET));
            assertSame(f.client, f.handler.getContext().client, "The headless action context must use this fixture's singleton client");
            f.handler.execute();
            assertEquals(1, f.queueAttempts, "The recovered provider must reach the action boundary: "
                    + f.runtime.hudStats().snapshot(me.aleksilassila.litematica.printer.handler.HudStatsManager.Mode.PRINT).lastReason());
        }
    }

    private static final class WaitingPrintHandler extends PrintHandler {
        private boolean firstOffer = true;
        WaitingPrintHandler(PrinterRuntime runtime) { super(runtime); }
        List<BlockPos> positions() {
            var result = new java.util.ArrayList<BlockPos>();
            for (BlockPos pos : super.getIterationPositions(new PrinterBox(TARGET))) result.add(pos);
            return result;
        }
        boolean execute() {
            var skip = new AtomicReference<>(false);
            super.executeIteration(TARGET, skip);
            return skip.get();
        }
        void prepareTick() { super.preprocess(); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) { return List.of(box); }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return true; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return true; }
        @Override protected Iterable<BlockPos> getCachedFilteredIterationPositions(
                PrinterBox box, ScanIntent intent, Predicate<BlockPos> predicate) {
            if (!this.firstOffer) return List.of();
            this.firstOffer = false;
            return predicate.test(TARGET) ? List.of(TARGET) : List.of();
        }
    }

    private static final class TimedLevel extends TestLevel {
        long now;
        @Override public long getGameTime() { return this.now; }
    }

    private static final class MaterialFixture extends Fixture {
        final boolean oldSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        final boolean oldReserve = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        final int oldReserveCount = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        final boolean oldPrecision = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        final boolean oldRenderHolding = Configs.Core.RENDER_ONLY_HOLDING_ITEMS.getBooleanValue();
        final boolean oldAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        final Field schematicField = SchematicWorldHandler.class.getDeclaredField("world");
        final Object oldSchematic;
        final TimedLevel world;
        final WaitingPrintHandler handler;
        final InventoryAvailabilityTracker availability = new InventoryAvailabilityTracker();
        final MaterialRequestCoordinator requests;
        boolean providerPending;
        int queueAttempts;

        MaterialFixture() throws Exception {
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(false);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            Configs.Core.RENDER_ONLY_HOLDING_ITEMS.setBooleanValue(false);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
            world = allocate(TimedLevel.class);
            world.blocks = new HashMap<>();
            set(world, Level.class, "isClientSide", true);
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            set(client.player, Entity.class, "level", world);
            set(client, Minecraft.class, "gui", allocate(Gui.class));
            var schematic = allocate(WaterSupportRegressionTest.Schematic.class);
            schematic.states = new HashMap<>();
            schematic.states.put(TARGET, Blocks.STONE.defaultBlockState());
            schematicField.setAccessible(true);
            oldSchematic = schematicField.get(SchematicWorldHandler.INSTANCE);
            schematicField.set(SchematicWorldHandler.INSTANCE, schematic);
            InventoryProvider unavailable = new InventoryProvider() {
                @Override public String id() { return "material-wait-recheck"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), providerPending
                            ? MaterialReservation.State.PENDING : MaterialReservation.State.UNAVAILABLE);
                }
                @Override public MaterialReservation status(MaterialRequest request) { return this.request(request); }
            };
            requests = new MaterialRequestCoordinator(List.of(unavailable));
            var cooldowns = new CooldownUtils();
            var missing = new MissingMaterialTracker();
            var adapter = new LitematicaAdapter();
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> world, new ScanCache()));
            set(runtime, PrinterRuntime.class, "actionBroker", new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null));
            set(runtime, PrinterRuntime.class, "cooldownUtils", cooldowns);
            set(runtime, PrinterRuntime.class, "missingMaterials", missing);
            set(runtime, PrinterRuntime.class, "inventoryAvailability", availability);
            set(runtime, PrinterRuntime.class, "litematica", adapter);
            set(runtime, PrinterRuntime.class, "materialRequests", requests);
            handler = new WaitingPrintHandler(runtime);
            ActionPort boundary = (ActionPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ActionPort.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("queueClick")) { queueAttempts++; return true; }
                        if (method.getName().equals("sendQueue")) return ActionPort.SendResult.INTERACTION_REJECTED;
                        return method.getReturnType() == boolean.class ? false : null;
                    });
            set(handler, PrintHandler.class, "placementExecutor", new PrintPlacementExecutor(boundary, cooldowns,
                    runtime.inventorySwitchGuard(), runtime.hudStats(), missing, adapter, new FallingPlacementTracker(), requests));
        }

        void advance(long now) throws Exception { world.now = now; tick(now); handler.prepareTick(); }
        void wake(BlockPos pos) throws Exception {
            var targets = (me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler)
                    get(handler, PrintHandler.class, "targets");
            targets.wake(pos);
        }
        @Override public void close() throws Exception {
            schematicField.set(SchematicWorldHandler.INSTANCE, oldSchematic);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(oldSorted);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(oldReserve);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(oldReserveCount);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(oldPrecision);
            Configs.Core.RENDER_ONLY_HOLDING_ITEMS.setBooleanValue(oldRenderHolding);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(oldAir);
            super.close();
        }
    }
}
//#endif
