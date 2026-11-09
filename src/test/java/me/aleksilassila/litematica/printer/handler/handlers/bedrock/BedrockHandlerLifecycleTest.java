//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real feature entry: existing work has a lifecycle even when no new scan may run. */
class BedrockHandlerLifecycleTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void suppliesAvailableThisTickCanUseTheCurrentAdmissionWindow() throws Exception {
        try (var f = new Fixture()) {
            f.cleanupWorld();
            set(f.engine, BedrockEngine.class, "awaitingSupplies", true);
            f.tick(8);

            f.handler.beforeScan();

            assertTrue(f.engine.canSubmitInCurrentWindow(), "A previous shortage cannot discard this tick's cadence allowance after supplies have arrived");
            BlockPos next = TARGET.east(4);
            f.plan(next, Direction.UP);
            assertTrue(f.engine.submit(next));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anUnbuiltMachinesShortageCannotPreemptAnAlreadyPoweredAttempt(boolean dirtyBudget) throws Exception {
        try (var f = new Fixture()) {
            f.cleanupWorld();
            f.machine(Direction.UP);
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
            var ready = new BedrockTarget(TARGET, f.client.level,
                    f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync);
            f.level.blocks.put(ready.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            ready.setStatus(BedrockTarget.Status.EXTENDED);
            f.targets.add(ready);
            BlockPos next = TARGET.east(4);
            var waiting = new BedrockTarget(next, f.client.level,
                    f.target(next, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(next.east(), Direction.UP), null, critical, f.enginePlacer, sync);
            f.targets.add(waiting);
            f.put(40, Items.PISTON, 1);
            if (dirtyBudget) f.enginePlacer.recordToolUse();
            f.transport.packets.clear();
            f.tick(8);

            f.handler.beforeScan();

            assertTrue(ready.hasTried(), "The existing attempt owns everything it needs; another cycle's refill cannot take its hands first");
            assertFalse(waiting.hasTried());
            assertTrue(f.transport.packets.stream().anyMatch(ServerboundUseItemOnPacket.class::isInstance));
            assertTrue(f.sent.isEmpty(), "Supply providers start only after the already dispatched attempt settles");
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void pendingMaterialsDoNotPreventExistingTargetsFromRetiring() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            target.setStatus(BedrockTarget.Status.FAILED);
            var waiting = new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "waiting_without_inventory_ownership"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
                }
                @Override public MaterialReservation status(MaterialRequest request) { return request(request); }
            }));
            waiting.request(Items.STONE, MaterialRequest.Source.OTHER);
            set(f.runtime, PrinterRuntime.class, "materialRequests", waiting);
            f.tick(8);

            f.handler.beforeScan();

            assertEquals(8L, get(f.engine, BedrockEngine.class, "lastProcessedTick"));
            assertTrue(f.targets.isEmpty(), "A material request cannot keep an already failed cycle in the active lane");
            assertTrue(waiting.isBusy());
            assertFalse(f.engine.canSubmitInCurrentWindow());
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @Test void aPendingProviderWithoutLeasesStillDefersNewConstruction() throws Exception {
        try (var f = new Fixture()) {
            f.cleanupWorld();
            f.level.blocks.remove(TARGET.above());
            f.addTarget();
            var waiting = new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "pending_without_leases"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
                }
                @Override public MaterialReservation status(MaterialRequest request) { return request(request); }
            }));
            waiting.request(Items.STONE, MaterialRequest.Source.OTHER);
            set(f.runtime, PrinterRuntime.class, "materialRequests", waiting);
            f.transport.packets.clear();
            f.tick(8);

            f.handler.beforeScan();

            assertTrue(waiting.isBusy());
            assertTrue(f.transport.packets.isEmpty(), "Read current supply ownership before construction, even when the provider has not acquired leases");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cursor", "menu", "switch", "INVENTORY", "CONTAINER", "MAIN_HAND", "LOOK", "INTERACTION"})
    void anInventoryBoundaryAllowsObservationWithoutStartingAMaterialRequest(String boundary) throws Exception {
        try (var f = new Fixture()) {
            f.addTarget().setStatus(BedrockTarget.Status.FAILED);
            f.put(40, Items.PISTON, 0);
            f.tick(8);
            switch (boundary) {
                case "cursor" -> f.client.player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
                case "menu" -> f.client.player.containerMenu = new InventoryMenu(f.client.player.getInventory(), true, f.client.player);
                case "switch" -> f.runtime.inventorySwitchGuard().markSwitchIfNeeded(Items.STONE);
                default -> assertTrue(f.broker.tryAcquire("other_component", EnumSet.of(ResourceLease.valueOf(boundary)), 0L));
            }

            f.handler.beforeScan();

            assertEquals(8L, get(f.engine, BedrockEngine.class, "lastProcessedTick"));
            assertTrue(f.targets.isEmpty());
            assertTrue(f.sent.isEmpty(), "Observation cannot acquire materials through an existing inventory owner");
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @Test void anInterruptedScanStillFinishesItsNewlyQueuedCycleInTheSameTick() throws Exception {
        try (var f = new Fixture()) {
            f.handler.discover = () -> {
                try {
                    f.addTarget();
                    f.level.blocks.remove(TARGET);
                }
                catch (Exception error) { throw new AssertionError(error); }
            };
            f.tick(8);

            f.handler.tick(TickContext.capture());

            assertEquals(1, f.handler.discovered);
            assertTrue(f.targets.isEmpty(), "The scan's interrupt flag cannot postpone its already queued cycle until another tick");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledFeatureDoesNotAdvanceItsControllerFromTheFinallyBoundary(boolean workDisabled) throws Exception {
        try (var f = new Fixture()) {
            f.addTarget().setStatus(BedrockTarget.Status.FAILED);
            if (workDisabled) Configs.Core.WORK_SWITCH.setBooleanValue(false);
            else Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
            f.tick(8);

            f.handler.tick(TickContext.capture());

            assertEquals(Long.MIN_VALUE, get(f.engine, BedrockEngine.class, "lastProcessedTick"));
            assertFalse(f.targets.isEmpty());
            assertEquals(0, f.handler.discovered);
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    private static final class Probe extends BedrockHandler {
        Runnable discover;
        int discovered;
        Probe(PrinterRuntime runtime) { super(runtime); }
        void beforeScan() { super.preprocess(); }
        @Override protected Iterable<BlockPos> getIterationPositions(PrinterBox box) { return List.of(TARGET); }
        @Override public boolean canIterationBlockPos(BlockPos pos) { return true; }
        @Override protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
            this.discovered++;
            if (this.discover != null) this.discover.run();
            skipIteration.set(true);
        }
    }

    private static final class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final Object oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        final Object oldFeature = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        final Object oldSchematic = get(SchematicWorldHandler.INSTANCE, SchematicWorldHandler.class, "world");
        final Probe handler;

        Fixture() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.BEDROCK);
            // Bedrock only passes this identity to the shared scanner; creating a renderer
            // would require a graphics device unrelated to its lifecycle entry.
            set(SchematicWorldHandler.INSTANCE, SchematicWorldHandler.class, "world", allocate(WorldSchematic.class));
            var info = allocate(PlayerInfo.class);
            set(info, PlayerInfo.class, "gameMode", GameType.SURVIVAL);
            set(client.player, AbstractClientPlayer.class, "playerInfo", info);
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", new ScanEngine(runtime::epoch, () -> client.level, new ScanCache()));
            set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
            set(runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(client, runtime::currentTick));
            handler = new Probe(runtime);
        }

        @Override public void close() throws Exception {
            set(SchematicWorldHandler.INSTANCE, SchematicWorldHandler.class, "world", oldSchematic);
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Core.WORK_MODE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldFeature);
            super.close();
        }
    }
}
//#endif
