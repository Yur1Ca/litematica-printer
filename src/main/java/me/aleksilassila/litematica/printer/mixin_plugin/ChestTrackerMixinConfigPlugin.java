package me.aleksilassila.litematica.printer.mixin_plugin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassReader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** Applies Chest Tracker mixins only when the optional mod is installed. */
public final class ChestTrackerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String PREFIX =
            "me.aleksilassila.litematica.printer.mixin.printer.chesttracker.";
    private boolean loaded;
    private boolean compatible;

    @Override public void onLoad(String mixinPackage) {
        this.loaded = FabricLoader.getInstance().isModLoaded("chesttracker");
        this.compatible = this.loaded && hasItemListWidgetContract();
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.startsWith(PREFIX) || this.compatible;
    }

    private static boolean hasItemListWidgetContract() {
        String resourceName = "red/jackf/chesttracker/impl/gui/widget/ItemListWidget.class";
        try (InputStream stream = ChestTrackerMixinConfigPlugin.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (stream == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE
                    | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            boolean field = node.fields.stream().anyMatch(value -> "gridWidth".equals(value.name));
            boolean method = node.methods.stream().anyMatch(value -> "getOffsetItems".equals(value.name)
                    && "()Ljava/util/List;".equals(value.desc));
            return field && method;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
