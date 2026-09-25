package me.aleksilassila.litematica.printer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
//#if MC >= 260300
//$$ import net.minecraft.core.component.DataComponents;
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//#endif
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * 模组核心常量引用类
 * 集中管理模组的全局固定值，避免硬编码和拼写错误
 */
public class Reference {
    public static final Minecraft MINECRAFT = Minecraft.getInstance();
    public static final String MOD_ID = "litematica-printer";
    public static final String MOD_NAME = "Litematica Printer";
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    public static final String GUI_TITLE = MOD_NAME + " - Hana - " + displayBuildVersion(VERSION);
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String displayBuildVersion(String version) {
        int separator = version.lastIndexOf('-');
        if (separator >= 0 && separator + 1 < version.length()) {
            String suffix = version.substring(separator + 1);
            if (suffix.equals("local") || suffix.startsWith("dev") || suffix.startsWith("beta")) {
                return suffix;
            }
        }
        return version;
    }

    //#if MC >= 260300
    //$$ public static final Item[] COMPOSTABLE_ITEMS = BuiltInRegistries.ITEM.stream()
    //$$         .filter(item -> item.components().has(DataComponents.COMPOSTABLE))
    //$$         .toArray(Item[]::new);
    //#else
    public static final Item[] COMPOSTABLE_ITEMS = Arrays.stream(ComposterBlock.COMPOSTABLES.keySet().toArray(ItemLike[]::new)).map(ItemLike::asItem).toArray(Item[]::new);
    //#endif
    public static final Item[] HOE_ITEMS = {Items.DIAMOND_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.NETHERITE_HOE, Items.STONE_HOE, Items.WOODEN_HOE};
    public static final Item[] SHOVEL_ITEMS = {Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.GOLDEN_SHOVEL, Items.NETHERITE_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL};
    public static final Item[] AXE_ITEMS = {Items.DIAMOND_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.NETHERITE_AXE, Items.STONE_AXE, Items.WOODEN_AXE};

    private static final Class<?>[] WRONG_STATE_BREAK_IGNORED_BLOCKS = {
            //#if MC >= 12000
            ChiseledBookShelfBlock.class,
            //#endif
            BrewingStandBlock.class,
            StainedGlassPaneBlock.class,
            VegetationBlock.class,
            LeavesBlock.class,
            SugarCaneBlock.class
    };

    public static boolean isIgnoreWrongStateBlock(Block block) {
        for (Class<?> blockClass : WRONG_STATE_BREAK_IGNORED_BLOCKS) {
            if (blockClass.isInstance(block)) {
                return true;
            }
        }
        return false;
    }
}
