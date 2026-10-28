package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//#if MC >= 260300
//$$ import net.minecraft.core.component.DataComponents;
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//#else
import net.minecraft.world.level.ItemLike;
import java.util.Arrays;
//#endif

import java.util.ArrayList;
import java.util.List;

/**
 * 堆肥桶
 */
public class ComposterGuide extends Guide {
    public ComposterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (!Configs.Print.FILL_COMPOSTER.getBooleanValue()) return Result.PASS;
        if (!currentState.hasProperty(ComposterBlock.LEVEL) || !requiredState.hasProperty(ComposterBlock.LEVEL)) {
            return Result.SKIP;
        }

        int currentLevel = getProperty(currentState, ComposterBlock.LEVEL).orElse(0);
        int requiredLevel = getProperty(requiredState, ComposterBlock.LEVEL).orElse(0);

        if (currentLevel >= requiredLevel) return Result.PASS;

        // Resolve only when a composter needs filling. Components and whitelist tags can
        // change on reconnect/reload, so neither an early empty result nor old matches persist.
        Item[] compostableItems = getCompostableItems();
        if (compostableItems.length == 0) return Result.SKIP;
        List<String> whitelist = Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings();
        if (!whitelist.isEmpty()) {
            List<Item> whitelistItems = new ArrayList<>();
            for (Item item : compostableItems) {
                for (String rule : whitelist) {
                    if (FilterUtils.matchName(rule, new ItemStack(item))) {
                        whitelistItems.add(item);
                        break;
                    }
                }
            }
            if (!whitelistItems.isEmpty()) {
                return Result.success(new ClickAction().setItems(whitelistItems.toArray(Item[]::new)));
            }
        }
        return Result.success(new ClickAction().setItems(compostableItems));
    }

    private static Item[] getCompostableItems() {
        //#if MC >= 260300
        //$$ List<Item> items = new ArrayList<>();
        //$$ var holders = BuiltInRegistries.ITEM.listElements().iterator();
        //$$ while (holders.hasNext()) {
        //$$     var holder = holders.next();
        //$$     // Check readiness explicitly; do not swallow component/linkage errors or use
        //$$     // a partial candidate list while server data is still being bound.
        //$$     if (!holder.areComponentsBound()) return new Item[0];
        //$$     if (holder.components().has(DataComponents.COMPOSTABLE)) items.add(holder.value());
        //$$ }
        //$$ return items.toArray(Item[]::new);
        //#else
        return Arrays.stream(ComposterBlock.COMPOSTABLES.keySet().toArray(ItemLike[]::new))
                .map(ItemLike::asItem).toArray(Item[]::new);
        //#endif
    }
}
