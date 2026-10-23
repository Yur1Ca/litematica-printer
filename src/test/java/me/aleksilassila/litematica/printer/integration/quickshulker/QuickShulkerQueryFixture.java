//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;

/** Runs the real query; only optional-mod opening and optional decode counters are intercepted. */
public final class QuickShulkerQueryFixture implements AutoCloseable {
    private static final ThreadLocal<QuickShulkerQueryFixture> CURRENT = new ThreadLocal<>();
    private final Object oldClient = get(null, Minecraft.class, "instance");
    public final Minecraft client = allocate(Minecraft.class);
    final CancelledSwapLifecycleTest.Player player = allocate(CancelledSwapLifecycleTest.Player.class);
    private final Object controller;
    private final Method query;
    private final java.lang.reflect.Field emptyOnly;
    public int decodes;
    public final List<Integer> attempts = new ArrayList<>();
    public Runnable onOpen = () -> { };
    public final List<Integer> clickedHotbar = new ArrayList<>();
    public final List<Integer> clickedSource = new ArrayList<>();
    private final String oldSlots;

    public QuickShulkerQueryFixture(boolean countDecodes) throws Exception {
        player.inventory = new net.minecraft.world.entity.player.Inventory(player, new net.minecraft.world.entity.EntityEquipment());
        set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
        player.containerMenu = player.inventoryMenu;
        client.player = player;
        client.gameMode = allocate(net.minecraft.client.multiplayer.MultiPlayerGameMode.class);
        set(client, Minecraft.class, "gameDirectory", new java.io.File(System.getProperty("user.dir")));
        set(null, Minecraft.class, "instance", client);
        oldSlots = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        CURRENT.set(this);
        Class<?> type = new Loader(countDecodes).loadClass(QuickShulkerRequestController.class.getName());
        controller = type.getConstructor(Minecraft.class).newInstance(client);
        query = type.getDeclaredMethod("openShulker", java.util.HashSet.class);
        query.setAccessible(true);
        emptyOnly = type.getDeclaredField("emptyShulkerOnly");
        emptyOnly.setAccessible(true);
        configuredSlots("1,2,3,4,5,6,7,8,9");
    }

    public void configuredSlots(String value) {
        fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.setValueFromString(value);
        fi.dy.masa.litematica.util.InventoryUtils.setPickBlockableSlots(value);
    }

    public void inventory(int slot, ItemStack stack) { player.inventory.setItem(slot, stack); }

    public void extract() throws Exception {
        extract(35);
    }

    public void extract(int sourceMenuSlot) throws Exception {
        var menu = new net.minecraft.world.inventory.ShulkerBoxMenu(7, player.inventory);
        menu.slots.get(0).set(new ItemStack(Items.STONE, 64));
        player.containerMenu = menu;
        set(controller, controller.getClass(), "automatedContainerId", 7);
        set(controller, controller.getClass(), "shulkerInventoryMenuSlot", sourceMenuSlot);
        controller.getClass().getMethod("requestItem", Item.class).invoke(controller, Items.STONE);
        controller.getClass().getMethod("switchInv").invoke(controller);
    }

    public static boolean canPickToSlot(net.minecraft.world.entity.player.Inventory inventory, int slot) {
        try {
            var method = fi.dy.masa.litematica.util.InventoryUtils.class.getDeclaredMethod("canPickToSlot",
                    net.minecraft.world.entity.player.Inventory.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, inventory, slot);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    public static void send(net.minecraft.world.inventory.AbstractContainerMenu menu, int source, int hotbar,
                            net.minecraft.world.inventory.ContainerInput input) {
        CURRENT.get().clickedHotbar.add(hotbar);
        CURRENT.get().clickedSource.add(source);
    }

    public boolean query(List<Item> candidates, boolean emptyShulkerOnly) throws Exception {
        emptyOnly.setBoolean(controller, emptyShulkerOnly);
        try {
            return (boolean) query.invoke(controller, new LinkedHashSet<>(candidates));
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    public void slot(int menuSlot, ItemStack stack) { player.inventoryMenu.slots.get(menuSlot).set(stack); }

    public static ItemStack box(ItemStack... contents) {
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
        return box;
    }

    public static NonNullList<ItemStack> readContents(ItemStack stack, int size) {
        CURRENT.get().decodes++;
        return fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, size);
    }

    public static boolean open(ItemStack stack, int slot) {
        var fixture = CURRENT.get();
        fixture.attempts.add(slot);
        fixture.onOpen.run();
        return false; // Observe fallback order without sending a real optional-mod request.
    }

    @Override public void close() throws Exception {
        configuredSlots(oldSlots);
        CURRENT.remove();
        set(null, Minecraft.class, "instance", oldClient);
    }

    private static final class Loader extends ClassLoader {
        private static final String PREFIX = "me.aleksilassila.litematica.printer.integration.quickshulker.";
        private final boolean countDecodes;
        Loader(boolean countDecodes) { super(QuickShulkerQueryFixture.class.getClassLoader()); this.countDecodes = countDecodes; }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PREFIX) || name.startsWith(QuickShulkerQueryFixture.class.getName())) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode();
                        new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (!(instruction instanceof MethodInsnNode call)) continue;
                            if (call.owner.equals("me/aleksilassila/litematica/printer/utils/mods/ShulkerUtils") && call.name.equals("openShulker")) {
                                call.owner = Type.getInternalName(QuickShulkerQueryFixture.class); call.name = "open";
                            } else if (call.owner.endsWith("/InventoryUtilsAccessor") && call.name.equals("canPickToSlot")) {
                                call.owner = Type.getInternalName(QuickShulkerQueryFixture.class); call.itf = false;
                            } else if (call.owner.endsWith("/QuickShulkerClicks") && call.name.equals("send")) {
                                call.owner = Type.getInternalName(QuickShulkerQueryFixture.class);
                            } else if (countDecodes && call.owner.equals("fi/dy/masa/malilib/util/InventoryUtils") && call.name.equals("getStoredItems")) {
                                call.owner = Type.getInternalName(QuickShulkerQueryFixture.class); call.name = "readContents";
                            }
                        }
                        var writer = new ClassWriter(0); node.accept(writer);
                        byte[] bytes = writer.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
//#endif
