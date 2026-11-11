package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 花簇
 */
public class FlowerBedGuide extends Guide {

    public FlowerBedGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        int requiredAmount = getProperty(requiredState, BlockStateProperties.FLOWER_AMOUNT).orElse(1);
        int currentAmount = getProperty(currentState, BlockStateProperties.FLOWER_AMOUNT).orElse(1);
        if (currentAmount <= requiredAmount) {
            return Result.success(new ClickAction().setItem(requiredBlock.asItem()));
        }
        return Result.SKIP;
    }
}
