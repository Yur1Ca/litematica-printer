package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.world.item.Items;

/**
 * 攀爬植物
 */
public class ClimbingPlantGuide extends Guide {

    public ClimbingPlantGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        if (requiredBlock instanceof net.minecraft.world.level.block.BigDripleafStemBlock) {
            return Result.success(new Action().setItem(Items.BIG_DRIPLEAF));
        }
        if (requiredBlock instanceof net.minecraft.world.level.block.CaveVinesBlock
                || requiredBlock instanceof net.minecraft.world.level.block.CaveVinesPlantBlock) {
            return Result.success(new Action().setItem(Items.GLOW_BERRIES).setRequiresSupport());
        }
        if (requiredBlock instanceof net.minecraft.world.level.block.WeepingVinesBlock
                || requiredBlock instanceof net.minecraft.world.level.block.WeepingVinesPlantBlock) {
            return Result.success(new Action().setItem(Items.WEEPING_VINES).setRequiresSupport());
        }
        if (requiredBlock instanceof net.minecraft.world.level.block.TwistingVinesBlock
                || requiredBlock instanceof net.minecraft.world.level.block.TwistingVinesPlantBlock) {
            return Result.success(new Action().setItem(Items.TWISTING_VINES).setRequiresSupport());
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if ((requiredBlock instanceof net.minecraft.world.level.block.WeepingVinesBlock
                || requiredBlock instanceof net.minecraft.world.level.block.TwistingVinesBlock
                || requiredBlock instanceof net.minecraft.world.level.block.CaveVinesBlock)
                && requiredState.getValue(net.minecraft.world.level.block.GrowingPlantHeadBlock.AGE) == 25
                && currentState.getValue(net.minecraft.world.level.block.GrowingPlantHeadBlock.AGE) < 25) {
            return Result.success(new me.aleksilassila.litematica.printer.printer.action.ClickAction().setItem(Items.SHEARS));
        }
        return Result.SKIP;
    }
}
