package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real preparation gate, target state machines, coordinator and broker; simulated delivery only. */
class BedrockRestockTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final ActionBroker broker = new ActionBroker(runtime, new ActionManager());
        final List<MaterialRequest> sent = new ArrayList<>();
        final InventoryProvider delivery = new InventoryProvider() {
            @Override public String id() { return "restock_test"; }
            @Override public boolean supports(MaterialRequest request) { return true; }
            @Override public MaterialReservation request(MaterialRequest request) {
                if (broker.tryAcquire(id(), EnumSet.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER,
                        ResourceLease.MAIN_HAND), 0L)) sent.add(request);
                return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
            }
            @Override public MaterialReservation status(MaterialRequest request) {
                if (BedrockInventory.hasAtLeast(request.preferredItem(), request.minimumCount())) {
                    broker.releaseOwner(id());
                    return MaterialReservation.available(request, request.preferredItem());
                }
                return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
            }
            @Override public void reset() { broker.releaseOwner(id()); }
        };
        final MaterialRequestCoordinator requests;
        final BedrockEngine engine;
        final BedrockPlacer enginePlacer;
        final BedrockTargetRegistry targets;

        Fixture() throws Exception {
            set(runtime, PrinterRuntime.class, "actionBroker", broker);
            set(client.player, Player.class, "inventoryMenu",
                    new InventoryMenu(client.player.getInventory(), true, client.player));
            client.player.containerMenu = client.player.inventoryMenu;
            client.player.getInventory().setItem(40, ItemStack.EMPTY);
            requests = new MaterialRequestCoordinator(List.of(new PlayerInventoryProvider(client), delivery));
            set(runtime, PrinterRuntime.class, "materialRequests", requests);
            runtime.register(broker);
            runtime.register(requests);
            engine = engine();
            enginePlacer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            targets = (BedrockTargetRegistry) get(engine, BedrockEngine.class, "targets");
        }

        boolean prepare() { return engine.prepareMaterials(requests); }
        void put(int slot, Item item, int count) {
            client.player.getInventory().setItem(slot, new ItemStack(item, count));
        }
        void deliver(Item item, int count) {
            int slot = item == Items.PISTON ? 40 : item == Items.REDSTONE_TORCH ? 10 : 11;
            put(slot, item, count);
            requests.tick();
        }
        BedrockTarget addTarget() throws Exception {
            level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = target(TARGET, Direction.UP, enginePlacer);
            targets.add(target);
            return target;
        }
        void settleTargets() throws Exception {
            var executor = (BedrockTargetExecutor) get(engine, BedrockEngine.class, "targetExecutor");
            var processed = new LinkedHashSet<BedrockTarget>();
            executor.process(level, 4, true, processed, null, false);
            executor.process(level, 4, false, processed, null, false);
        }
    }

    @Test void startupRestocksConsumablesInOrderAndDoesNotResendDuringLatency() throws Exception {
        try (var f = new Fixture()) {
            assertFalse(f.prepare());
            var piston = f.sent.get(0);
            assertSame(Items.PISTON, piston.preferredItem());
            assertEquals(2, piston.minimumCount());
            assertEquals(MaterialRequest.Source.BEDROCK, piston.source());
            for (int tick = 0; tick < 60; tick++) {
                f.requests.tick();
                assertFalse(f.prepare());
            }
            assertEquals(1, f.sent.size());
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.deliver(Items.PISTON, 1);
            assertFalse(f.prepare());
            assertEquals(1, f.sent.size(), "One piston cannot complete the request for two");
            f.deliver(Items.PISTON, 2);
            assertFalse(f.prepare());
            assertSame(Items.REDSTONE_TORCH, f.sent.get(1).preferredItem());
            f.deliver(Items.REDSTONE_TORCH, 1);
            assertFalse(f.prepare());
            assertSame(Items.SLIME_BLOCK, f.sent.get(2).preferredItem());
            f.deliver(Items.SLIME_BLOCK, 1);
            assertTrue(f.prepare());
            assertFalse(f.requests.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.mode.hits.isEmpty(), "Restocking alone never places a machine or a tool");
        }
    }

    @Test void anUnexecutedTargetDoesNotDeadlockRestocking() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            assertFalse(f.prepare());
            assertEquals(1, f.sent.size());
            assertTrue(f.engine.hasActiveWork());
            assertFalse(f.engine.canSubmitInCurrentWindow());
            assertFalse(f.engine.submit(TARGET.east(5)));
            f.settleTargets();
            assertEquals(0, target.tickTimes());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void installedPistonAndTorchDoNotRequireAnotherWholeMachine() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            f.machine(Direction.UP);
            f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            f.put(40, Items.PISTON, 1);
            assertTrue(f.prepare(), "Only the reverse piston is still needed by this machine");
            assertTrue(f.sent.isEmpty());
        }
    }

    @Test void dispatchedObservationFinishesBeforeRefillingWithoutRepeatingPlacement() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            target.setHasTried(true);
            target.setExecuteTick(0);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            f.put(40, Items.PISTON, 1);
            f.machine(Direction.UP);
            for (int tick = 0; tick < 8; tick++) {
                f.tick(tick);
                assertTrue(f.prepare());
                f.settleTargets();
                assertTrue(f.sent.isEmpty());
                assertTrue(f.mode.hits.isEmpty(), "Confirmation observes the complete original bundle");
            }
            f.put(40, Items.PISTON, 0);
            assertTrue(f.prepare());
            assertTrue(target.hasTried(), "An empty hand cannot cancel the dispatched attempt");

            f.level.blocks.clear();
            f.tick(8);
            f.settleTargets();
            assertTrue(f.targets.isEmpty());
            assertFalse(f.prepare());
            assertEquals(1, f.sent.size());
            assertTrue(f.mode.hits.isEmpty(), "Restocking never replays an old reverse placement");
        }
    }

    @Test void failedAttemptCanRestockWithoutDiscardingItsTarget() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            target.setHasTried(true);
            target.setExecuteTick(0);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            assertTrue(f.prepare());
            for (int tick = 0; tick < 5; tick++) {
                f.tick(tick);
                f.settleTargets();
            }
            assertFalse(target.hasTried(), "The failed event closes before recovery can request supplies");
            assertEquals(BedrockTarget.Status.RECOVERING, target.getStatus());
            assertFalse(f.targets.isEmpty());
            assertFalse(f.prepare());
            assertSame(Items.PISTON, f.sent.get(0).preferredItem());
            assertTrue(f.mode.hits.isEmpty(), "Recovery may not start a fresh machine while draining");
        }
    }

    @Test void drainingKeepsTheSameFirstPostDispatchTickAsOrdinaryExecution() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addTarget();
            f.machine(Direction.UP);
            target.refreshStatusOnly();
            target.setHasTried(true);
            target.setExecuteTick(0);
            target.setStatus(BedrockTarget.Status.EXTENDED);
            assertTrue(f.prepare());
            f.tick(1);
            f.settleTargets();
            assertEquals(1, target.tickTimes());
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.getStatus());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work_switch_disabled", "execution_scope_changed", "client_scope_changed", "disconnect"})
    void resetDropsTheBedrockRequestAndDoesNotReplayItAfterLateDelivery(String reason) throws Exception {
        try (var f = new Fixture()) {
            assertFalse(f.prepare());
            assertTrue(f.requests.isBusy());
            f.runtime.reset(reason);
            f.deliver(Items.PISTON, 64);
            for (int tick = 0; tick < 100; tick++) f.requests.tick();
            assertFalse(f.requests.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.sent.size());
            assertTrue(f.mode.hits.isEmpty());
            assertFalse(f.engine.hasActiveWork());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"PRINT", "PICK_BLOCK", "CHEST_TRACKER_SCREEN", "TAKE_IT_OUT_PICK"})
    void anExistingRequestIsNotReplacedOrCancelledByBedrock(String source) throws Exception {
        try (var f = new Fixture()) {
            var manual = MaterialRequest.Source.valueOf(source);
            f.requests.request(Items.STONE, manual);
            long token = f.requests.activeToken();
            assertFalse(f.prepare());
            f.requests.cancelOwner("bedrock");
            assertEquals(token, f.requests.activeToken());
            assertEquals(1, f.sent.size());
            assertSame(Items.STONE, f.sent.get(0).preferredItem());
            f.put(12, Items.STONE, 1);
            f.requests.tick();
            assertFalse(f.requests.isBusy());
            assertFalse(f.prepare());
            assertSame(Items.PISTON, f.sent.get(1).preferredItem());
        }
    }

    //#if MC == 260100
    static class EntryProbe extends me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler {
        EntryProbe() { super(null); }
        boolean prepareFromFeature() { return super.canExecute(); }
    }

    private static EntryProbe entry(Fixture f) throws Exception {
        var probe = f.allocate(EntryProbe.class);
        var base = me.aleksilassila.litematica.printer.handler.FeatureModuleBase.class;
        set(probe, base, "id", "bedrock");
        set(probe, base, "actionBroker", f.broker);
        set(probe, base, "player", f.client.player);
        var info = f.allocate(net.minecraft.client.multiplayer.PlayerInfo.class);
        set(info, net.minecraft.client.multiplayer.PlayerInfo.class, "gameMode", net.minecraft.world.level.GameType.SURVIVAL);
        set(f.client.player, net.minecraft.client.player.AbstractClientPlayer.class, "playerInfo", info);
        set(f.client.player, Player.class, "abilities", new net.minecraft.world.entity.player.Abilities());
        set(f.client.player, net.minecraft.world.entity.LivingEntity.class, "equipment", new net.minecraft.world.entity.EntityEquipment());
        set(f.client, net.minecraft.client.Minecraft.class, "gui", f.allocate(net.minecraft.client.gui.Gui.class));
        set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard",
                new me.aleksilassila.litematica.printer.utils.InventorySwitchGuard(f.client, f.runtime::currentTick));
        return probe;
    }

    @Test void theRealFeatureEntryStartsMissingMaterialRequestsBeforeItsWarningStopsScanning() throws Exception {
        try (var f = new Fixture()) {
            f.put(40, Items.PISTON, 1);
            assertFalse(entry(f).prepareFromFeature());
            assertEquals(1, f.sent.size());
            assertSame(Items.PISTON, f.sent.get(0).preferredItem());
            assertEquals(2, f.sent.get(0).minimumCount());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cursor", "menu", "switch", "INVENTORY", "CONTAINER", "MAIN_HAND", "LOOK", "INTERACTION"})
    void theFeatureCannotStartRestockingThroughAnInventoryBoundary(String boundary) throws Exception {
        try (var f = new Fixture()) {
            var probe = entry(f);
            switch (boundary) {
                case "cursor" -> f.client.player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
                case "menu" -> f.client.player.containerMenu = new InventoryMenu(f.client.player.getInventory(), true, f.client.player);
                case "switch" -> f.runtime.inventorySwitchGuard().markSwitchIfNeeded(Items.STONE);
                default -> assertTrue(f.broker.tryAcquire("other_component", EnumSet.of(ResourceLease.valueOf(boundary)), 0L));
            }
            assertFalse(probe.prepareFromFeature());
            assertTrue(f.sent.isEmpty());
            assertTrue(f.mode.hits.isEmpty());
            f.client.player.containerMenu = f.client.player.inventoryMenu;
            f.client.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            f.runtime.inventorySwitchGuard().reset();
            f.broker.releaseOwner("other_component");
            assertFalse(probe.prepareFromFeature());
            assertEquals(1, f.sent.size(), "The same entry resumes once the existing owner has settled");
        }
    }
    //#endif
}
