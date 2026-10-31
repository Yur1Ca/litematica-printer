//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.*;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class VoidWarehouseReturnLedgerRegressionTest {
    @BeforeAll static void bootstrap() { VoidWarehouseMaterialConsumersTest.bootstrap(); }
    static <T> T allocate(Class<T> type) throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }
    static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        var field=type.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    static Object call(Object target, String name, Class<?>[] types, Object...args) throws Exception {
        var method=target.getClass().getDeclaredMethod(name,types); method.setAccessible(true);
        return method.invoke(target,args);
    }
    static class TestPlayer extends LocalPlayer {
        Inventory inv;
        TestPlayer() { super(null,null,null,null,null,null,false,null); }
        @Override public Inventory getInventory() { return inv; }
        @Override public Abilities getAbilities() { return new Abilities(); }
    }
    static class TestLevel extends ClientLevel {
        TestLevel() { super(null,null,null,null,0,0,null,false,0L,0); }
        long tick;
        @Override public long getGameTime() { return tick; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
    }
    static class Fixture implements AutoCloseable {
        final Path ledgerFile = Path.of("config/litematica-printer-void-warehouse-ledger.json");
        final byte[] previousLedger = Files.exists(ledgerFile) ? Files.readAllBytes(ledgerFile) : null;
        final Minecraft oldClient=Minecraft.getInstance();
        final boolean oldReturn=Configs.Special.VOID_WAREHOUSE_RETURN.getBooleanValue();
        final boolean oldRestock=Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        final Minecraft client=allocate(Minecraft.class);
        final TestPlayer player=allocate(TestPlayer.class);
        final VoidWarehouseReturnController controller;
        final ActionBroker broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null);
        final Network network = new Network();
        VoidWarehouseRestockAdapter adapter() {
            var adapter = new VoidWarehouseRestockAdapter(broker, network);
            adapter.attachReturnController(controller);
            return adapter;
        }
        void save() throws Exception { call(controller, "saveSnapshot", new Class<?>[0]); }
        VoidWarehouseReturnController restored() throws Exception {
            var next = new VoidWarehouseReturnController(client, () -> false, () -> false, List::of);
            call(next, "restoreIfPending", new Class<?>[0]);
            return next;
        }
        Fixture() throws Exception {
            player.inv=new Inventory(player,new EntityEquipment());
            set(player,Player.class,"inventoryMenu",new InventoryMenu(player.inv,true,player));
            player.containerMenu=player.inventoryMenu;
            client.player=player; client.level=allocate(TestLevel.class);
            ((TestLevel) client.level).tick = 1000L;
            set(null,Minecraft.class,"instance",client);
            controller=new VoidWarehouseReturnController(client,()->false,()->false,List::of);
            set(controller,VoidWarehouseReturnController.class,"restorePending",false);
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
            Files.deleteIfExists(Path.of("config/litematica-printer-void-warehouse-ledger.json"));
        }
        List<?> slots(VoidWarehouseReturnController c) throws Exception {
            return (List<?>)call(c,"chooseSlots",new Class<?>[]{int.class,boolean.class,Set.class},1,false,Set.of());
        }
        void reconcile() throws Exception { call(controller,"reconcile",new Class<?>[]{long.class},1000L); }
        @Override public void close() throws Exception {
            set(null,Minecraft.class,"instance",oldClient);
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(oldReturn);
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(oldRestock);
            if (previousLedger == null) {
                Files.deleteIfExists(ledgerFile);
            } else {
                Files.createDirectories(ledgerFile.getParent());
                Files.write(ledgerFile, previousLedger);
            }
        }
    }
    @Test void disabledReturnMustNotReclassifyLaterPersonalStock() throws Exception {
        try(var f=new Fixture()) {
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            f.controller.recordTaken(Items.STONE,64);
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64)); f.controller.tick();
            f.player.inv.setItem(35,ItemStack.EMPTY); f.controller.tick();
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64)); f.controller.tick();
            f.reconcile();
            assertEquals(List.of(),f.slots(f.controller),"All warehouse stone was consumed while disabled; replacement is personal");
        }
    }
    @Test void emptyLedgerMustInvalidatePreviousSnapshot() throws Exception {
        try(var f=new Fixture()) {
            f.controller.recordTaken(Items.STONE,64);
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));
            call(f.controller,"saveSnapshot",new Class<?>[0]);
            f.player.inv.setItem(35,ItemStack.EMPTY); f.reconcile();
            assertEquals(0,f.controller.pendingCount());
            call(f.controller,"saveSnapshot",new Class<?>[0]);
            f.controller.clear();
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));
            var next=new VoidWarehouseReturnController(f.client,()->false,()->false,List::of);
            call(next,"restoreIfPending",new Class<?>[0]);
            assertEquals(0,next.pendingCount(),"A settled ledger must not be re-adopted after personal stock is acquired");
        }
    }
    @Test void distinctPersonalComponentsMustNotSpendPlainWarehouseBudget() throws Exception {
        try(var f=new Fixture()) {
            f.controller.recordTaken(Items.STONE,64);
            f.player.inv.setItem(34,new ItemStack(Items.STONE,64));
            var personal=new ItemStack(Items.STONE,64);
            personal.set(DataComponents.CUSTOM_NAME,Component.literal("personal reserved stack"));
            f.player.inv.setItem(35,personal);
            assertEquals(List.of(34),f.slots(f.controller),"Named personal stack must not be returned in place of plain warehouse material");
        }
    }
    static class Network implements VoidWarehouseRestockPort {
        int status = PrinterTakeResultPayload.NONE;
        int sends;
        @Override public boolean isSupported() { return true; }
        @Override public int requestTake(List<String> candidates) { return ++sends; }
        @Override public int statusOf(int nonce) { return status; }
        @Override public void forget() { status = PrinterTakeResultPayload.NONE; }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void rejectedRestockMustNotRecordUnrelatedPickup(int status) throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            assertEquals(MaterialReservation.State.PENDING, adapter.request(request).state());
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.network.status = status;
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertEquals(0, f.controller.pendingCount(), "Rejected warehouse requests cannot claim pickups");
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void successfulRestockNeedsBothInventoryAndReceipt(boolean receiptFirst) throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            assertEquals(MaterialReservation.State.PENDING, adapter.request(request).state());
            if (receiptFirst) f.network.status = PrinterTakeResultPayload.OK;
            else f.player.inv.setItem(35, new ItemStack(Items.STONE, 17));
            for (int i = 0; i < 40; i++) {
                assertEquals(MaterialReservation.State.PENDING, adapter.status(request).state());
                assertEquals(0, f.controller.pendingCount());
                assertFalse(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY), 0L));
            }
            f.network.status = PrinterTakeResultPayload.OK;
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 17));
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertEquals(17, f.controller.pendingCount(), "Account only the partial delivery");
            assertEquals(1, f.network.sends);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            adapter.status(request);
            assertEquals(17, f.controller.pendingCount(), "A receipt can be accounted only once");
        }
    }

    @Test void delayedFailureCannotClaimAnEarlierPickup() throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            adapter.request(request);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            assertEquals(MaterialReservation.State.PENDING, adapter.status(request).state());
            f.network.status = PrinterTakeResultPayload.EMPTY;
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertEquals(0, f.controller.pendingCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"disable", "disconnect"})
    void cancelledRestockDoesNotClaimALateDelivery(String reason) throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            adapter.request(request);
            if (reason.equals("disable")) {
                Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(false);
                adapter.status(request);
            } else adapter.reset();
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.network.status = PrinterTakeResultPayload.OK;
            assertEquals(MaterialReservation.State.UNAVAILABLE, adapter.status(request).state());
            assertEquals(0, f.controller.pendingCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void anExistingInventoryOwnerPreventsRestockDispatchAndAccounting() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY), 0L));
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            assertEquals(MaterialReservation.State.PENDING, adapter.request(request).state());
            assertEquals(0, f.network.sends);
            assertEquals(0, f.controller.pendingCount());
            f.broker.releaseOwner("container_sync");
            assertEquals(MaterialReservation.State.PENDING, adapter.status(request).state());
            assertEquals(1, f.network.sends);
        }
    }

    @Test void settlementIsPersistedImmediatelyEvenWhileReturnsAreDisabled() throws Exception {
        try (var f = new Fixture()) {
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.save();
            f.player.inv.setItem(35, ItemStack.EMPTY);
            f.controller.tick();
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            assertEquals(0, f.restored().pendingCount());
        }
    }

    @Test void disconnectBeforeRestorationDoesNotOverwriteAValidSnapshot() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.save();
            byte[] saved = Files.readAllBytes(f.ledgerFile);
            var uninitialized = new VoidWarehouseReturnController(f.client, () -> false, () -> false, List::of);
            uninitialized.onEpochChanged(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL,
                    RuntimeEpoch.INITIAL.next(), "disconnect"));
            assertArrayEquals(saved, Files.readAllBytes(f.ledgerFile));
            assertEquals(64, f.restored().pendingCount());
        }
    }

    @Test void reconnectRestoresOnlyTheRemainingWarehouseBalance() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 32));
            f.reconcile();
            f.controller.onEpochChanged(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL,
                    RuntimeEpoch.INITIAL.next(), "disconnect"));
            assertEquals(0, f.controller.pendingCount());
            assertEquals(32, f.restored().pendingCount());
        }
    }

    @Test void anotherWorldNeverAdoptsThePreviousWorldsLedger() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.save();
            f.client.level = allocate(TestLevel.class);
            ((TestLevel) f.client.level).tick = 2000L;
            assertEquals(0, f.restored().pendingCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"name", "damage", "stack_size"})
    void componentVariantsNeitherPreserveNorRestorePlainMaterialCredit(String component) throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.save();
            var personal = new ItemStack(Items.STONE, 64);
            if (component.equals("name")) personal.set(DataComponents.CUSTOM_NAME, Component.literal("reserved"));
            if (component.equals("damage")) personal.set(DataComponents.DAMAGE, 1);
            if (component.equals("stack_size")) personal.set(DataComponents.MAX_STACK_SIZE, 16);
            f.player.inv.setItem(35, personal);
            f.reconcile();
            assertEquals(0, f.controller.pendingCount());
            assertEquals(0, f.restored().pendingCount());
        }
    }

    @Test void nonDefaultPickupIsNotCountedAsTheConfirmedDefaultDelivery() throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var request = MaterialRequest.single(1, Items.STONE, 1, MaterialRequest.Source.PRINT);
            adapter.request(request);
            var personal = new ItemStack(Items.STONE, 64);
            personal.set(DataComponents.CUSTOM_NAME, Component.literal("reserved"));
            f.player.inv.setItem(35, personal);
            f.player.inv.setItem(34, new ItemStack(Items.STONE, 16));
            f.network.status = PrinterTakeResultPayload.OK;
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertEquals(16, f.controller.pendingCount());
            assertEquals(List.of(34), f.slots(f.controller));
        }
    }
    @Test void rejectedSnapshotEntriesAreNotReAdoptedOnTheNextReconnect() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.save();
            f.player.inv.setItem(35, ItemStack.EMPTY);
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            var firstLogin = new VoidWarehouseReturnController(f.client, () -> false, () -> false, List::of);
            firstLogin.tick();
            assertEquals(0, firstLogin.pendingCount());
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            assertEquals(0, f.restored().pendingCount(), "Rejected old credit must not survive another reconnect");
        }
    }
}
//#endif
