//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.interaction.ToolSwitchService;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Ordered native snapshots renew conservative budgets without owning the world's mutation lane. */
class BedrockInventoryRefreshTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    private static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner, args);
    }

    private static void maintain(Object session, int reserve) throws Exception {
        call(session, "maintainInventory", new Class<?>[]{int.class}, reserve);
    }

    private static void synchronizedInventory(Object session, Object connection) throws Exception {
        call(session, "onInventorySynchronized", new Class<?>[]{Object.class}, connection);
    }

    private static int available(Object session, int slot) throws Exception {
        return (int) call(session, "availableCount", new Class<?>[]{int.class}, slot);
    }

    private static boolean settling(Object session) throws Exception {
        return (boolean) call(session, "isSettling", new Class<?>[0]);
    }

    private static void raw(Object session, int pistons, int torchSlot, int torches) throws Exception {
        call(session, "recordRawUses", new Class<?>[]{int.class, int.class, int.class}, pistons, torchSlot, torches);
    }

    private static void toolUse(Object session) throws Exception {
        call(session, "recordToolUse", new Class<?>[0]);
    }

    private static void installGuard(BedrockInventorySessionTest.Fixture f, int threshold) throws Exception {
        var guard = new InventorySwitchGuard(f.client, f.runtime::currentTick);
        var policy = (TweakerooToolSwitchPort) Proxy.newProxyInstance(BedrockInventoryRefreshTest.class.getClassLoader(),
                new Class<?>[]{TweakerooToolSwitchPort.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isDurabilityGuardActive" -> true;
                    case "isCurrentToolUsable" -> {
                        var candidate = (ItemStack) args[0];
                        yield candidate.getMaxDamage() - candidate.getDamageValue() > threshold;
                    }
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
        set(f.runtime, PrinterRuntime.class, "toolSwitchService", new ToolSwitchService(f.client, policy, guard));
    }

    private static Object recoveredPistons(BedrockInventorySessionTest.Fixture f) throws Exception {
        f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 12));
        Object session = f.session();
        f.validateInitial(session);
        raw(session, 8, -1, 0);
        f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), 48);
        assertEquals(4, available(session, 40), "A newer-looking slot alone still cannot mint placement allowance");
        return session;
    }

    @Test void recoveredMaterialsRefreshInTheBackgroundAndCaptureTheExactOrderedReply() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Object session = recoveredPistons(f);

            maintain(session, 4);

            assertEquals(2, f.markers(), "Low conservative allowance with visible recovery starts one background snapshot");
            assertFalse(settling(session), "Background calibration must not pause unrelated world work");
            assertTrue((boolean) call(session, "preparePistons", new Class<?>[0]));
            raw(session, 2, -1, 0);
            maintain(session, 4);
            assertEquals(2, f.markers(), "All low-budget observations share the outstanding background request");
            assertEquals(2, available(session, 40));
            f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), 49);
            f.acknowledge();
            synchronizedInventory(session, f.transport);
            // An older inventory packet arriving later cannot become the lazy refresh baseline.
            f.apply(f.contents(new ItemStack(Items.PISTON, 32), ItemStack.EMPTY), 47);
            assertEquals(10, available(session, 40), "The exact Stats reply captures native 12 minus two later USEs");
            assertFalse(settling(session));
            assertEquals(2, f.markers());
        }
    }

    @Test void backgroundRefreshSubtractsBothPredictedMaterialAndToolCostsAfterItsMarker() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 10);
            f.player().getInventory().setItem(0, tool);
            f.player().getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 8));
            Object session = f.session();
            f.validateInitial(session);
            raw(session, 0, 6, 6);
            for (int i = 0; i < 7; i++) toolUse(session);
            f.apply(f.contents(f.player().getInventory().getItem(40).copy(), new ItemStack(Items.REDSTONE_TORCH, 8)), 48);

            maintain(session, 4);

            assertEquals(2, f.markers());
            InventoryUtils.setSelectedSlot(f.player().getInventory(), 6);
            f.player().getInventory().getItem(6).shrink(1); // Native local prediction has already charged this use.
            call(session, "recordPredictedUse", new Class<?>[0]);
            InventoryUtils.setSelectedSlot(f.player().getInventory(), 0);
            toolUse(session);
            toolUse(session);
            f.acknowledge();
            synchronizedInventory(session, f.transport);

            assertEquals(6, available(session, 6), "Visible seven already contains prediction; subtracting its post-marker cost stays conservative");
            assertEquals(8, call(session, "availableToolUses", new Class<?>[0]), "Unconfirmed two later hard STARTs cannot be credited back");
            assertEquals(10, f.hand().getMaxDamage() - f.hand().getDamageValue());
            assertFalse(settling(session));
        }
    }

    @Test void aHighDurabilityGuardRefreshesBeforeItsRemainingUsableBufferIsExhausted() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 36);
            f.player().getInventory().setItem(0, tool);
            installGuard(f, 32);
            Object session = f.session();
            f.validateInitial(session);
            toolUse(session);
            toolUse(session);
            assertEquals(34, call(session, "availableToolUses", new Class<?>[0]));
            assertTrue((boolean) call(session, "canReserveToolUses", new Class<?>[]{int.class}, 2));

            maintain(session, 12);

            assertEquals(2, f.markers(), "The guard's usable allowance, rather than absolute tool durability, owns the refresh lead time");
            assertFalse(settling(session));
            assertTrue((boolean) call(session, "canReserveToolUses", new Class<?>[]{int.class}, 2),
                    "An early request must leave the already approved tool uses available");
            maintain(session, 12);
            assertEquals(2, f.markers());
            toolUse(session);
            toolUse(session);
            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertEquals(34, call(session, "availableToolUses", new Class<?>[0]),
                    "Two uses after the marker remain charged against its native durability reply");
            assertEquals(36, f.hand().getMaxDamage() - f.hand().getDamageValue(), "Guard projections cannot mutate the real tool");
        }
    }

    @ParameterizedTest @CsvSource({"36, 7, 31, 32, 29, false", "8, 5, 4, 5, 3, false", "8, 5, 4, 5, 3, true"})
    void anActuallyUnsafeVisibleToolDoesNotOwnTheRefreshDecisionForRecoveredMaterials(
            int initialUses, int costs, int visibleUses, int threshold, int remainingBudget, boolean recoveredMaterials) throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - initialUses);
            f.player().getInventory().setItem(0, tool);
            installGuard(f, threshold);
            Object session;
            if (recoveredMaterials) session = recoveredPistons(f);
            else {
                session = f.session();
                f.validateInitial(session);
            }
            for (int use = 0; use < costs; use++) toolUse(session);
            f.hand().setDamageValue(f.hand().getMaxDamage() - visibleUses);
            assertEquals(visibleUses, f.hand().getMaxDamage() - f.hand().getDamageValue());
            assertEquals(remainingBudget, call(session, "availableToolUses", new Class<?>[0]));

            maintain(session, 12);

            assertEquals(recoveredMaterials ? 2 : 1, f.markers(),
                    "An unsafe tool cannot delay its real switch with calibration, while recovered materials retain their independent background refresh");
            assertFalse(settling(session));
        }
    }

    @Test void aBackgroundReplyKeepsAnEmptyEngineBusyWithoutOwningTheWorldMutationFence() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 12));
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            Object session = get(placer, BedrockPlacer.class, "inventory");
            f.validateInitial(session);
            raw(session, 8, -1, 0);
            f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), 48);
            maintain(session, 4);
            assertEquals(2, f.markers());
            assertFalse(placer.isInventorySettling());
            assertTrue(placer.isInventoryPreparationPending());

            assertTrue(engine.hasActiveWork(), "External inventory automation must wait for the pending baseline even after the last cycle has retired");

            f.acknowledge();
            engine.onRuntimeEvent(new RuntimeEvent.InventorySynchronized(f.transport));
            assertFalse(placer.isInventoryPreparationPending());
            assertFalse(engine.hasActiveWork());
        }
    }

    @Test void anOlderStatsReplyCannotCompleteTheBackgroundSnapshot() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Object session = recoveredPistons(f);
            InventoryRecoveryFence.sent(f.transport,
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));

            maintain(session, 4);

            assertEquals(2, f.markers());
            f.acknowledge(); // The older Stats-screen request's reply.
            synchronizedInventory(session, f.transport);
            assertEquals(4, available(session, 40));
            assertFalse(settling(session));
            raw(session, 1, -1, 0);
            f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), 49);
            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertEquals(11, available(session, 40));
        }
    }

    @Test void aRealRelativeSwapWaitsForTheBackgroundReplyWithoutReplacingItsTicket() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(11, new ItemStack(Items.REDSTONE_TORCH, 8));
            Object session = recoveredPistons(f);
            maintain(session, 4);
            assertEquals(2, f.markers());

            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{Item.class}, Items.REDSTONE_TORCH));

            assertEquals(0, f.actions.materialMoves);
            assertEquals(2, f.markers(), "No second prepared ticket can overtake the background marker's ordered send");
            assertFalse(settling(session));
            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{Item.class}, Items.REDSTONE_TORCH));
            assertEquals(1, f.actions.materialMoves);
            assertEquals(3, f.markers());
            assertTrue(settling(session), "The actual menu mutation still owns a strong inventory fence");
            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertFalse(settling(session));
        }
    }

    @Test void anOutstandingBackgroundRefreshOwnsReconciliationBeforeAnotherProviderStarts() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Object session = recoveredPistons(f);
            maintain(session, 4);

            assertTrue((boolean) call(session, "reconcileInventoryIfDirty", new Class<?>[0]),
                    "The engine must wait for this real reconciliation before requesting an external inventory provider");

            assertFalse(settling(session), "Waiting to choose another inventory owner must not block unrelated world actions");
            assertEquals(2, f.markers());
        }
    }

    @Test void aStrongMaterialSnapshotCompletesItsOwnGenericSwitchReceiptBeforeAnotherHandIsSelected() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var guard = new InventorySwitchGuard(f.client, () -> 0);
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            f.actions.markMaterialGuard = true;
            Object session = f.session();
            f.validateInitial(session);
            f.player().getInventory().setItem(11, new ItemStack(Items.REDSTONE_TORCH, 8));
            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{Item.class}, Items.REDSTONE_TORCH));
            assertTrue(guard.isWaiting());

            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertFalse(settling(session));
            InventoryUtils.setSelectedSlot(f.player().getInventory(), 0);

            assertFalse(guard.isWaiting(), "An actual ordered inventory reply supersedes the same click's generic time window");
        }
    }

    @Test void aStrongSnapshotCannotClearANewerToolsGenericSwitchReceipt() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            var guard = new InventorySwitchGuard(f.client, () -> 0);
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            f.actions.markMaterialGuard = true;
            Object session = f.session();
            f.validateInitial(session);
            f.player().getInventory().setItem(11, new ItemStack(Items.REDSTONE_TORCH, 8));
            assertFalse((boolean) call(session, "prepareMaterial", new Class<?>[]{Item.class}, Items.REDSTONE_TORCH));
            guard.markSwitchIfNeeded(new ItemStack(Items.DIAMOND_PICKAXE)); // A genuinely later switch owns a different generation.

            f.acknowledge();
            synchronizedInventory(session, f.transport);
            assertFalse(settling(session));

            assertTrue(guard.isWaiting(), "Completing the older material transaction cannot approve a newer tool switch");
        }
    }

    @Test void lowActualStockWithoutAnyVisibleRecoveryDoesNotPollEveryTick() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 3));
            Object session = f.session();
            f.validateInitial(session);
            raw(session, 1, -1, 0);

            for (int i = 0; i < 20; i++) maintain(session, 12);

            assertEquals(1, f.markers(), "Low stock alone is not evidence that a background snapshot can restore allowance");
            assertEquals(2, available(session, 40));
        }
    }

    @Test void repeatedNativeRepliesRenewMoreThanAStackOfRawCostsWithoutBlockingWorldWork() throws Exception {
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            f.player().getInventory().setItem(40, new ItemStack(Items.PISTON, 12));
            Object session = f.session();
            f.validateInitial(session);
            try (var subscription = f.runtime.events().subscribe(event -> {
                if (event instanceof RuntimeEvent.InventorySynchronized synchronizedInventory) {
                    try { synchronizedInventory(session, synchronizedInventory.connection()); }
                    catch (Exception error) { throw new AssertionError(error); }
                }
            })) {
                int rawCosts = 0;
                int backgroundReplies = 0;
                int revision = 48;
                for (int group = 0; group < 30; group++) {
                    assertTrue(available(session, 40) >= 4);
                    raw(session, 4, -1, 0);
                    rawCosts += 4;
                    // Apply a vanilla player-menu reply after recovered materials were picked up.
                    f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), revision++);
                    long before = f.markers();
                    maintain(session, 4);
                    assertFalse(settling(session));
                    if (f.markers() > before) {
                        assertEquals(before + 1, f.markers());
                        raw(session, 2, -1, 0); // These USEs are ordered after this background marker.
                        rawCosts += 2;
                        assertFalse(settling(session));
                        if (available(session, 40) == 0) {
                            assertFalse((boolean) call(session, "preparePistons", new Class<?>[0]));
                            assertEquals(before + 1, f.markers(), "Exhausting old allowance cannot start another competing ticket");
                        }
                        f.apply(f.contents(new ItemStack(Items.PISTON, 12), ItemStack.EMPTY), revision++);
                        f.acknowledge();
                        f.runtime.events().publish(new RuntimeEvent.InventorySynchronized(f.transport));
                        backgroundReplies++;
                        assertEquals(10, available(session, 40), "Both post-marker USEs stay charged when this snapshot is applied");
                        f.apply(f.contents(new ItemStack(Items.PISTON, 32), ItemStack.EMPTY), 47);
                        assertEquals(10, available(session, 40), "An old count after the Stats event cannot refill the captured baseline");
                    }
                    assertFalse(settling(session), "Normal recovery never owns the global inventory mutation fence");
                }
                assertTrue(rawCosts > 64);
                assertTrue(backgroundReplies > 1);
                assertTrue(backgroundReplies < 30, "A healthy allowance cannot request a snapshot after every group");
                assertEquals(1 + backgroundReplies, f.markers());
            }
        }
    }
}
//#endif
