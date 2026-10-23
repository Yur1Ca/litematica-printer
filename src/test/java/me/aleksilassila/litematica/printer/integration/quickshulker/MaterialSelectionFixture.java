//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;

/** Real selection, inventory writes and confirmation; only client transport and mixin invokers are replaced. */
public final class MaterialSelectionFixture implements AutoCloseable {
    private final Object oldClient = get(null, Minecraft.class, "instance");
    private final Object oldRuntime = get(null, RuntimeAccess.class, "current");
    final Minecraft client = allocate(Minecraft.class);
    final Player player = allocate(Player.class);
    final PrinterRuntime runtime = allocate(PrinterRuntime.class);
    final List<String> traffic = new ArrayList<>();
    final List<MaterialRequest> requests = new ArrayList<>();
    long tick;
    final InventorySwitchGuard guard = new InventorySwitchGuard(client, () -> tick);
    final Class<?> selector;

    public MaterialSelectionFixture() throws Exception {
        var equipment = new PlayerEquipment(player);
        player.inventory = new Inventory(player, equipment);
        set(player, net.minecraft.world.entity.player.Player.class, "inventory", player.inventory);
        set(player, net.minecraft.world.entity.player.Player.class, "abilities", new Abilities());
        set(player, net.minecraft.world.entity.LivingEntity.class, "equipment", equipment);
        set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu",
                new InventoryMenu(player.inventory, true, player));
        player.containerMenu = player.inventoryMenu;
        var connection = allocate(Connection.class);
        connection.traffic = traffic;
        set(player, LocalPlayer.class, "connection", connection);
        var gameMode = allocate(GameMode.class);
        gameMode.traffic = traffic;
        client.gameMode = gameMode;
        client.player = player;
        set(null, Minecraft.class, "instance", client);
        set(runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
        installRequests(requests);
        RuntimeAccess.install(runtime);
        selector = new SelectionLoader().loadClass("me.aleksilassila.litematica.printer.utils.MaterialSelector");
    }

    void installRequests(List<MaterialRequest> destination) throws Exception {
        InventoryProvider external = new InventoryProvider() {
            @Override public String id() { return "captured-external-provider"; }
            @Override public MaterialReservation request(MaterialRequest request) {
                destination.add(request);
                return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
            }
            @Override public MaterialReservation status(MaterialRequest request) {
                return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
            }
        };
        set(runtime, PrinterRuntime.class, "materialRequests", new MaterialRequestCoordinator(List.of(external)));
    }

    boolean items(Item[] items, int reserve, MaterialRequest.Source source) throws Exception {
        return (boolean) invoke("switchToItems", new Class<?>[]{LocalPlayer.class, Item[].class, int.class,
                MaterialRequest.Source.class}, player, items, reserve, source);
    }

    boolean items(Item[] items, int reserve) throws Exception {
        return (boolean) invoke("switchToItems", new Class<?>[]{LocalPlayer.class, Item[].class, int.class},
                player, items, reserve);
    }

    boolean matching(Predicate<ItemStack> predicate, ItemStack fallback, int reserve) throws Exception {
        return (boolean) invoke("switchToMatchingStack", new Class<?>[]{LocalPlayer.class, Predicate.class,
                ItemStack.class, int.class}, player, predicate, fallback, reserve);
    }

    int surplus(ItemStack stack, Predicate<ItemStack> predicate, int reserve) throws Exception {
        return (int) invoke("getConsumableSurplus", new Class<?>[]{LocalPlayer.class, ItemStack.class,
                Predicate.class, int.class}, player, stack, predicate, reserve);
    }

    ItemStack blocked(Item[] items, Predicate<ItemStack> predicate, int reserve) throws Exception {
        return (ItemStack) invoke("findReserveBlockedStack", new Class<?>[]{LocalPlayer.class, Item[].class,
                Predicate.class, int.class}, player, items, predicate, reserve);
    }

    Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try {
            return selector.getMethod(name, types).invoke(null, args);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }

    @Override public void close() throws Exception {
        set(null, Minecraft.class, "instance", oldClient);
        set(null, RuntimeAccess.class, "current", oldRuntime);
    }

    static final class Player extends CancelledSwapLifecycleTest.Player {
        @Override public GameType gameMode() {
            return getAbilities().instabuild ? GameType.CREATIVE : GameType.SURVIVAL;
        }
    }

    static final class Connection extends CancelledSwapLifecycleTest.Connection {
        List<String> traffic;
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ServerboundSetCarriedItemPacket selected) {
                traffic.add("select:" + selected.getSlot());
            } else {
                throw new AssertionError("Unexpected transport packet: " + packet);
            }
        }
    }

    static final class GameMode extends MultiPlayerGameMode {
        List<String> traffic;
        GameMode() { super(null, null); }
        @Override public void handleContainerInput(int id, int slot, int button, ContainerInput input,
                                                   net.minecraft.world.entity.player.Player player) {
            if (input != ContainerInput.SWAP) throw new AssertionError("Unexpected input: " + input);
            traffic.add("swap:" + slot + ":" + button);
        }
        @Override public void handleCreativeModeItemAdd(ItemStack stack, int slot) {
            traffic.add("creative:" + slot);
        }
    }

    /** Mixin-generated methods are unavailable in JUnit; this fixture configures empty pick slot 2. */
    public static final class PickSlots {
        public static List<Integer> getPICK_BLOCKABLE_SLOTS() { return List.of(2); }
        public static int getEmptyPickBlockableHotbarSlot(Inventory inventory) {
            if (!inventory.getItem(2).isEmpty()) throw new AssertionError("Fixture pick slot must be empty");
            return 2;
        }
    }

    public static final class EasyPlace {
        public static void callSetEasyPlaceLastPickBlockTime() { }
    }

    /** Isolates InventoryUtils' existing static client field without modifying production code. */
    private static final class SelectionLoader extends ClassLoader {
        private static final String UTILS = "me.aleksilassila.litematica.printer.utils.";
        private static final Set<String> ISOLATED = Set.of("MaterialSelector", "InventoryUtils",
                "MaterialSelectionAccess", "ClientMaterialSelectionAccess");

        SelectionLoader() { super(MaterialSelectionFixture.class.getClassLoader()); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(UTILS) || !ISOLATED.contains(name.substring(UTILS.length()))) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode();
                        new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) {
                            for (var instruction : method.instructions) {
                                if (!(instruction instanceof MethodInsnNode call)) continue;
                                String replacement = switch (call.owner) {
                                    case "me/aleksilassila/litematica/printer/mixin/printer/litematica/InventoryUtilsAccessor" ->
                                            Type.getInternalName(PickSlots.class);
                                    case "me/aleksilassila/litematica/printer/mixin/printer/litematica/EasyPlaceUtilsAccessor" ->
                                            Type.getInternalName(EasyPlace.class);
                                    default -> null;
                                };
                                if (replacement != null) {
                                    call.owner = replacement;
                                    call.itf = false;
                                    call.setOpcode(Opcodes.INVOKESTATIC);
                                }
                            }
                        }
                        var writer = new ClassWriter(0);
                        node.accept(writer);
                        byte[] bytes = writer.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException exception) {
                        throw new ClassNotFoundException(name, exception);
                    }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
//#endif
