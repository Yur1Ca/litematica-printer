package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

/**
 * 告示牌
 */
public class SignGuide extends Guide {

    public SignGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, HorizontalDirectionalBlock.FACING).orElse(null);

        // 站立告示牌
        if (requiredBlock instanceof StandingSignBlock) {
            int rotation = getProperty(requiredState, StandingSignBlock.ROTATION).orElseThrow();
            return Result.success(new Action()
                    .setSides(Direction.DOWN)
                    .setLookRotation(rotation)
                    .setRequiresSupport());
        }

        // 墙壁告示牌
        if (requiredBlock instanceof WallSignBlock && facing != null) {
            return Result.success(new Action()
                    .setSides(facing.getOpposite())
                    .setLookDirection(facing.getOpposite())
                    .setRequiresSupport());
        }

        // 墙壁悬挂告示牌（WallHangingSignBlock）
        if (requiredBlock instanceof WallHangingSignBlock && facing != null) {
            List<Direction> sides = facing.getAxis() == Direction.Axis.X
                    ? List.of(Direction.NORTH, Direction.SOUTH)
                    : List.of(Direction.EAST, Direction.WEST);
            return Result.success(new Action()
                    .setSides(sides.toArray(new Direction[0]))
                    .setLookDirection(facing.getOpposite())
                    .setRequiresSupport());
        }

        // 天花板悬挂告示牌（CeilingHangingSignBlock）
        if (requiredBlock instanceof CeilingHangingSignBlock) {
            int rotation = getProperty(requiredState, CeilingHangingSignBlock.ROTATION).orElse((int) 0);
            boolean attached = getProperty(requiredState, BlockStateProperties.ATTACHED).orElse(false);
            return Result.success(new Action()
                    .setShift(attached)
                    .setSides(Direction.UP)
                    .setLookRotation(rotation)
                    .setRequiresSupport());
        }

        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && InteractionUtils.canBreakBlock(blockPos)) {
            boolean isLegitimateSign = currentBlock instanceof StandingSignBlock
                    || currentBlock instanceof WallSignBlock
                    || currentBlock instanceof WallHangingSignBlock
                    || currentBlock instanceof CeilingHangingSignBlock
                    ;
            if (!isLegitimateSign) {
                InteractionUtils.getRuntime().add(context);
            }
        }
        return Result.SKIP;
    }
}
