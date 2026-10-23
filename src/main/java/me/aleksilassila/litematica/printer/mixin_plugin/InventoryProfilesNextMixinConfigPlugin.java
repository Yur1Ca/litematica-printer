package me.aleksilassila.litematica.printer.mixin_plugin;

import java.io.InputStream;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Do not link optional IPN classes or apply a partial pause/resume contract. */
public final class InventoryProfilesNextMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica-printer");
    private static final String PREFIX = "me.aleksilassila.litematica.printer.mixin.printer.ipn.";
    private static final Set<String> TARGETS = Set.of(
            "org/anti_ad/mc/ipnext/event/autorefill/AutoRefillHandler",
            "org/anti_ad/mc/ipnext/event/AutoRefillHandler");
    private static final String LOCKED_SLOT_KEEPER = "org/anti_ad/mc/ipnext/event/LockedSlotKeeper";
    private final Set<String> compatibleTargets = new HashSet<>();

    @Override public void onLoad(String mixinPackage) {
        if (!FabricLoader.getInstance().isModLoaded("inventoryprofilesnext")) return;
        Set<String> targets = new HashSet<>(TARGETS);
        targets.add(LOCKED_SLOT_KEEPER);
        for (String target : targets) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(target + ".class")) {
                if (stream != null) {
                    byte[] bytes = stream.readAllBytes();
                    if (supportsAutoRefillContract(bytes) || supportsLockedSlotKeeperContract(bytes)) {
                        this.compatibleTargets.add(target.replace('/', '.'));
                    }
                }
            } catch (Exception | LinkageError error) {
                LOGGER.warn("Unable to inspect IPN auto-refill compatibility for {}", target, error);
            }
        }
        if (TARGETS.stream().noneMatch(target -> this.compatibleTargets.contains(target.replace('/', '.'))))
            LOGGER.warn("IPN auto-refill API does not match; printer auto-refill protection is unavailable");
        if (!this.compatibleTargets.contains(LOCKED_SLOT_KEEPER.replace('/', '.')))
            LOGGER.warn("IPN locked-slot keeper API does not match; printer locked-slot protection is unavailable");
    }

    static boolean supportsAutoRefillContract(byte[] bytes) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!TARGETS.contains(node.name)) return false;
            return hasMethod(node, "onTickInGame", "()V")
                    && hasMethod(node, "setScreenOpening", "(Z)V")
                    && hasMethod(node, "init", "()V");
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    static boolean supportsLockedSlotKeeperContract(byte[] bytes) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return LOCKED_SLOT_KEEPER.equals(node.name)
                    && hasMethod(node, "onTickInGame", "()V") && hasMethod(node, "init", "()V")
                    && node.fields.stream().anyMatch(field -> "screenOpening".equals(field.name) && "Z".equals(field.desc)
                            && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == Opcodes.ACC_STATIC);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean hasMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc)
                && (method.access & Opcodes.ACC_PUBLIC) != 0
                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0);
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.startsWith(PREFIX) || this.compatibleTargets.contains(targetClassName);
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
