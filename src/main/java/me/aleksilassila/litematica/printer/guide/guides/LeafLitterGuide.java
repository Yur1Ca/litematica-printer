package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Segment stacking adapted from BiliXWhite/litematica-printer@6cef6d31. */
public final class LeafLitterGuide extends Guide {
    public LeafLitterGuide(SchematicBlockContext context) { super(context); }

    @Override protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(new Action().setSides(Direction.DOWN).setRequiresSupport()
                .setLookDirection(requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()));
    }

    @Override protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (currentState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                != requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING)) return Result.PASS;
        return currentState.getValue(BlockStateProperties.SEGMENT_AMOUNT)
                < requiredState.getValue(BlockStateProperties.SEGMENT_AMOUNT)
                ? Result.success(new ClickAction().setItem(requiredBlock.asItem())) : Result.PASS;
    }
}
