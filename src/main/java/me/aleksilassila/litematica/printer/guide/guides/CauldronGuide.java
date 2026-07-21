package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.world.level.block.LayeredCauldronBlock;
<<<<<<< HEAD
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * 炼药锅
 */
public class CauldronGuide extends Guide {

    public CauldronGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
<<<<<<< HEAD
        if (!requiredState.is(Blocks.WATER_CAULDRON)) {
            return Result.SKIP;
        }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        Optional<Integer> currentLevel = getProperty(currentState, LayeredCauldronBlock.LEVEL);
        Optional<Integer> requiredLevel = getProperty(requiredState, LayeredCauldronBlock.LEVEL);

        if (currentLevel.isEmpty() || requiredLevel.isEmpty()) {
            return Result.SKIP;
        }

        if (currentLevel.get() > requiredLevel.get()) {
            if (InventoryUtils.playerHasAccessToItem(client.player, Items.GLASS_BOTTLE)) {
                return Result.success(new ClickAction().setItem(Items.GLASS_BOTTLE));
            }
        }
        if (currentLevel.get() < requiredLevel.get()) {
<<<<<<< HEAD
            return this.buildWaterFillAction(requiredLevel.get());
=======
            if (InventoryUtils.playerHasAccessToItem(client.player, Items.POTION)) {
                return Result.success(new ClickAction().setItem(Items.POTION));
            }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
<<<<<<< HEAD
        if (requiredState.is(Blocks.WATER_CAULDRON) && currentState.is(Blocks.CAULDRON)) {
            int requiredLevel = getProperty(requiredState, LayeredCauldronBlock.LEVEL).orElse(1);
            return this.buildWaterFillAction(requiredLevel);
        }
        if (requiredState.is(Blocks.CAULDRON)
                && currentState.is(Blocks.WATER_CAULDRON)
                && InventoryUtils.playerHasAccessToItem(client.player, Items.GLASS_BOTTLE)) {
            return Result.success(new ClickAction().setItem(Items.GLASS_BOTTLE));
        }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && InteractionUtils.canBreakBlock(blockPos)) {
            InteractionUtils.INSTANCE.add(context);
        }
        return Result.SKIP;
    }
<<<<<<< HEAD

    private Result buildWaterFillAction(int requiredLevel) {
        if (requiredLevel == LayeredCauldronBlock.MAX_FILL_LEVEL
                && InventoryUtils.playerHasItemInInventory(client.player, Items.WATER_BUCKET)) {
            return Result.success(new ClickAction().setItem(Items.WATER_BUCKET));
        }
        ItemStack waterPotion = InventoryUtils.createWaterPotionStack();
        if (InventoryUtils.playerHasAccessToMatchingStack(
                client.player,
                waterPotion,
                InventoryUtils::isWaterPotion
        )) {
            return Result.success(new ClickAction()
                    .setItem(Items.POTION)
                    .setRequiredStackPredicate(InventoryUtils::isWaterPotion)
                    .setRequiredCreativeStack(waterPotion));
        }
        if (requiredLevel == LayeredCauldronBlock.MAX_FILL_LEVEL) {
            InventoryUtils.playerHasAccessToItem(client.player, Items.WATER_BUCKET);
        }
        return Result.SKIP;
    }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
}
