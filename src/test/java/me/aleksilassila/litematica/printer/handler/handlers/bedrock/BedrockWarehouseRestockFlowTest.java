//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockAdapter;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Production engine/provider/session flow, with separate authoritative menu contents. */
class BedrockWarehouseRestockFlowTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos RESIDUE = new BlockPos(-4, 65, 0);
    private static final int SOURCE = 3;

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void fastCleanupToolWearDoesNotDelayAnAlreadyCalibratedWarehouseMaterialRequest() throws Exception {
        boolean enabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
        try (var f = new Fixture()) {
            f.fastCleanupWithMissingTorch();

            assertFalse(f.engine.prepareMaterials(f.warehouseRequests));

            assertEquals(1, f.network.sends, "Tool wear alone must not insert another inventory baseline before requesting the missing torch");
            assertEquals(List.of("minecraft:redstone_torch"), f.network.candidates);
            assertTrue(f.warehouseRequests.isBusy());
            for (var resource : List.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)) {
                assertTrue(f.broker.isResourceHeld(resource));
            }
            assertEquals(0, f.markers(), "No material count changed after the confirmed baseline");
            assertFalse(f.enginePlacer.isInventorySettling());
        } finally {
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(enabled);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"material_cost", "refresh_pending", "strong_pending"})
    void warehouseMaterialPreparationStillHonorsCountCostsAndOutstandingInventoryFences(String boundary) throws Exception {
        boolean enabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
        try (var f = new Fixture()) {
            f.fastCleanupWithMissingTorch();
            if (boundary.equals("material_cost")) {
                f.inventory.recordRawUses(1, -1, 0);
                f.serverPlayer.inventory.getItem(40).shrink(1);
                assertEquals(Boolean.TRUE, get(f.inventory, BedrockInventorySession.class, "countDirty"));
            } else if (boundary.equals("refresh_pending")) {
                f.inventory.maintainInventory(10);
                assertTrue(f.enginePlacer.isInventoryPreparationPending());
                assertFalse(f.enginePlacer.isInventorySettling(), "This control owns a background refresh, not a strong fence");
            } else {
                assertTrue(f.inventory.reconcileInventoryIfDirty(), "The generic tool-dirty synchronization must remain intact");
                assertTrue(f.enginePlacer.isInventorySettling());
            }

            assertTrue(f.engine.prepareMaterials(f.warehouseRequests));

            assertEquals(0, f.network.sends, "A count cost or an outstanding ordered reply must prevent provider acquisition");
            assertFalse(f.warehouseRequests.isBusy());
            for (var resource : List.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)) {
                assertFalse(f.broker.isResourceHeld(resource));
            }
            assertEquals(1, f.markers(), "The existing fence must be preserved without replacing or duplicating its marker");
            assertTrue(f.enginePlacer.isInventoryPreparationPending());
            f.replyToSnapshot();
            if (boundary.equals("refresh_pending")) {
                f.engine.onRuntimeEvent(new RuntimeEvent.InventorySynchronized(f.transport));
            }
            assertFalse(f.enginePlacer.isInventoryPreparationPending());

            assertFalse(f.engine.prepareMaterials(f.warehouseRequests));
            assertEquals(1, f.network.sends, "The same material request may start immediately after its actual ordered reply");
            assertEquals(List.of("minecraft:redstone_torch"), f.network.candidates);
            assertTrue(f.warehouseRequests.isBusy());
            for (var resource : List.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)) {
                assertTrue(f.broker.isResourceHeld(resource));
            }
            assertEquals(1, f.markers());
        } finally {
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(enabled);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aWarehouseRefillCanReachTheOffhandWithoutAnotherBaselineAfterEnginePreflight(boolean cleanupFirst) throws Exception {
        boolean enabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
        try (var f = new Fixture()) {
            f.cleanupWorld();
            f.setBreakSpeed(4.5D); // Native piston delta is 8 * 4.5 / (1.5 * 30) = 0.8: START + STOP.
            var piston = Blocks.PISTON.defaultBlockState();
            assertEquals(0.8F, piston.getDestroyProgress(f.client.player, f.client.level, RESIDUE), 0.00001F);
            assertEquals(0.8F, PlayerUtils.getDestroyProgress(f.client.player, piston, f.client.player.getMainHandItem()), 0.00001F);
            f.level.blocks.remove(TARGET.above());
            BedrockInventoryTestAccess.synchronizeInventory(f.enginePlacer, f.client);
            f.copyToServer();
            int initialToolUses = f.enginePlacer.availableToolUses();

            f.inventory.recordRawUses(64, -1, 0);
            f.serverPlayer.inventory.setItem(40, ItemStack.EMPTY); // All preceding server USEs accepted.
            assertTrue(f.client.player.getOffhandItem().isEmpty());
            assertTrue(f.engine.prepareMaterials(f.warehouseRequests));
            assertEquals(0, f.network.sends, "The engine reconciles its dirty inventory before acquiring a provider");
            assertTrue(f.enginePlacer.isInventorySettling());
            f.replyToSnapshot();
            assertFalse(f.enginePlacer.isInventorySettling());

            assertFalse(f.engine.prepareMaterials(f.warehouseRequests));
            assertEquals(1, f.network.sends);
            assertTrue(f.warehouseRequests.isBusy());
            for (var resource : List.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)) {
                assertTrue(f.broker.isResourceHeld(resource));
            }

            // NCA takes and mutates inventory, broadcasts it, then sends its matching nonce's OK.
            // The server inventory is distinct from the client's predicted inventory throughout.
            f.serverPlayer.inventory.setItem(SOURCE, new ItemStack(Items.PISTON, 64));
            f.applyServerContents();
            f.network.status = PrinterTakeResultPayload.OK;
            f.warehouseRequests.tick();
            assertFalse(f.warehouseRequests.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(0, f.inventory.availableCount(SOURCE), "A normal delivery does not silently create a Session budget");
            assertTrue(f.client.player.getOffhandItem().isEmpty());

            if (cleanupFirst) {
                f.level.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState());
                var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
                cleanup.enqueueCycle(RESIDUE.below(), Set.of(RESIDUE), false);
            }
            f.targets.add(f.target(TARGET, Direction.UP, f.enginePlacer));
            f.transport.packets.clear();
            f.tick(1L);
            f.engine.observeSupplies(f.warehouseRequests);
            f.engine.beginTick(true);

            if (cleanupFirst) {
                var digs = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                        .map(ServerboundPlayerActionPacket.class::cast).filter(packet -> packet.getPos().equals(RESIDUE)).toList();
                assertEquals(List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), digs.stream().map(ServerboundPlayerActionPacket::getAction).toList());
                assertEquals(initialToolUses - 1, f.enginePlacer.availableToolUses(), "The emitted cleanup attempt records a real tool cost");
                assertFalse(f.enginePlacer.hasActiveCleanupMining(), "The fixture must not manufacture an OPEN mining blocker");
            }
            assertEquals(64, f.client.player.getOffhandItem().getCount(),
                    "A completed Warehouse delivery should need only the SWAP's own confirmation, including after independent fast cleanup");
            assertEquals(1, f.nativeMode.swaps);
            assertEquals(1, f.markers(), "A refill needs one post-SWAP snapshot, with no preceding baseline round trip");
            assertEquals(0, f.enginePlacer.availablePistons(), "The native predicted SWAP still cannot authorize world work");
            f.replyToSnapshot();
            assertFalse(f.enginePlacer.isInventorySettling());
            assertEquals(64, f.enginePlacer.availablePistons());
        } finally {
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(enabled);
        }
    }

    private static final class Network implements VoidWarehouseRestockPort {
        int sends;
        int status = PrinterTakeResultPayload.NONE;
        List<String> candidates = List.of();
        @Override public boolean isSupported() { return true; }
        @Override public int requestTake(List<String> candidates) {
            this.candidates = List.copyOf(candidates);
            status = PrinterTakeResultPayload.NONE;
            return ++sends;
        }
        @Override public int statusOf(int nonce) { return nonce == sends ? status : PrinterTakeResultPayload.NONE; }
        @Override public void forget() { status = PrinterTakeResultPayload.NONE; }
    }

    private static final class NativeMode extends BedrockPlacementRegressionTest.TestGameMode {
        BedrockPlacementRegressionTest.TestPlayer serverPlayer;
        int swaps;
        @Override public void handleContainerInput(int containerId, int slot, int button, ContainerInput input, Player player) {
            assertEquals(ContainerInput.SWAP, input);
            assertEquals(40, button);
            swaps++;
            player.inventoryMenu.clicked(slot, button, input, player);
            serverPlayer.inventoryMenu.clicked(slot, button, input, serverPlayer);
        }
    }

    private static final class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final Network network = new Network();
        final MaterialRequestCoordinator warehouseRequests;
        final BedrockInventorySession inventory;
        final BedrockPlacementRegressionTest.TestPlayer serverPlayer;
        final NativeMode nativeMode;

        Fixture() throws Exception {
            inventory = (BedrockInventorySession) get(enginePlacer, BedrockPlacer.class, "inventory");
            warehouseRequests = new MaterialRequestCoordinator(List.of(new PlayerInventoryProvider(client),
                    new VoidWarehouseRestockAdapter(broker, network)));
            set(runtime, PrinterRuntime.class, "materialRequests", warehouseRequests);
            serverPlayer = allocate(BedrockPlacementRegressionTest.TestPlayer.class);
            serverPlayer.inventory = new Inventory(serverPlayer, new EntityEquipment());
            set(serverPlayer, Player.class, "inventory", serverPlayer.inventory);
            set(serverPlayer, Player.class, "abilities", new Abilities());
            set(serverPlayer, Player.class, "inventoryMenu", new InventoryMenu(serverPlayer.inventory, false, serverPlayer));
            serverPlayer.containerMenu = serverPlayer.inventoryMenu;
            nativeMode = allocate(NativeMode.class);
            nativeMode.serverPlayer = serverPlayer;
            nativeMode.hits = mode.hits;
            nativeMode.predictions = mode.predictions;
            client.gameMode = nativeMode;
        }

        void fastCleanupWithMissingTorch() throws Exception {
            cleanupWorld();
            setBreakSpeed(4.5D); // Native piston hardness 1.5: delta 0.8 emits START + STOP.
            var piston = Blocks.PISTON.defaultBlockState();
            assertEquals(0.8F, piston.getDestroyProgress(client.player, client.level, RESIDUE), 0.00001F);
            assertEquals(0.8F, PlayerUtils.getDestroyProgress(client.player, piston, client.player.getMainHandItem()), 0.00001F);
            level.blocks.remove(TARGET.above());
            client.player.getInventory().setItem(1, ItemStack.EMPTY);
            var tool = client.player.getMainHandItem();
            tool.setDamageValue(tool.getMaxDamage() - 10);
            BedrockInventoryTestAccess.synchronizeInventory(enginePlacer, client);
            copyToServer();
            int initialToolUses = enginePlacer.availableToolUses();
            assertEquals(10, initialToolUses);

            level.blocks.put(RESIDUE, piston);
            var cleanup = (BedrockCleanupCoordinator) get(engine, BedrockEngine.class, "cleanup");
            cleanup.enqueueCycle(RESIDUE.below(), Set.of(RESIDUE), false);
            transport.packets.clear();
            tick(1L);
            engine.observeSupplies(warehouseRequests);
            engine.beginTick(true);

            var digs = transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).filter(packet -> packet.getPos().equals(RESIDUE)).toList();
            assertEquals(List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK), digs.stream().map(ServerboundPlayerActionPacket::getAction).toList());
            assertEquals(initialToolUses - 1, enginePlacer.availableToolUses());
            assertFalse(enginePlacer.hasActiveCleanupMining());
            assertEquals(Boolean.TRUE, get(inventory, BedrockInventorySession.class, "rawDirty"));
            assertEquals(Boolean.FALSE, get(inventory, BedrockInventorySession.class, "countDirty"));
            assertEquals(0, markers());
            var serverTool = serverPlayer.inventory.getItem(0);
            serverTool.setDamageValue(serverTool.getDamageValue() + 1);
        }

        void copyToServer() {
            for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
                serverPlayer.inventory.setItem(slot, client.player.getInventory().getItem(slot).copy());
            }
        }

        void applyServerContents() {
            client.player.inventoryMenu.initializeContents(client.player.inventoryMenu.getStateId() + 1,
                    serverPlayer.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
        }

        void replyToSnapshot() {
            applyServerContents();
            InventoryRecoveryFence.received(transport);
        }

        long markers() { return transport.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count(); }
    }
}
//#endif
