//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import java.util.*;
import java.util.function.*;
import java.lang.reflect.*;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.*;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseReturnLedgerRegressionTest.*;

public class VoidWarehouseReturnSynchronizationTest {
    @BeforeAll static void bootstrap() { VoidWarehouseMaterialConsumersTest.bootstrap(); }
    // Replace only the transport boundary. Controller/ledger bytecode and MC inventory remain real.
    public static class Transport {
        static List<Integer> sent=List.of();
        static int status = -1;
        static int returned;
        static int sends;
        static boolean supported = true;
        public record Returned(String itemId,int count,long remaining) {}
        public static boolean isSupported(){return supported;}
        public static int requestReturn(List<Integer> slots){sent=List.copyOf(slots);sends++;return sends;}
        public static void forget(){status=-1;returned=0;}
        public static int statusOf(int nonce){return status;}
        public static int returnedOf(int nonce){return returned;}
        public static List<Returned> returnedItemsOf(int nonce){return List.of();}
    }
    static class CaptureLoader extends ClassLoader {
        final String prefix="me.aleksilassila.litematica.printer.integration.voidwarehouse.";
        CaptureLoader(){super(VoidWarehouseReturnSynchronizationTest.class.getClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(!name.equals(prefix+"VoidWarehouseReturnController") && !name.startsWith(prefix+"VoidWarehouseLedgerStore")) return super.loadClass(name,resolve);
            synchronized(getClassLoadingLock(name)) {
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null) try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                    var reader=new ClassReader(in); var writer=new ClassWriter(0);
                    String from=(prefix+"VoidWarehouseReturnNetwork").replace('.','/');
                    String to=Transport.class.getName().replace('.','/');
                    reader.accept(new ClassRemapper(writer,new SimpleRemapper(Opcodes.ASM9,Map.of(from,to,from+"$Returned",to+"$Returned"))),0);
                    byte[] bytes=writer.toByteArray(); loaded=defineClass(name,bytes,0,bytes.length);
                } catch(Exception e){throw new ClassNotFoundException(name,e);}
                if(resolve) resolveClass(loaded); return loaded;
            }
        }
    }
    static Object copyState(Fixture f,VoidWarehouseReturnController state) throws Exception {
        var type=new CaptureLoader().loadClass(VoidWarehouseReturnController.class.getName());
        Object c=type.getConstructor(Minecraft.class,BooleanSupplier.class,BooleanSupplier.class,Supplier.class)
            .newInstance(f.client,(BooleanSupplier)()->false,(BooleanSupplier)()->false,(Supplier<List<Item>>)List::of);
        for(var field:VoidWarehouseReturnController.class.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()) || Set.of("client","printing","inventoryBusy","requestedItems").contains(field.getName()))continue;
            field.setAccessible(true); var target=type.getDeclaredField(field.getName()); target.setAccessible(true); target.set(c,field.get(state));
        }
        Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(true);
        Transport.sent=List.of(); Transport.sends=0; Transport.status=-1; Transport.returned=0; Transport.supported=true;
        return c;
    }
    static List<Integer> capture(Fixture f, VoidWarehouseReturnController state) throws Exception {
        Object controller = copyState(f, state);
        controller.getClass().getMethod("tick").invoke(controller);
        return Transport.sent;
    }
    static void tick(Object controller) throws Exception { controller.getClass().getMethod("tick").invoke(controller); }
    static int balance(Object controller) throws Exception { return (int) controller.getClass().getMethod("pendingCount").invoke(controller); }

    @Test void disabledLedgerReachesReturnRequestForPersonalReplacement() throws Exception {
        try(var f=new Fixture()) {
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            f.controller.recordTaken(Items.STONE,64);
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));f.controller.tick();
            f.player.inv.setItem(35,ItemStack.EMPTY);f.controller.tick();
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));f.controller.tick();
            assertEquals(List.of(),capture(f,f.controller));
        }
    }
    @Test void clearedLedgerSnapshotReachesReturnRequestForPersonalReplacement() throws Exception {
        try(var f=new Fixture()) {
            f.controller.recordTaken(Items.STONE,64);f.player.inv.setItem(35,new ItemStack(Items.STONE,64));
            call(f.controller,"saveSnapshot",new Class<?>[0]);
            f.player.inv.setItem(35,ItemStack.EMPTY);f.reconcile();
            call(f.controller,"saveSnapshot",new Class<?>[0]);f.controller.clear();
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));
            var next=new VoidWarehouseReturnController(f.client,()->false,()->false,List::of);
            assertEquals(List.of(),capture(f,next));
        }
    }
    @Test void componentMismatchReachesReturnRequestForNamedPersonalStack() throws Exception {
        try(var f=new Fixture()) {
            f.controller.recordTaken(Items.STONE,64); f.player.inv.setItem(34,new ItemStack(Items.STONE,64));
            var personal=new ItemStack(Items.STONE,64);personal.set(DataComponents.CUSTOM_NAME,Component.literal("personal reserved stack"));
            f.player.inv.setItem(35,personal);
            assertEquals(List.of(34),capture(f,f.controller));
        }
    }
    @Test void rejectedRestockReachesReturnRequestForGroundPickup() throws Exception {
        try(var f=new Fixture()) {
            var broker=(ActionPort)Proxy.newProxyInstance(ActionPort.class.getClassLoader(),new Class<?>[]{ActionPort.class},(p,m,a)->m.getReturnType()==boolean.class?true:null);
            var net=new VoidWarehouseRestockPort(){public boolean isSupported(){return true;}public int requestTake(List<String> a){return 7;}public int statusOf(int a){return PrinterTakeResultPayload.EMPTY;}public void forget(){}};
            var adapter=new VoidWarehouseRestockAdapter(broker,net);adapter.attachReturnController(f.controller);
            var request=MaterialRequest.single(1,Items.STONE,1,MaterialRequest.Source.PRINT);
            assertEquals(MaterialReservation.State.PENDING,adapter.request(request).state());
            f.player.inv.setItem(35,new ItemStack(Items.STONE,64));adapter.status(request);
            assertEquals(List.of(),capture(f,f.controller));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void disablingReturnsSettlesAnInFlightReceiptWithoutReturningPersonalRemainder(boolean epochChange) throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(34, new ItemStack(Items.STONE, 64));
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            Object controller = copyState(f, f.controller);
            tick(controller);
            assertEquals(List.of(35), Transport.sent);
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            if (epochChange) ((me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent) controller)
                    .onEpochChanged(new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.EpochChanged(
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL.next(),
                            "execution_scope_changed"));
            for (int i = 0; i < 40; i++) {
                ((TestLevel) f.client.level).tick++;
                tick(controller);
                assertEquals(1, Transport.sends);
            }
            f.player.inv.setItem(35, ItemStack.EMPTY);
            Transport.status = neko.voidwarehouse.restock.PrinterReturnResultPayload.OK;
            Transport.returned = 64;
            tick(controller);
            assertEquals(0, balance(controller), "Late receipts must settle warehouse credit while returns are disabled");
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(true);
            tick(controller);
            assertEquals(1, Transport.sends, "Personal remainder must not be returned after re-enabling");
        }
    }

    @Test void delayedInventoryThenReceiptDoesNotSendTwiceOrDoubleDeduct() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 96);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.player.inv.setItem(0, new ItemStack(Items.STONE, 32));
            Object controller = copyState(f, f.controller);
            tick(controller);
            assertEquals(List.of(35), Transport.sent);
            f.player.inv.setItem(35, ItemStack.EMPTY);
            for (int i = 0; i < 40; i++) {
                ((TestLevel) f.client.level).tick++;
                tick(controller);
                assertEquals(1, Transport.sends);
            }
            Transport.status = neko.voidwarehouse.restock.PrinterReturnResultPayload.OK;
            Transport.returned = 64;
            tick(controller);
            assertEquals(32, balance(controller));
            assertEquals(1, Transport.sends);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"timeout", "disconnect", "world_unloaded"})
    void unconfirmedReturnsCannotLeaveCreditForPersonalStock(String boundary) throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(34, new ItemStack(Items.STONE, 64));
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            Object controller = copyState(f, f.controller);
            tick(controller);
            assertEquals(List.of(35), Transport.sent);
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            if (boundary.equals("timeout")) {
                ((TestLevel) f.client.level).tick += 61;
                tick(controller);
                f.player.inv.setItem(35, ItemStack.EMPTY);
                Transport.status = neko.voidwarehouse.restock.PrinterReturnResultPayload.OK;
                Transport.returned = 64;
                tick(controller);
                assertEquals(0, balance(controller));
            } else if (boundary.equals("disconnect")) {
                ((me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent) controller)
                        .onEpochChanged(new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.EpochChanged(
                                me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                                me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL.next(), "disconnect"));
            } else {
                var level = f.client.level;
                f.client.level = null;
                tick(controller);
                f.client.level = level;
            }
            f.player.inv.setItem(35, ItemStack.EMPTY);
            assertEquals(0, f.restored().pendingCount(), "An unconfirmed batch must not survive on disk either");
            assertEquals(1, Transport.sends);
        }
    }
    @Test void abandoningOneBatchPreservesOtherConfirmedMaterials() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.controller.recordTaken(Items.DIRT, 32);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            f.player.inv.setItem(0, new ItemStack(Items.DIRT, 32));
            Object controller = copyState(f, f.controller);
            tick(controller);
            assertEquals(List.of(35), Transport.sent);
            Configs.Special.VOID_WAREHOUSE_RETURN.setBooleanValue(false);
            ((TestLevel) f.client.level).tick += 61;
            tick(controller);
            assertEquals(32, balance(controller));
            assertEquals(32, f.restored().pendingCount());
        }
    }

    @Test void aKnownRejectionDoesNotDiscardTheUnreturnedMaterialsOnDisconnect() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            Object controller = copyState(f, f.controller);
            tick(controller);
            Transport.status = neko.voidwarehouse.restock.PrinterReturnResultPayload.RULE_OFF;
            ((me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent) controller)
                    .onEpochChanged(new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.EpochChanged(
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL.next(), "disconnect"));
            assertEquals(64, f.restored().pendingCount());
        }
    }
    @Test void aServerWithoutReturnSupportStillRetiresConsumedCreditBeforeReconnect() throws Exception {
        try (var f = new Fixture()) {
            f.controller.recordTaken(Items.STONE, 64);
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            Object controller = copyState(f, f.controller);
            Transport.supported = false;
            tick(controller);
            f.player.inv.setItem(35, ItemStack.EMPTY);
            tick(controller);
            assertEquals(0, balance(controller));
            f.player.inv.setItem(35, new ItemStack(Items.STONE, 64));
            tick(controller);
            ((me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent) controller)
                    .onEpochChanged(new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.EpochChanged(
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                            me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL.next(), "disconnect"));
            var restored = f.restored();
            assertEquals(0, restored.pendingCount());
            assertEquals(List.of(), capture(f, restored));
        }
    }
}

//#endif
