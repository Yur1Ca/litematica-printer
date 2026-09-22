package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public class WaterGuide extends Guide {
    public WaterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return isFluidTarget(requiredState);
    }

    @Override
    protected Result onBuildAction(BlockMatchResult state) {
        if (shouldSkipWaterloggedTarget()) {
            return Result.SKIP;
        }
        if (state == BlockMatchResult.WRONG_BLOCK
                && Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && !(currentState.getBlock() instanceof LiquidBlock)) {
            if (InteractionUtils.canBreakBlock(blockPos)
                    && InteractionUtils.breakRestriction(currentState)) {
                InteractionUtils.getRuntime().add(context);
            }
            return Result.SKIP;
        }
        if (isWaterloggedTarget()) {
            return Result.PASS;
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        return isWaterloggedTarget() ? Result.PASS : Result.SKIP;
    }

    private boolean shouldSkipWaterloggedTarget() {
        return Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && isWaterloggedTarget();
    }

    private boolean isWaterloggedTarget() {
        return requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                && requiredState.getValue(BlockStateProperties.WATERLOGGED);
    }

    static boolean isFluidTarget(BlockState state) {
        return BlockStateUtils.isWaterBlock(state) || state.getBlock() instanceof LiquidBlock;
    }
}
