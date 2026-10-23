//#if MC == 260100
package me.aleksilassila.litematica.printer.utils.mods;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Real Utils with plugin discovery/class resolution at the external dependency boundary replaced. */
public final class TakeItOutQueryFixture implements AutoCloseable {
    private static final ThreadLocal<TakeItOutQueryFixture> CURRENT = new ThreadLocal<>();
    public boolean loaded = true;
    public Class<?> sourceType = ModernSources.class;
    public final Map<Class<?>, Integer> methodLookups = new HashMap<>();
    public final List<Boolean> singleItemRequests = new ArrayList<>();
    public final Class<?> utility;

    public TakeItOutQueryFixture(boolean countLookups) throws Exception {
        CURRENT.set(this);
        Client.AUTOTAKEOUT = true; Client.TAKE_SINGLE_ITEM_MODE = false;
        Client.WORLD_CONTAINER_ITEMS = List.of(); Client.awaitingStack = ItemStack.EMPTY;
        utility = new Loader(countLookups).loadClass(TakeItOutUtils.class.getName());
    }

    public Object call(String method, Class<?>[] parameters, Object... args) throws Exception {
        var target = utility.getDeclaredMethod(method, parameters); target.setAccessible(true);
        try { return target.invoke(null, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    public Set<Item> available() throws Exception { return (Set<Item>) call("getAvailableItems", new Class<?>[0]); }
    public ItemStack empty(ItemStack wanted, Iterable<?> entries) throws Exception {
        return (ItemStack) call("selectEmptyShulkerRequest", new Class<?>[]{ItemStack.class, Iterable.class}, wanted, entries);
    }

    public static boolean loaded() { return CURRENT.get().loaded; }
    public static Class<?> resolve(String name) throws ClassNotFoundException {
        return switch (name) {
            case "net.maxbel.takeitout.client.TakeitoutClient" -> Client.class;
            case "net.maxbel.takeitout.client.WorldContainerSources" -> CURRENT.get().sourceType;
            case "net.maxbel.takeitout.client.WorldContainerMaterialListCache" -> throw new ClassNotFoundException(name);
            default -> Class.forName(name);
        };
    }
    public static Method method(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        CURRENT.get().methodLookups.merge(type, 1, Integer::sum);
        return type.getMethod(name, parameters);
    }

    public static final class Client {
        public static boolean AUTOTAKEOUT;
        public static boolean TAKE_SINGLE_ITEM_MODE;
        public static Iterable<?> WORLD_CONTAINER_ITEMS;
        public static ItemStack awaitingStack;
    }
    public static final class Entry {
        public ItemStack stack;
        public int count;
        public Entry(ItemStack stack, int count) { this.stack = stack; this.count = count; }
        public ItemStack stack() { return stack; }
        public int count() { return count; }
    }
    public record OtherEntry(ItemStack stack, int count) { }
    public static final class MissingEntry { public int count() { return 1; } }
    public static final class LegacySources {
        public static boolean requestStack(Minecraft client, ItemStack stack, boolean single) {
            CURRENT.get().singleItemRequests.add(single); return true;
        }
    }
    public static final class ModernSources {
        public static boolean requestStack(Minecraft client, ItemStack stack, boolean single, boolean silent) {
            if (silent) throw new AssertionError("The existing false flag must be preserved");
            CURRENT.get().singleItemRequests.add(single); return true;
        }
    }
    public static final class MissingSources { }
    public record LegacyPayload(int inner, int outer) { }
    public record ModernPayload(int inner, int outer, boolean single) { }

    @Override public void close() { CURRENT.remove(); }

    private static final class Loader extends ClassLoader {
        private final boolean countLookups;
        Loader(boolean countLookups) { super(TakeItOutQueryFixture.class.getClassLoader()); this.countLookups = countLookups; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String prefix = "me.aleksilassila.litematica.printer.utils.mods.TakeItOut";
            if (!name.startsWith(prefix) || name.startsWith(TakeItOutQueryFixture.class.getName())) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode(); new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) {
                            if (name.equals(TakeItOutUtils.class.getName()) && method.name.equals("isLoaded")) {
                                method.instructions.clear(); method.tryCatchBlocks.clear();
                                if (method.localVariables != null) method.localVariables.clear();
                                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        Type.getInternalName(TakeItOutQueryFixture.class), "loaded", "()Z", false));
                                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                                method.maxStack = 1; method.maxLocals = 0;
                                continue;
                            }
                            for (var instruction : method.instructions) {
                                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals("java/lang/Class")) continue;
                                if (call.name.equals("forName") && call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                                    call.owner = Type.getInternalName(TakeItOutQueryFixture.class); call.name = "resolve";
                                } else if (countLookups && call.name.equals("getMethod")) {
                                    call.owner = Type.getInternalName(TakeItOutQueryFixture.class); call.name = "method";
                                    call.desc = "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
                                    call.setOpcode(Opcodes.INVOKESTATIC); call.itf = false;
                                }
                            }
                        }
                        var writer = new ClassWriter(0); node.accept(writer);
                        byte[] bytes = writer.toByteArray(); type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
//#endif
