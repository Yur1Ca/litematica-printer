//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.ConfiguredPlacementSettings;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.HudStatus;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.alloc;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.1 headless regression coverage of the real fill/material selection path. The action port
 * records placement submissions and rejects transport; no Minecraft server is started here.
 */
class FillMaterialRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void returningFromHandheldRepeatedlyRestoresTheUnchangedConfiguredList() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            assertArrayEquals(new Item[]{Items.STONE}, f.fill.getFillModeItemList());
            for (Item held : List.of(Items.DIRT, Items.SAND, Items.DIRT)) {
                f.handheld(new ItemStack(held, 64));
                assertArrayEquals(new Item[]{held}, f.fill.getFillModeItemList());
                f.blocklist("minecraft:stone");
                assertArrayEquals(new Item[]{Items.STONE}, f.fill.getFillModeItemList(),
                        "Returning to BLOCKLIST must discard the material last observed in HANDHELD");
            }
        }
    }

    @Test void returningFromEmptyHandRestoresTheConfiguredList() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.handheld(ItemStack.EMPTY);
            assertArrayEquals(new Item[0], f.fill.getFillModeItemList());
            assertEquals(HudStatus.MAIN_HAND_NO_BLOCK, f.reason());
            f.blocklist("minecraft:stone");
            assertArrayEquals(new Item[]{Items.STONE}, f.fill.getFillModeItemList());
        }
    }

    @Test void returningToAnEmptyListDoesNotReuseTheHandheldMaterial() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist();
            assertArrayEquals(new Item[0], f.fill.getFillModeItemList());
            f.handheld(new ItemStack(Items.DIRT, 64));
            f.blocklist();
            assertArrayEquals(new Item[0], f.fill.getFillModeItemList());
        }
    }

    @Test void listEditsAreObservedBothWithinBlocklistAndWhileHandheldIsActive() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.blocklist("minecraft:sand");
            assertArrayEquals(new Item[]{Items.SAND}, f.fill.getFillModeItemList());
            f.handheld(new ItemStack(Items.DIRT, 64));
            Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:gravel"));
            f.preprocess();
            assertArrayEquals(new Item[]{Items.DIRT}, f.fill.getFillModeItemList());
            f.blocklist("minecraft:gravel");
            assertArrayEquals(new Item[]{Items.GRAVEL}, f.fill.getFillModeItemList());
            f.blocklist();
            assertArrayEquals(new Item[0], f.fill.getFillModeItemList());
        }
    }

    @Test void runtimeResetAfterDisablingDoesNotLeaveHandheldMaterialInBlocklistMode() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.handheld(new ItemStack(Items.DIRT, 64));
            Configs.Core.FILL.setBooleanValue(false);
            // Exercise the fill-owned reset hook called by the runtime's disable/epoch reset.
            invoke(f.fill, "onRuntimeReset");
            Configs.Core.FILL.setBooleanValue(true);
            f.blocklist("minecraft:stone");
            assertArrayEquals(new Item[]{Items.STONE}, f.fill.getFillModeItemList());
        }
    }

    @Test void sandInHandDoesNotPreventSelectingStoneForAHorizontallySupportedTarget() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.hotbar(0, Items.SAND);
            f.hotbar(1, Items.STONE);
            f.execute();
            assertEquals(Items.STONE, f.player.getMainHandItem().getItem());
            f.assertSubmission(Items.STONE);
        }
    }

    @Test void stoneInHandDoesNotAllowUnsupportedSandAfterSelection() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:sand");
            f.hotbar(0, Items.STONE);
            f.hotbar(1, Items.SAND);
            f.execute();
            assertEquals(Items.SAND, f.player.getMainHandItem().getItem());
            assertEquals(HudStatus.FALLING_NO_SUPPORT, f.reason());
            f.assertDeferredWithoutSubmission();
            f.world.belowSupported = true;
            f.execute();
            f.assertSubmission(Items.SAND);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fallingCheckUsesTheSelectedStackWhenTheListAllowsBothMaterials(boolean sandSelected) throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:sand", "minecraft:stone");
            f.hotbar(0, sandSelected ? Items.SAND : Items.STONE);
            f.execute();
            if (sandSelected) {
                assertEquals(HudStatus.FALLING_NO_SUPPORT, f.reason());
                f.assertDeferredWithoutSubmission();
            } else {
                f.assertSubmission(Items.STONE);
            }
        }
    }

    @Test void handheldSandRemainsDeferredUntilSupportAppears() throws Exception {
        try (var f = new Fixture()) {
            f.handheld(new ItemStack(Items.SAND, 64));
            f.execute();
            assertEquals(HudStatus.FALLING_NO_SUPPORT, f.reason());
            f.assertDeferredWithoutSubmission();
            f.world.belowSupported = true;
            f.execute();
            f.assertSubmission(Items.SAND);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingInventorySwitchCannotSubmitPlacementEvenWithAPredictedMatchingHand(boolean handAlreadyMatches)
            throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.hotbar(0, handAlreadyMatches ? Items.STONE : Items.SAND);
            f.hotbar(1, Items.STONE);
            f.switchGuard.markSwitchIfNeeded(Items.STONE);
            assertTrue(f.switchGuard.isWaiting(), "A same-tick local stack is not inventory confirmation");
            f.execute();
            f.assertDeferredWithoutSubmission();
            assertNotEquals(HudStatus.FALLING_NO_SUPPORT, f.reason(),
                    "An unfinished stone selection must not be classified using the old held sand");
            InventoryUtils.setSelectedSlot(f.player.inventory, 1);
            f.world.tick++;
            f.execute();
            f.assertSubmission(Items.STONE);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unavailableOrRetrievingStoneRetainsTheCandidateWithoutSubmittingHeldSand(boolean retrievalPending)
            throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.hotbar(0, Items.SAND);
            f.provider.pending = retrievalPending;
            f.execute();
            f.assertDeferredWithoutSubmission();
            assertEquals(retrievalPending ? HudStatus.WAITING_RETRIEVAL : HudStatus.MISSING_FILL_MATERIAL,
                    f.reason());
            assertNotNull(f.provider.lastRequest, "The requested stone must reach the inventory provider");
            assertEquals(List.of(Items.STONE), f.provider.lastRequest.acceptedItems());
            assertEquals(MaterialRequest.Source.FILL, f.provider.lastRequest.source());
            assertEquals(retrievalPending, f.skip.get());
            f.provider.pending = false;
            f.hotbar(1, Items.STONE);
            f.execute();
            f.assertSubmission(Items.STONE);
        }
    }

    @Test void changingTheListDuringAnInventoryWaitUsesOnlyTheNewMaterialAfterConfirmation() throws Exception {
        try (var f = new Fixture()) {
            f.blocklist("minecraft:stone");
            f.hotbar(0, Items.SAND);
            f.hotbar(1, Items.STONE);
            f.hotbar(2, Items.GRAVEL);
            f.switchGuard.markSwitchIfNeeded(Items.STONE);
            f.blocklist("minecraft:gravel");
            f.execute();
            f.assertDeferredWithoutSubmission();
            InventoryUtils.setSelectedSlot(f.player.inventory, 1);
            f.world.tick++;
            f.execute();
            assertEquals(Items.GRAVEL, f.player.getMainHandItem().getItem());
            assertEquals(HudStatus.FALLING_NO_SUPPORT, f.reason());
            f.assertDeferredWithoutSubmission();
            f.world.belowSupported = true;
            f.execute();
            f.assertSubmission(Items.GRAVEL);
        }
    }

    static class InventoryPlayer extends MiningInteractionRegressionTest.Player {
        @Override public ItemStack getMainHandItem() {
            return inventory.getItem(InventoryUtils.getSelectedSlot(inventory));
        }
    }

    static class FillLevel extends MiningInteractionRegressionTest.Level {
        boolean belowSupported;
        @Override public BlockState getBlockState(BlockPos pos) {
            return pos.equals(TARGET.relative(Direction.NORTH)) || belowSupported && pos.equals(TARGET.below())
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
    }

    static class Provider implements InventoryProvider {
        boolean pending;
        MaterialRequest lastRequest;
        @Override public String id() { return "headless-fill-materials"; }
        @Override public MaterialReservation request(MaterialRequest request) {
            lastRequest = request;
            return new MaterialReservation(request.token(), pending
                    ? MaterialReservation.State.PENDING : MaterialReservation.State.UNAVAILABLE);
        }
        @Override public MaterialReservation status(MaterialRequest request) { return request(request); }
    }

    static class Fixture implements AutoCloseable {
        final Object oldInventoryClient = get(null, InventoryUtils.class, "client");
        final IConfigOptionListEntry oldMode = Configs.Fill.FILL_BLOCK_MODE.getOptionListValue();
        final IConfigOptionListEntry oldFacing = Configs.Fill.FILL_BLOCK_FACING.getOptionListValue();
        final List<String> oldList = List.copyOf(Configs.Fill.FILL_BLOCK_LIST.getStrings());
        final boolean oldFalling = Configs.Placement.FALLING_CHECK.getBooleanValue();
        final boolean oldAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        final boolean oldFill = Configs.Core.FILL.getBooleanValue();
        final MiningInteractionRegressionTest.Fixture base = new MiningInteractionRegressionTest.Fixture(1, false);
        final FillHandler fill = alloc(FillHandler.class);
        final InventoryPlayer player = alloc(InventoryPlayer.class);
        final FillLevel world = alloc(FillLevel.class);
        final HudStatsManager hud = new HudStatsManager(base.client, () -> world.tick, null);
        final InventorySwitchGuard switchGuard = new InventorySwitchGuard(base.client, () -> world.tick);
        final Provider provider = new Provider();
        final Set<BlockPos> retries = new LinkedHashSet<>();
        final AtomicReference<Boolean> skip = new AtomicReference<>(false);
        int queued;
        int sendAttempts;
        Item submittedItem;
        Item[] expectedItems;
        BlockPos clickTarget;
        ActionPort.ActionSource source;

        Fixture() throws Exception {
            Configs.Placement.FALLING_CHECK.setBooleanValue(true);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
            Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(FillModeFacingType.NORTH);
            player.inventory = base.player.inventory;
            player.abilities = base.player.abilities;
            set(player, LocalPlayer.class, "connection", base.connection);
            base.player = player;
            base.client.player = player;
            base.client.level = world;
            world.tick = 1;
            set(base.client, Minecraft.class, "gui", alloc(Gui.class));
            set(null, InventoryUtils.class, "client", base.client);
            set(RuntimeAccess.get(), PrinterRuntime.class, "inventorySwitchGuard", switchGuard);
            set(RuntimeAccess.get(), PrinterRuntime.class, "materialRequests",
                    new MaterialRequestCoordinator(List.of(provider)));
            ActionPort actions = (ActionPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ActionPort.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isResourceHeld": return provider.pending && args[0] == ResourceLease.INVENTORY;
                            case "queueClick":
                                queued++;
                                clickTarget = (BlockPos) args[0];
                                expectedItems = ((Item[]) args[5]).clone();
                                source = (ActionPort.ActionSource) args[6];
                                submittedItem = player.getMainHandItem().getItem();
                                return true;
                            case "sendQueue":
                                sendAttempts++;
                                return ActionPort.SendResult.INTERACTION_REJECTED;
                            default:
                                if (method.getReturnType() == boolean.class) return false;
                                return null;
                        }
                    });
            set(fill, FillHandler.class, "placementSettings", new ConfiguredPlacementSettings());
            set(fill, FillHandler.class, "fillCacheBlocklist", List.of());
            set(fill, FillHandler.class, "replaceableListCache", List.of());
            set(fill, FillHandler.class, "replaceableFilters", new String[0]);
            set(fill, FillHandler.class, "fillModeItemList", new Item[0]);
            set(fill, FillHandler.class, "observedFillScanConfigHash", Integer.MIN_VALUE);
            set(fill, FillHandler.class, "retryTargets", retries);
            set(fill, FillHandler.class, "inFlightTargets", new LinkedHashSet<>());
            set(fill, FillHandler.class, "inFlightSince", new HashMap<>());
            set(fill, FeatureModuleBase.class, "id", FillHandler.NAME);
            set(fill, FeatureModuleBase.class, "mc", base.client);
            set(fill, FeatureModuleBase.class, "player", player);
            set(fill, FeatureModuleBase.class, "hudStats", hud);
            set(fill, FeatureModuleBase.class, "actionBroker", actions);
            set(fill, FeatureModuleBase.class, "missingMaterials", new MissingMaterialTracker());
            var scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> null, new ScanCache());
            set(fill, FeatureModuleBase.class, "scanEngine", scan);
            var coordinator = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleScanCoordinator")
                    .getDeclaredConstructors()[0];
            coordinator.setAccessible(true);
            set(fill, FeatureModuleBase.class, "scanCoordinator", coordinator.newInstance(null, null, scan));
        }

        void blocklist(String... entries) throws Exception {
            Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of(entries));
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
            preprocess();
        }
        void handheld(ItemStack stack) throws Exception {
            InventoryUtils.setSelectedSlot(player.inventory, 0);
            InventoryUtils.getMainStacks(player.inventory).set(0, stack);
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.HANDHELD);
            preprocess();
        }
        void hotbar(int slot, Item item) {
            InventoryUtils.getMainStacks(player.inventory).set(slot, new ItemStack(item, 64));
        }
        void preprocess() throws Exception { invoke(fill, "preprocess"); }
        void execute() throws Exception {
            set(fill, FeatureModuleBase.class, "level", world);
            skip.set(false);
            Method method = FillHandler.class.getDeclaredMethod("executeIteration", BlockPos.class, AtomicReference.class);
            method.setAccessible(true);
            method.invoke(fill, TARGET, skip);
        }
        String reason() { return hud.snapshot(HudStatsManager.Mode.FILL).lastReason(); }
        void assertDeferredWithoutSubmission() {
            assertEquals(0, queued, "Deferred material decisions must not enqueue an interaction");
            assertEquals(0, sendAttempts, "Deferred material decisions must not attempt transport");
            assertTrue(retries.contains(TARGET), "The candidate must remain available for a later retry");
        }
        void assertSubmission(Item item) {
            assertEquals(1, queued);
            assertEquals(1, sendAttempts);
            assertEquals(item, submittedItem);
            assertTrue(List.of(expectedItems).contains(item));
            assertEquals(TARGET.relative(Direction.NORTH), clickTarget);
            assertEquals(ActionPort.ActionSource.FILL, source);
            assertTrue(retries.contains(TARGET), "Rejected transport must preserve the candidate");
        }
        @Override public void close() throws Exception {
            Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(oldMode);
            Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(oldFacing);
            Configs.Fill.FILL_BLOCK_LIST.setStrings(oldList);
            Configs.Placement.FALLING_CHECK.setBooleanValue(oldFalling);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(oldAir);
            Configs.Core.FILL.setBooleanValue(oldFill);
            set(null, InventoryUtils.class, "client", oldInventoryClient);
            base.close();
        }
    }

    private static void invoke(FillHandler fill, String name) throws Exception {
        Method method = FillHandler.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(fill);
    }
}
//#endif
