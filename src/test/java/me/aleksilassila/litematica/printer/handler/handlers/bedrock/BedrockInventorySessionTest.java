//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real menu snapshots and response ordinals; inventory actions are a narrow test port. */
class BedrockInventorySessionTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    private static Object call(Object session, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = session.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(session, args);
    }

    private static boolean pistons(Object session) throws Exception {
        return (boolean) call(session, "preparePistons", new Class<?>[0]);
    }

    private static boolean settling(Object session) throws Exception {
        return (boolean) call(session, "isSettling", new Class<?>[0]);
    }

    private static boolean material(Object session, Item item) throws Exception {
        return (boolean) call(session, "prepareMaterial", new Class<?>[]{Item.class}, item);
    }

    private static boolean tool(Object session, boolean cleanup) throws Exception {
        return (boolean) call(session, "prepareTool", new Class<?>[]{BlockState.class, boolean.class},
                Blocks.PISTON.defaultBlockState(), cleanup);
    }

    private static void preparationSent(Object session, boolean changedMainHand) throws Exception {
        var method = assertDoesNotThrow(() -> session.getClass().getDeclaredMethod("onInitialPreparationSent",
                boolean.class), "Powered preparation must own its equipment-tick confirmation");
        method.setAccessible(true);
        method.invoke(session, changedMainHand);
    }

    private static int lastEquipmentMarker(Fixture f) {
        return f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                .map(ServerboundUseItemPacket.class::cast)
                .mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aRealInventoryMoveAndRestoredEquipmentKeepTheirIndependentBoundaries(boolean inventoryFirst) throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            f.validateInitial(session);
            assertTrue(tool(session, false));
            assertFalse((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]));
            int oldMarker = lastEquipmentMarker(f);
            call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, oldMarker);
            assertFalse(settling(session));
            preparationSent(session, true);
            assertEquals(oldMarker, lastEquipmentMarker(f), "Preparation records the hand change; the dispatcher chooses the warmup phase");
            call(session, "prewarmCriticalTool", new Class<?>[0]);
            f.preparationFence(session);

            int newMarker = lastEquipmentMarker(f);
            assertTrue(newMarker > oldMarker, "Restoring the same final hotbar signature cannot reuse pre-Torch confirmation");
            call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, oldMarker);
            assertTrue(settling(session));
            if (inventoryFirst) f.acknowledge();
            else call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, newMarker);
            assertEquals(!inventoryFirst, settling(session), "Only the real inventory transaction owns the inventory boundary");
            assertEquals(inventoryFirst, call(session, "isToolSettling", new Class<?>[0]));
            assertFalse((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]),
                    "A critical break still requires both the real SWAP and fresh equipment confirmation");
            if (inventoryFirst) call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, newMarker);
            else f.acknowledge();
            assertFalse(settling(session));
            assertTrue((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]));
        }
    }

    @Test void ordinaryPoweredPreparationWaitsOnlyForFreshEquipmentWithoutRequestingAMenuReply() throws Exception {
        try (var f = new Fixture()) {
            f.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var session = f.session();
            f.validateInitial(session);
            assertTrue(tool(session, false));
            assertFalse((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]));
            int oldMarker = lastEquipmentMarker(f);
            call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, oldMarker);

            preparationSent(session, true);
            assertEquals(oldMarker, lastEquipmentMarker(f), "Material preparation must not select the tool on its own");
            call(session, "prewarmCriticalTool", new Class<?>[0]);

            int marker = lastEquipmentMarker(f);
            assertTrue(marker > oldMarker);
            assertFalse(settling(session), "A real equipment wait does not block unrelated inventory-free cleanup");
            assertTrue((boolean) call(session, "isToolSettling", new Class<?>[0]));
            assertEquals(1, f.markers(), "Only initial calibration requested a full menu reply");
            call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, oldMarker);
            assertFalse((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]));
            call(session, "acknowledgeToolTick", new Class<?>[]{Object.class, int.class}, f.transport, marker);
            assertTrue((boolean) call(session, "prepareCriticalToolTick", new Class<?>[0]));
        }
    }

    @Test void preparationWarmupCannotMoveAnInventoryToolThroughItsOutstandingSnapshot() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.validateInitial(session);
            f.player().getInventory().setItem(11, new ItemStack(Items.DIAMOND_PICKAXE));
            f.actions.toolSource = 11;
            f.preparationFence(session);
            int before = f.transport.packets.size();

            preparationSent(session, true);
            call(session, "prewarmCriticalTool", new Class<?>[0]);

            assertEquals(0, f.actions.toolMoves);
            assertEquals(before, f.transport.packets.size());
            assertTrue(settling(session));
            f.acknowledge();
            assertFalse(tool(session, false));
            assertEquals(1, f.actions.toolMoves);
            assertTrue(settling(session));
        }
    }

    @Test void lateFullContentsCannotCauseASecondRelativeOffhandSwapBeforeItsOwnReply() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.validateInitial(session);
            var stale = f.contents(new ItemStack(Items.REDSTONE_TORCH, 55), new ItemStack(Items.PISTON, 38));
            f.apply(stale, 48);

            assertFalse(pistons(session));
            assertEquals(1, f.actions.offhandSwaps);
            assertTrue(f.player().getInventory().getItem(40).is(Items.PISTON));
            int packetsAfterSwap = f.transport.packets.size();
            // The real vanilla update path overwrites both slots without a revision freshness check.
            f.apply(stale, 48);
            for (int attempt = 0; attempt < 4; attempt++) assertFalse(pistons(session));
            assertEquals(1, f.actions.offhandSwaps, "An older snapshot must not provoke the inverse SWAP");
            assertEquals(packetsAfterSwap, f.transport.packets.size(), "The outstanding marker cannot be replaced");

            f.apply(f.contents(new ItemStack(Items.PISTON, 38), new ItemStack(Items.REDSTONE_TORCH, 50)), 51);
            InventoryRecoveryFence.received(f.transport);
            assertFalse(settling(session));
            assertTrue(pistons(session));
            assertEquals(1, f.actions.offhandSwaps);
            assertEquals(2, f.markers(), "One initial verification and one actual SWAP confirmation");
        }
    }

    @Test void anExistingPredictedPistonNeedsOneInitialAuthoritativeSnapshot() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            assertFalse(pistons(session));
            assertTrue(settling(session));
            assertEquals(0, f.actions.offhandSwaps);
            assertEquals(1, f.markers());
            f.acknowledge();
            assertTrue(pistons(session));
            assertTrue(pistons(session));
            assertEquals(1, f.markers(), "Completing a fence must retain the initial verification");
        }
    }

    @Test void aDirtyEmptyOffhandRefillsFromItsCalibratedSlotBeforeTheSingleOrderedSnapshot() throws Exception {
        try (var f = new Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 2));
            f.player().getInventory().setItem(10, new ItemStack(Items.PISTON, 32));
            var session = f.session();
            f.validateInitial(session);
            call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, 2, -1, 0);
            assertTrue(f.player().getOffhandItem().isEmpty());
            InventoryRecoveryFence.sent(f.transport,
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));

            assertFalse(pistons(session));

            assertEquals(32, f.player().getOffhandItem().getCount(),
                    "The calibrated absolute source can be swapped before the reply, without a preliminary empty snapshot");
            assertTrue(f.player().getInventory().getItem(10).isEmpty());
            assertEquals(List.of(10), f.actions.absoluteOffhandSources);
            assertEquals(2, f.markers(), "Initial calibration plus one SWAP confirmation, with no extra dirty-baseline round trip");
            assertEquals(0, call(session, "availablePistons", new Class<?>[0]),
                    "Predicted refill must not authorize any world use before its own fence");
            f.acknowledge(); // The older Stats request does not confirm this SWAP.
            assertTrue(settling(session));
            assertFalse(pistons(session));
            assertEquals(List.of(10), f.actions.absoluteOffhandSources, "A pending refill cannot send an inverse SWAP");

            // The last raw USE was rejected: the server still had one piston in the offhand.
            // Its native SWAP returns that piston to the source instead of losing it.
            var serverReply = new ArrayList<>(f.player().inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList());
            serverReply.set(10, new ItemStack(Items.PISTON, 1));
            f.apply(serverReply, 51);
            assertEquals(0, call(session, "availablePistons", new Class<?>[0]));
            InventoryRecoveryFence.received(f.transport);

            assertFalse(settling(session));
            assertTrue(pistons(session));
            assertEquals(32, call(session, "availablePistons", new Class<?>[0]));
            assertEquals(1, call(session, "availableCount", new Class<?>[]{int.class}, 10),
                    "Only the real post-SWAP snapshot may restore the server's rejected raw cost");
            assertEquals(2, f.markers());
        }
    }

    @Test void aDirtyRefillCannotTrustPistonsWhichReplacedAnotherCalibratedItem() throws Exception {
        try (var f = new Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 1));
            f.player().getInventory().setItem(10, new ItemStack(Items.STONE, 32));
            var session = f.session();
            f.validateInitial(session);
            call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, 1, -1, 0);
            f.player().getInventory().setItem(10, new ItemStack(Items.PISTON, 32));

            assertFalse(pistons(session));

            assertTrue(f.player().getOffhandItem().isEmpty(), "A count budget for stone cannot authorize a piston source");
            assertTrue(f.actions.absoluteOffhandSources.isEmpty());
            assertTrue(settling(session));
            assertEquals(2, f.markers());
        }
    }

    @ParameterizedTest @CsvSource({"3, 39", "4, 40", "10, 10"})
    void theRuntimeRefillUsesTheFrozenPlayerMenuSlotAndOffhandButton(int source, int expectedMenuSlot) throws Exception {
        try (var f = new Fixture()) {
            var mode = f.allocate(RefillGameMode.class);
            f.client.gameMode = mode;
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 2));
            f.player().getInventory().setItem(source, new ItemStack(Items.PISTON, 32));
            var session = new BedrockInventorySession(f.client);
            f.validateInitial(session);
            session.recordRawUses(2, -1, 0);

            assertFalse(session.preparePistons());

            assertEquals(1, mode.calls, "The production inventory adapter must issue exactly one native SWAP");
            assertEquals(expectedMenuSlot, mode.menuSlot);
            assertEquals(40, mode.button, "Button 40 addresses the offhand inventory slot; its menu slot is 45");
            assertEquals(ContainerInput.SWAP, mode.input);
            assertEquals(32, f.player().getOffhandItem().getCount());
            assertTrue(f.player().getInventory().getItem(source).isEmpty());
            assertEquals(0, session.availablePistons());
            assertTrue(session.isSettling());
            f.acknowledge();
            assertFalse(session.isSettling());
            assertEquals(32, session.availablePistons());
            assertEquals(2, f.markers());
        }
    }

    @Test void aDirtyNonemptyOffhandStillConfirmsItsBaselineBeforeAnyRelativeSwap() throws Exception {
        try (var f = new Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 1));
            f.player().getInventory().setItem(10, new ItemStack(Items.PISTON, 32));
            var session = f.session();
            f.validateInitial(session);
            call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, 1, -1, 0);
            f.player().getInventory().setItem(40, new ItemStack(Items.REDSTONE_TORCH, 8));

            assertFalse(pistons(session));

            assertTrue(f.player().getOffhandItem().is(Items.REDSTONE_TORCH));
            assertEquals(0, f.actions.offhandSwaps);
            assertTrue(f.actions.absoluteOffhandSources.isEmpty());
            assertTrue(settling(session));
        }
    }

    @Test void aDirtyRefillCannotReuseTheCountOfAPistonWhoseComponentsChanged() throws Exception {
        try (var f = new Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 1));
            f.player().getInventory().setItem(10, new ItemStack(Items.PISTON, 32));
            var session = f.session();
            f.validateInitial(session);
            call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, 1, -1, 0);
            f.player().getInventory().getItem(10).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("Changed piston"));

            assertFalse(pistons(session));

            assertTrue(f.player().getOffhandItem().isEmpty());
            assertTrue(f.actions.absoluteOffhandSources.isEmpty(), "The source's count is bound to its calibrated component identity");
            assertTrue(settling(session));
        }
    }

    @Test void materialSelectionUsesAbsoluteMainHandSlotsAndResendsTheSelectedSlotEveryTime() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.player().getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 32));
            f.validateInitial(session);

            assertTrue(material(session, Items.REDSTONE_TORCH));
            assertTrue(material(session, Items.REDSTONE_TORCH));

            assertTrue(f.hand().is(Items.REDSTONE_TORCH));
            assertTrue(f.player().getInventory().getItem(40).is(Items.PISTON));
            assertEquals(0, f.actions.offhandSwaps);
            assertEquals(2, f.transport.packets.stream().filter(ServerboundSetCarriedItemPacket.class::isInstance).count());
            assertEquals(1, f.markers(), "Absolute hotbar selection needs no inventory-move response");
        }
    }

    @Test void aRealMaterialMoveIsFencedEvenWhenTheSelectorReportsNotReady() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.validateInitial(session);
            f.player().getInventory().setItem(10, new ItemStack(Items.SLIME_BLOCK, 16));
            f.actions.notReadyAfterMaterialMove = true;

            assertFalse(material(session, Items.SLIME_BLOCK));

            assertEquals(1, f.actions.materialMoves);
            assertTrue(f.hand().is(Items.SLIME_BLOCK), "The false return followed an actual predicted move");
            assertTrue(settling(session));
            assertFalse(material(session, Items.SLIME_BLOCK));
            assertEquals(1, f.actions.materialMoves);
            f.acknowledge();
            assertTrue(material(session, Items.SLIME_BLOCK));
            assertTrue(pistons(session));
            assertEquals(2, f.markers());
        }
    }

    @Test void criticalAndCleanupToolMovesWaitForTheirOrderedInventoryReply() throws Exception {
        for (boolean cleanup : new boolean[]{false, true}) {
            try (var f = new Fixture()) {
                var session = f.session();
                f.validateInitial(session);
                f.player().getInventory().setItem(11, new ItemStack(Items.DIAMOND_PICKAXE));
                f.actions.toolSource = 11;

                assertFalse(tool(session, cleanup));
                assertTrue(settling(session));
                assertEquals(1, f.actions.toolMoves);
                assertEquals(cleanup, f.actions.lastCleanup);
                assertFalse(tool(session, cleanup));
                assertEquals(1, f.actions.toolMoves);
                f.acknowledge();
                assertTrue(tool(session, cleanup));
            }
        }
    }

    @Test void cleanupDoesNotRequireHavingAPistonInTheOffhand() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.player().getInventory().setItem(40, ItemStack.EMPTY);
            f.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            assertFalse(tool(session, true));
            f.acknowledge();
            assertTrue(tool(session, true));
            assertEquals(0, f.actions.offhandSwaps);
        }
    }

    @Test void anExternalToolMoveCannotBeMistakenForAnAlreadySettledMatchingTool() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.validateInitial(session);
            f.player().getInventory().setItem(11, new ItemStack(Items.DIAMOND_PICKAXE));
            f.actions.move(11, 0);
            assertTrue(f.hand().is(Items.DIAMOND_PICKAXE));

            call(session, "confirmToolSwitch", new Class<?>[0]);

            assertTrue(settling(session));
            assertFalse(tool(session, true));
            call(session, "confirmToolSwitch", new Class<?>[0]);
            assertEquals(2, f.markers(), "An outstanding external-switch fence cannot be replaced");
            f.acknowledge();
            assertTrue(tool(session, true));
        }
    }

    @Test void changingConnectionOrResettingTheSessionRequiresFreshVerification() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.validateInitial(session);
            var replacement = f.allocate(BedrockPlacementRegressionTest.CaptureTransport.class);
            replacement.packets = new ArrayList<>();
            f.connection.transport = replacement;
            assertFalse(pistons(session));
            assertEquals(2, replacement.packets.size());
            InventoryRecoveryFence.received(replacement);
            assertTrue(pistons(session));
            call(session, "reset", new Class<?>[0]);
            assertFalse(pistons(session));
            assertEquals(4, replacement.packets.size());
        }
    }

    @Test void aCarriedStackBlocksTheInventoryTransactionBeforeSelectionOrPackets() throws Exception {
        try (var f = new Fixture()) {
            var session = f.session();
            f.player().inventoryMenu.setCarried(new ItemStack(Items.STONE));
            assertFalse(pistons(session));
            assertFalse(material(session, Items.REDSTONE_TORCH));
            assertFalse(tool(session, true));
            assertTrue(f.transport.packets.isEmpty());
            assertEquals(0, f.actions.offhandSwaps + f.actions.materialMoves + f.actions.toolMoves);
        }
    }

    private static final class RefillGameMode extends BedrockPlacementRegressionTest.TestGameMode {
        int calls, menuSlot, button;
        ContainerInput input;
        @Override public void handleContainerInput(int containerId, int slot, int button, ContainerInput input, Player player) {
            this.calls++;
            this.menuSlot = slot;
            this.button = button;
            this.input = input;
            assertEquals(player.inventoryMenu.containerId, containerId);
            player.inventoryMenu.clicked(slot, button, input, player);
        }
    }

    static class Actions implements InvocationHandler {
        final Fixture f;
        final List<Integer> absoluteOffhandSources = new ArrayList<>();
        int offhandSwaps, materialMoves, toolMoves;
        int toolSource;
        boolean lastCleanup, notReadyAfterMaterialMove, markMaterialGuard;
        Actions(Fixture fixture) { f = fixture; }
        int source(Item item) {
            for (int i = 0; i < 36; i++) if (f.player().getInventory().getItem(i).is(item)) return i;
            return -1;
        }
        void move(int source, int hotbar) {
            f.player().inventoryMenu.clicked(source < 9 ? source + 36 : source, hotbar,
                    ContainerInput.SWAP, f.player());
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "selectOffhandSlot" -> {
                    int source = (int) args[0];
                    absoluteOffhandSources.add(source);
                    offhandSwaps++;
                    move(source, 40);
                    yield true;
                }
                case "selectOffhand" -> {
                    Item item = (Item) args[0];
                    if (!f.player().getInventory().getItem(40).is(item)) {
                        int source = source(item);
                        if (source < 0) yield false;
                        offhandSwaps++;
                        move(source, 40);
                    }
                    yield true;
                }
                case "materialRequiresInventorySwap" -> {
                    int slot = source((Item) args[0]);
                    yield !f.hand().is((Item) args[0]) && slot >= 9;
                }
                case "selectMaterial" -> {
                    Item item = (Item) args[0];
                    if (f.hand().is(item)) yield true;
                    int slot = source(item);
                    if (slot < 0) yield false;
                    boolean moved = !Inventory.isHotbarSlot(slot);
                    if (moved) {
                        materialMoves++;
                        move(slot, 5);
                        slot = 5;
                        if (markMaterialGuard) me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get()
                                .inventorySwitchGuard().markSwitchIfNeeded(item);
                    }
                    InventoryUtils.setHotbarSlot(slot, f.player().getInventory());
                    yield !moved || !notReadyAfterMaterialMove;
                }
                case "toolRequiresInventorySwap" -> !f.hand().is(Items.DIAMOND_PICKAXE)
                        && !Inventory.isHotbarSlot(toolSource);
                case "selectTool" -> {
                    lastCleanup = (boolean) args[1];
                    if (!f.hand().is(Items.DIAMOND_PICKAXE)) {
                        int slot = toolSource;
                        if (!Inventory.isHotbarSlot(slot)) { toolMoves++; move(slot, 0); slot = 0; }
                        InventoryUtils.setSelectedSlot(f.player().getInventory(), slot);
                    }
                    yield f.hand().is(Items.DIAMOND_PICKAXE);
                }
                default -> throw new AssertionError("Unexpected inventory access: " + method.getName());
            };
        }
    }

    static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final Actions actions = new Actions(this);
        Fixture() throws Exception {
            super();
            set(client.player, Player.class, "abilities", new Abilities());
            var sequence = allocate(BedrockToolSyncTest.SequencedLevel.class);
            sequence.blocks = level.blocks;
            sequence.next = 100;
            set(sequence, net.minecraft.world.level.Level.class, "dimension", net.minecraft.world.level.Level.OVERWORLD);
            client.level = sequence;
        }

        void preparationFence(Object session) throws Exception {
            var fence = me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets.prepareFence(transport);
            assertTrue(me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils.sendPacketBatch(List.of(
                    me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets.createPlayerInventoryFullSync(player()),
                    fence.packet())));
            call(session, "record", new Class<?>[]{me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets.SyncFence.class}, fence);
        }
        Player player() { return client.player; }
        ItemStack hand() { return player().getInventory().getItem(InventoryUtils.getSelectedSlot(player().getInventory())); }
        Object session() throws Exception {
            var type = assertDoesNotThrow(() -> Class.forName(BedrockPlacer.class.getPackageName() + ".BedrockInventorySession"),
                    "Bedrock needs a session that owns confirmed material preparation");
            var access = Class.forName(type.getName() + "$InventoryAccess");
            var constructor = type.getDeclaredConstructor(Minecraft.class, access);
            constructor.setAccessible(true);
            return constructor.newInstance(client, Proxy.newProxyInstance(access.getClassLoader(), new Class<?>[]{access}, actions));
        }
        List<ItemStack> contents(ItemStack offhand, ItemStack source) {
            var contents = new ArrayList<>(player().inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList());
            contents.set(45, offhand);
            contents.set(42, source);
            return contents;
        }
        void apply(List<ItemStack> items, int state) {
            player().inventoryMenu.initializeContents(state, items.stream().map(ItemStack::copy).toList(), ItemStack.EMPTY);
        }
        void acknowledge() {
            player().inventoryMenu.initializeContents(player().inventoryMenu.getStateId() + 1,
                    player().inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            InventoryRecoveryFence.received(transport);
        }
        void validateInitial(Object session) throws Exception {
            assertFalse(pistons(session));
            acknowledge();
            assertTrue(pistons(session));
        }
        long markers() { return transport.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count(); }
    }
}
//#endif
