//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehousePlacementNetwork;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon;
import neko.voidwarehouse.restock.PrinterPlaceHelloPayload;
import neko.voidwarehouse.restock.PrinterPlacePayload;
import java.util.Set;
import java.util.Map;
import java.lang.reflect.Field;

/** Real warehouse EMPTY bookkeeping -> Guide -> Executor -> ActionManager -> vanilla USE. */
class WarehouseInventoryFallbackRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void emptyWarehouseFallsBackToTheExactStockedQuartzItem(boolean stairs) throws Exception {
        try (var f = new ClickFixture(stairs ? Blocks.QUARTZ_STAIRS.defaultBlockState() : Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.emptyWarehouse();
            var result = f.execute();
            if (result.taskEvent() == PrintPlacementResult.TaskEvent.QUEUED) {
                assertTrue(f.dispatch.hits.isEmpty(), "Facing-sensitive placement must not USE before its queued look settles");
                assertFalse(f.transport.packets.isEmpty());
                assertTrue(f.transport.packets.stream().allMatch(
                        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.class::isInstance),
                        "Only LOOK may be sent while the original placement queue waits");
                PlayerLook requestedLook = f.manager.getLook();
                assertNotNull(requestedLook);
                f.client.player.setYRot(requestedLook.yaw);
                f.client.player.setXRot(requestedLook.pitch);
                assertEquals(ActionManager.SendResult.SENT, f.manager.sendQueue(f.client.player),
                        "Resume the same queued action after applying its real requested pose");
            } else {
                assertEquals(PrintPlacementResult.TaskEvent.SUCCESS, result.taskEvent());
            }
            assertTrue(f.runtime.hudStats().isPrintPlacementPending(TARGET),
                    "Immediate and deferred dispatch must both run the real completion bookkeeping");
            assertEquals(1, f.dispatch.hits.size());
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(0, f.manager.warehouseSends);
            assertEquals(0, f.legacyRequests);
        }
    }

    @Test void stockedWarehouseKeepsPriorityOverTheStockedBackpack() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            assertEquals(PrintPlacementResult.TaskEvent.SUCCESS, f.execute().taskEvent());
            assertEquals(1, f.manager.warehouseSends);
            assertTrue(f.dispatch.hits.isEmpty());
            assertEquals(0, f.legacyRequests);
        }
    }

    @Test void anOutstandingWarehousePlacementDoesNotDuplicateThroughTheBackpack() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.beginRequest();
            assertEquals(PrintPlacementResult.TaskEvent.DEFERRED, f.execute().taskEvent());
            f.assertNoDispatchOrRestock();
        }
    }

    @Test void bothStoresMissingDoNotStartTheOldExternalRestockPath() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.emptyWarehouse();
            // A similar-looking item is not an acceptable substitute.
            f.client.player.getInventory().setItem(0, new ItemStack(Items.SMOOTH_QUARTZ_SLAB, 16));
            assertEquals(PrintPlacementResult.TaskEvent.MATERIAL_UNAVAILABLE, f.execute().taskEvent());
            f.assertNoDispatchOrRestock();
        }
    }

    @Test void warehouseFallbackCannotConsumeReservedBackpackItems() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.emptyWarehouse();
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(true);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(16);
            f.execute();
            f.assertNoDispatchOrRestock();
            assertEquals(16, f.client.player.getMainHandItem().getCount());
        }
    }

    @Test void aWarehouseTriggerIsNotFallbackCraftingTableMaterial() throws Exception {
        try (var f = new ClickFixture(Blocks.CRAFTING_TABLE.defaultBlockState())) {
            f.emptyWarehouse();
            f.client.player.getMainHandItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("Void Warehouse"));
            assertTrue(me.aleksilassila.litematica.printer.utils.VoidWarehouseTriggerItem.isTrigger(
                    f.client.player.getMainHandItem()));
            assertEquals(PrintPlacementResult.TaskEvent.MATERIAL_UNAVAILABLE, f.execute().taskEvent());
            f.assertNoDispatchOrRestock();
        }
    }

    @Test void warehouseFallbackCannotBypassAnUnconfirmedInventorySwitch() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.emptyWarehouse();
            f.runtime.inventorySwitchGuard().markSwitchIfNeeded(Items.QUARTZ_SLAB);
            assertTrue(f.runtime.inventorySwitchGuard().isWaiting());
            f.execute();
            f.assertNoDispatchOrRestock();
        }
    }

    @Test void anEmptyReplyForAnotherPositionCannotReleaseAnOutstandingPlacement() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            int pending = f.beginRequest();
            assertTrue(pending > 0);
            int other = f.beginRequest(TARGET.east(), "minecraft:quartz_slab");
            f.reply(other, 2);
            assertTrue(VoidWarehousePlacementNetwork.isPending(TARGET));
            assertFalse(VoidWarehousePlacementNetwork.isMaterialAvailable(new Item[]{Items.QUARTZ_SLAB}));
            assertEquals(PrintPlacementResult.TaskEvent.DEFERRED, f.execute().taskEvent());
            f.assertNoDispatchOrRestock();
        }
    }

    @Test void emptyStockCannotBypassGlobalThrottlingEstablishedByAnotherMaterial() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.emptyWarehouse();
            int other = f.beginRequest(TARGET.east(), "minecraft:stone");
            assertTrue(other > 0);
            f.reply(other, 4);
            assertEquals(PrintPlacementResult.TaskEvent.DEFERRED, f.execute().taskEvent());
            f.assertNoDispatchOrRestock();
            f.advance(6L);
            assertEquals(PrintPlacementResult.TaskEvent.SUCCESS, f.execute().taskEvent(),
                    "The five-tick global throttle must expire without an inventory gain");
            assertEquals(1, f.dispatch.hits.size());
        }
    }

    @Test void anEmptyDirectReplyRecoversThroughTheExistingConfirmationExpiryWithoutInventoryGain() throws Exception {
        try (var f = new ClickFixture(Blocks.QUARTZ_SLAB.defaultBlockState())) {
            f.advance(1L);
            f.manager.registerRequest = () -> {
                try { return f.beginRequest(); }
                catch (Exception error) { throw new AssertionError(error); }
            };
            assertEquals(PrintPlacementResult.TaskEvent.SUCCESS, f.execute().taskEvent());
            assertEquals(1, f.manager.warehouseSends);
            assertTrue(f.manager.lastNonce > 0);
            assertTrue(f.runtime.hudStats().isPrintPlacementPending(TARGET));
            long dirtyBefore = f.runtime.scanEngine().dirtyVersion();
            f.reply(f.manager.lastNonce, 2);
            assertFalse(VoidWarehousePlacementNetwork.isPending(TARGET));
            for (long tick = 2; tick <= 10; tick++) {
                f.advance(tick);
                f.runtime.hudStats().tick();
                assertTrue(f.runtime.hudStats().isPrintPlacementPending(TARGET),
                        "An EMPTY reply must not prematurely release the ordinary confirmation owner");
            }
            f.advance(12L);
            f.runtime.hudStats().tick();
            assertFalse(f.runtime.hudStats().isPrintPlacementPending(TARGET));
            assertTrue(f.runtime.scanEngine().dirtyVersion() > dirtyBefore,
                    "The real HUD expiry must invalidate the target for the next scan, without a fake block update");
            assertTrue(f.level.getBlockState(TARGET).isAir());
            assertEquals(16, f.client.player.getMainHandItem().getCount());
            assertEquals(PrintPlacementResult.TaskEvent.SUCCESS, f.execute().taskEvent());
            assertEquals(1, f.dispatch.hits.size());
            assertEquals(1, f.manager.warehouseSends);
            assertEquals(0, f.legacyRequests);
        }
    }

    private static class WarehouseConnection extends TestConnection implements PacketListenerExtensions {
        ClientPlayNetworkAddon addon;
        @Override public AbstractNetworkAddon<?> getAddon() { return this.addon; }
    }

    private static class PlacementCapture extends ActionManager {
        int warehouseSends;
        int lastNonce;
        java.util.function.IntSupplier registerRequest;
        @Override protected boolean sendWarehousePlacement(Item[] items, BlockPos target, BlockHitResult hit, int state) {
            warehouseSends++;
            if (registerRequest != null) lastNonce = registerRequest.getAsInt();
            return true;
        }
    }
    private static class SneakPlayer extends TestPlayer {
        boolean shift;
        @Override public boolean isShiftKeyDown() { return this.shift; }
        @Override public void setShiftKeyDown(boolean shift) { this.shift = shift; }
    }

    private static class ClickGameMode extends TestGameMode {
        ClickFixture fixture;
        List<Boolean> sneakingAtUse;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            this.hits.add(hit);
            this.sneakingAtUse.add(this.fixture.client.player.isShiftKeyDown());
            this.fixture.client.player.connection.send(new ServerboundUseItemOnPacket(hand, hit, this.hits.size()));
            return InteractionResult.PASS;
        }
    }

    private static class ClickFixture extends Fixture {
        final boolean oldAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        final boolean oldSort = Configs.Print.PRINT_SORT_SIDES.getBooleanValue();
        final boolean oldSneak = Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        final boolean oldReserve = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        final boolean oldProtocol = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        final boolean oldQuickShulker = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final int oldWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        final Object oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        final PlacementCapture manager = new PlacementCapture();
        final ClickGameMode dispatch;
        final WaterSupportRegressionTest.Schematic schematic;
        final PrintPlacementExecutor executor;

        @SuppressWarnings("unchecked")
        ClickFixture(BlockState required) throws Exception {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(true);
            Configs.Print.PRINT_FORCED_SNEAK.setBooleanValue(false);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            this.installCachedClient(Reference.class.getDeclaredField("MINECRAFT"));
            var player = allocate(SneakPlayer.class);
            player.inventory = client.player.getInventory();
            set(player, Player.class, "inventory", player.inventory);
            set(player, Player.class, "inventoryMenu", client.player.inventoryMenu);
            set(player, Player.class, "abilities", client.player.getAbilities());
            player.containerMenu = player.inventoryMenu;
            set(player, Entity.class, "level", level);
            set(player, Entity.class, "eyeHeight", 1.5F);
            set(player, Entity.class, "position", new Vec3(0.5D, 64.0D, 3.5D));
            set(player, Entity.class, "blockPosition", new BlockPos(0, 64, 3));
            set(player, LivingEntity.class, "attributes", new AttributeMap(Player.createAttributes().build()));
            player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(64.0D);
            set(player, LocalPlayer.class, "connection", connection);
            player.input = new ClientInput();
            client.player = player;
            player.inventory.setItem(0, new ItemStack(required.getBlock().asItem(), 16));
            InventoryUtils.setSelectedSlot(player.inventory, 0);
            level.blocks.clear();
            level.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            schematic = allocate(WaterSupportRegressionTest.Schematic.class);
            schematic.states = new HashMap<>();
            schematic.states.put(TARGET, required);
            dispatch = allocate(ClickGameMode.class);
            dispatch.fixture = this;
            dispatch.hits = new ArrayList<>();
            dispatch.sneakingAtUse = new ArrayList<>();
            client.gameMode = dispatch;
            ActionPort port = (ActionPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ActionPort.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "queueClick" -> manager.queueClick((BlockPos) args[0], (Direction) args[1], (Vec3) args[2],
                                (boolean) args[3], (int) args[4], (Item[]) args[5], ActionManager.ActionSource.PRINT);
                        case "setWarehousePlacement" -> manager.setWarehousePlacement((BlockPos) args[0], (int) args[1]);
                        case "cancelQueue" -> { manager.cancelQueue(); yield null; }
                        case "setExpectedStackPredicate" -> manager.setExpectedStackPredicate((Predicate<ItemStack>) args[0]);
                        case "setLook" -> { manager.setLook((PlayerLook) args[0]); yield null; }
                        case "setNeedWaitModifyLookFromAction" -> { manager.setNeedWaitModifyLookFromAction((boolean) args[0]); yield null; }
                        case "sendQueue" -> ActionPort.SendResult.valueOf(manager.sendQueue((LocalPlayer) args[0]).name());
                        case "setQueueCompletionListener" -> manager.setQueueCompletionListener(result ->
                                ((java.util.function.Consumer<ActionPort.SendResult>) args[0]).accept(
                                        ActionPort.SendResult.valueOf(result.name())));
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            var requests = new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "interactive-support-boundary"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    legacyRequests++;
                    return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
                }
            }));
            set(runtime, me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "materialRequests", requests);
            set(runtime, me.aleksilassila.litematica.printer.runtime.PrinterRuntime.class, "scanEngine",
                    new me.aleksilassila.litematica.printer.handler.scan.ScanEngine(
                            () -> me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                            () -> level, new me.aleksilassila.litematica.printer.handler.scan.ScanCache()));
            setupWarehouse();
            executor = new PrintPlacementExecutor(port, new CooldownUtils(), runtime.inventorySwitchGuard(),
                    runtime.hudStats(), new MissingMaterialTracker(), new LitematicaAdapter(), new FallingPlacementTracker(), requests);
        }

        PrintPlacementResult execute() {
            var context = new SchematicBlockContext(client, level, schematic, TARGET);
            var action = new Guides().buildAction(context).orElseThrow();
            return executor.execute(context, action, null);
        }
        void assertNoDispatchOrRestock() {
            assertTrue(dispatch.hits.isEmpty());
            assertEquals(0, manager.warehouseSends);
            assertEquals(0, legacyRequests);
        }
        final int oldReserveCount = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        final boolean oldDirect = Configs.Special.VOID_WAREHOUSE_DIRECT_PLACE.getBooleanValue();
        final Map<Field, Object> networkFields = new java.util.LinkedHashMap<>();
        Object warehouseState;
        int legacyRequests;

        void setupWarehouse() throws Exception {
            Configs.Special.VOID_WAREHOUSE_DIRECT_PLACE.setBooleanValue(true);
            var linked = allocate(WarehouseConnection.class);
            linked.transport = transport;
            linked.addon = allocate(ClientPlayNetworkAddon.class);
            set(linked.addon, AbstractChanneledNetworkAddon.class, "sendableChannels",
                    Set.of(PrinterPlaceHelloPayload.TYPE.id(), PrinterPlacePayload.TYPE.id()));
            set(client.player, LocalPlayer.class, "connection", linked);
            for (String name : List.of("initialized", "connection", "world", "tick")) {
                var field = VoidWarehousePlacementNetwork.class.getDeclaredField(name);
                field.setAccessible(true);
                networkFields.put(field, field.get(null));
            }
            set(null, VoidWarehousePlacementNetwork.class, "initialized", true);
            set(null, VoidWarehousePlacementNetwork.class, "connection", linked);
            set(null, VoidWarehousePlacementNetwork.class, "world", level);
            set(null, VoidWarehousePlacementNetwork.class, "tick", 1L);
            var stateField = VoidWarehousePlacementNetwork.class.getDeclaredField("STATE");
            stateField.setAccessible(true);
            warehouseState = stateField.get(null);
            stateCall("reset", new Class<?>[0]);
            int nonce = (int) stateCall("beginHello", new Class<?>[]{long.class}, 0L);
            stateCall("receiveHello", new Class<?>[]{int.class, boolean.class, long.class}, nonce, true, 0L);
            assertTrue(VoidWarehousePlacementNetwork.isAvailable(), "Fixture must negotiate the actual direct-placement route");
        }
        int beginRequest() throws Exception {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                    schematic.states.get(TARGET).getBlock().asItem()).toString();
            return beginRequest(TARGET, id);
        }
        int beginRequest(BlockPos target, String id) throws Exception {
            return (int) stateCall("beginRequest", new Class<?>[]{List.class, Object.class, long.class}, List.of(id), target, 1L);
        }
        void reply(int nonce, int status) throws Exception {
            var method = VoidWarehousePlacementNetwork.class.getDeclaredMethod("handleResult",
                    neko.voidwarehouse.restock.PrinterPlaceResultPayload.class, net.minecraft.client.multiplayer.ClientLevel.class);
            method.setAccessible(true);
            method.invoke(null, new neko.voidwarehouse.restock.PrinterPlaceResultPayload(nonce, status), level);
        }
        void advance(long now) throws Exception {
            tick(now);
            set(null, VoidWarehousePlacementNetwork.class, "tick", now);
        }
        void emptyWarehouse() throws Exception {
            int nonce = beginRequest();
            assertTrue(nonce > 0);
            stateCall("receiveResult", new Class<?>[]{int.class, int.class, long.class}, nonce, 2, 1L);
            assertFalse(VoidWarehousePlacementNetwork.isMaterialAvailable(new Item[]{schematic.states.get(TARGET).getBlock().asItem()}));
        }
        Object stateCall(String name, Class<?>[] types, Object... args) throws Exception {
            var method = warehouseState.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(warehouseState, args);
        }
        @Override public void close() throws Exception {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(oldAir);
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(oldSort);
            Configs.Print.PRINT_FORCED_SNEAK.setBooleanValue(oldSneak);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(oldReserve);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(oldProtocol);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuickShulker);
            Configs.Core.WORK_RANGE.setIntegerValue(oldWorkRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue((RadiusShapeType) oldShape);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(oldReserveCount);
            Configs.Special.VOID_WAREHOUSE_DIRECT_PLACE.setBooleanValue(oldDirect);
            if (warehouseState != null) stateCall("reset", new Class<?>[0]);
            for (var entry : networkFields.entrySet()) entry.getKey().set(null, entry.getValue());
            super.close();
        }
    }
}
//#endif
