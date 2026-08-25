package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.Reference;
import net.fabricmc.loader.api.FabricLoader;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

public class TweakerooUtils {
    private static @Nullable Object tweakToolSwitchEnum;
    private static @Nullable Object tweakSwapAlmostBrokenToolsEnum;
    private static @Nullable Object disableBlockBreakCooldownConfig;
    private static @Nullable Method trySwitchToEffectiveToolMethod;
    private static @Nullable Method trySwapCurrentToolIfNearlyBrokenMethod;
    private static @Nullable Method getBooleanValueMethod;
    private static @Nullable Object itemSwapDurabilityThresholdConfig;
    private static @Nullable Method getIntegerValueMethod;
    private static @Nullable Object blockTypeBreakRestriction;
    private static @Nullable Object blockTypeBreakRestrictionBlacklist;
    private static @Nullable Object blockTypeBreakRestrictionWhitelist;
    private static @Nullable Method getListTypeMethod;
    private static @Nullable Method getStringsMethod;
    private static boolean bindingWarningLogged;

    static {
        if (FabricLoader.getInstance().isModLoaded("tweakeroo")) {
            Class<?> featureToggleClass = loadClass("fi.dy.masa.tweakeroo.config.FeatureToggle");
            if (featureToggleClass != null) {
                tweakToolSwitchEnum = loadField(featureToggleClass, "TWEAK_TOOL_SWITCH");
                tweakSwapAlmostBrokenToolsEnum = loadField(featureToggleClass, "TWEAK_SWAP_ALMOST_BROKEN_TOOLS");
            }

            Class<?> disableConfigsClass = loadClass("fi.dy.masa.tweakeroo.config.Configs$Disable");
            if (disableConfigsClass != null) {
                disableBlockBreakCooldownConfig = loadField(disableConfigsClass, "DISABLE_BLOCK_BREAK_COOLDOWN");
            }

            Class<?> iConfigBooleanClass = loadClass("fi.dy.masa.malilib.config.IConfigBoolean");
            if (iConfigBooleanClass != null) {
                try {
                    getBooleanValueMethod = iConfigBooleanClass.getMethod("getBooleanValue");
                } catch (ReflectiveOperationException exception) {
                    logBindingWarning("IConfigBoolean.getBooleanValue", exception);
                }
            }
            Class<?> genericConfigsClass = loadClass("fi.dy.masa.tweakeroo.config.Configs$Generic");
            if (genericConfigsClass != null) {
                itemSwapDurabilityThresholdConfig = loadField(
                        genericConfigsClass, "ITEM_SWAP_DURABILITY_THRESHOLD");
                if (itemSwapDurabilityThresholdConfig != null) {
                    try {
                        getIntegerValueMethod = itemSwapDurabilityThresholdConfig.getClass()
                                .getMethod("getIntegerValue");
                    } catch (ReflectiveOperationException exception) {
                        logBindingWarning("Tweakeroo item swap durability threshold", exception);
                    }
                }
            }
            Class<?> inventoryUtilsClass = loadClass("fi.dy.masa.tweakeroo.util.InventoryUtils");
            if (inventoryUtilsClass != null) {
                try {
                    trySwitchToEffectiveToolMethod = inventoryUtilsClass.getDeclaredMethod(
                            "trySwitchToEffectiveTool", BlockPos.class);
                } catch (ReflectiveOperationException exception) {
                    logBindingWarning("Tweakeroo effective tool switch", exception);
                }
                try {
                    trySwapCurrentToolIfNearlyBrokenMethod = inventoryUtilsClass.getDeclaredMethod(
                            "trySwapCurrentToolIfNearlyBroken");
                } catch (ReflectiveOperationException exception) {
                    logBindingWarning("Tweakeroo nearly-broken tool switch", exception);
                }
            }

            Class<?> placementTweaksClass = loadClass("fi.dy.masa.tweakeroo.tweaks.PlacementTweaks");
            if (placementTweaksClass != null) {
                blockTypeBreakRestriction = loadField(placementTweaksClass, "BLOCK_TYPE_BREAK_RESTRICTION");
            }

            Class<?> listConfigsClass = loadClass("fi.dy.masa.tweakeroo.config.Configs$Lists");
            if (listConfigsClass != null) {
                blockTypeBreakRestrictionBlacklist = loadField(
                        listConfigsClass, "BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST");
                blockTypeBreakRestrictionWhitelist = loadField(
                        listConfigsClass, "BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST");
            }

            if (blockTypeBreakRestriction != null) {
                try {
                    getListTypeMethod = blockTypeBreakRestriction.getClass().getMethod("getListType");
                } catch (ReflectiveOperationException exception) {
                    logBindingWarning("Tweakeroo break restriction type", exception);
                }
            }
            if (blockTypeBreakRestrictionBlacklist != null) {
                try {
                    getStringsMethod = blockTypeBreakRestrictionBlacklist.getClass().getMethod("getStrings");
                } catch (ReflectiveOperationException exception) {
                    logBindingWarning("Tweakeroo break restriction list", exception);
                }
            }
        }
    }

    @Nullable
    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ReflectiveOperationException exception) {
            logBindingWarning(name, exception);
            return null;
        }
    }

    @Nullable
    private static Object loadField(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (ReflectiveOperationException exception) {
            logBindingWarning(owner.getName() + "." + name, exception);
            return null;
        }
    }

    private static void logBindingWarning(String binding, Exception exception) {
        if (!bindingWarningLogged) {
            bindingWarningLogged = true;
            Reference.LOGGER.warn("Tweakeroo integration partially unavailable; missing {}. That optional capability is disabled", binding);
        }
    }

    /**
     * 检查 Tweakeroo 的 TWEAK_TOOL_SWITCH 选项是否启用。
     * @return 如果 Tweakeroo 存在且选项启用，则返回 true，否则返回 false。
     */
    public static boolean isToolSwitchEnabled() {
        return readBoolean(tweakToolSwitchEnum);
    }

    public static boolean isSwapAlmostBrokenToolsEnabled() {
        return readBoolean(tweakSwapAlmostBrokenToolsEnum);
    }

    public static boolean isDisableBlockBreakCooldownEnabled() {
        return readBoolean(disableBlockBreakCooldownConfig);
    }

    /**
     * 调用 Tweakeroo 的 InventoryUtils.trySwitchToEffectiveTool(BlockPos pos) 静态方法。
     * 只有在 Tweakeroo 存在且方法被成功加载时才执行。
     * @param pos 要挖掘的方块位置
     */
    public static void trySwitchToEffectiveTool(BlockPos pos) {
        if (trySwitchToEffectiveToolMethod == null) {
            return;
        }
        try {
            trySwitchToEffectiveToolMethod.invoke(null, pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void trySwapCurrentToolIfNearlyBroken() {
        if (trySwapCurrentToolIfNearlyBrokenMethod == null) {
            return;
        }
        try {
            trySwapCurrentToolIfNearlyBrokenMethod.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isCurrentToolUnsafe(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()
                || !isSwapAlmostBrokenToolsEnabled()) {
            return false;
        }
        int remainingDurability = stack.getMaxDamage() - stack.getDamageValue();
        return remainingDurability <= getMinDurability(stack);
    }

    private static int getMinDurability(ItemStack stack) {
        int threshold = getItemSwapDurabilityThreshold();
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 100 && threshold <= 20
                && (double) threshold / (double) maxDamage > 0.08D) {
            threshold = (int) Math.ceil((double) maxDamage * 0.08D);
        }
        return threshold;
    }

    private static int getItemSwapDurabilityThreshold() {
        if (getIntegerValueMethod == null || itemSwapDurabilityThresholdConfig == null) {
            return 5;
        }
        try {
            return (int) getIntegerValueMethod.invoke(itemSwapDurabilityThresholdConfig);
        } catch (ReflectiveOperationException exception) {
            return 5;
        }
    }

    public static UsageRestriction.ListType getBreakRestrictionListType() {
        if (getListTypeMethod == null || blockTypeBreakRestriction == null) {
            return UsageRestriction.ListType.NONE;
        }
        try {
            Object value = getListTypeMethod.invoke(blockTypeBreakRestriction);
            return value instanceof UsageRestriction.ListType type ? type : UsageRestriction.ListType.NONE;
        } catch (ReflectiveOperationException exception) {
            return UsageRestriction.ListType.NONE;
        }
    }

    public static List<String> getBreakRestrictionBlacklist() {
        return getStrings(blockTypeBreakRestrictionBlacklist);
    }

    public static List<String> getBreakRestrictionWhitelist() {
        return getStrings(blockTypeBreakRestrictionWhitelist);
    }

    private static List<String> getStrings(@Nullable Object config) {
        if (getStringsMethod == null || config == null) {
            return List.of();
        }
        try {
            Object value = getStringsMethod.invoke(config);
            return value instanceof List<?> list
                    ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                    : List.of();
        } catch (ReflectiveOperationException exception) {
            return List.of();
        }
    }

    private static boolean readBoolean(@Nullable Object config) {
        if (config == null) {
            return false;
        }
        try {
            Method method = getBooleanValueMethod != null
                    && getBooleanValueMethod.getDeclaringClass().isInstance(config)
                    ? getBooleanValueMethod
                    : config.getClass().getMethod("getBooleanValue");
            return (boolean) method.invoke(config);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

}
