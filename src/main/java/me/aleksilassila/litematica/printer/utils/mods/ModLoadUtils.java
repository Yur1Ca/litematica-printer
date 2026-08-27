package me.aleksilassila.litematica.printer.utils.mods;

import net.fabricmc.loader.api.FabricLoader;

public class ModLoadUtils {
    // 快捷潜影盒连续打开的短暂互斥计数；容器界面隐藏由 MixinContainerScreenGuard 处理。
    public static int closeScreen = 0;

    public static boolean isLoadMod(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isChestTrackerLoaded(){
        return isLoadMod("chesttracker");
    }

    public static boolean isQuickShulkerLoaded(){
        return isLoadMod("quickshulker");
    }

    public static boolean isTweakerooLoaded() {
        return isLoadMod("tweakeroo");
    }
}
