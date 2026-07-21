package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

/**
 * 侦测器
 */
public class ObserverGuide extends Guide {

    public ObserverGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, ObserverBlock.FACING).orElseThrow();

        if (!Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
            return Result.success(new Action()
                    .setLookDirection(facing)
                    .setNeedWaitModifyLook());
        }

        // 安全放置模式
        SchematicBlockContext input = context.offset(facing);          // 输入端（侦测面）
        SchematicBlockContext output = context.offset(facing.getOpposite()); // 输出端（红点面）

        // 获取输入端方块需要忽略的属性
        List<Property<?>> inputPropertiesToIgnore = new ArrayList<>();
        if (input.requiredState.getBlock() instanceof WallBlock) {
            BlockStateUtils.getWallFacingProperty(facing.getOpposite()).ifPresent(inputPropertiesToIgnore::add);
        }
        if (input.requiredState.getBlock() instanceof CrossCollisionBlock) {
            BlockStateUtils.getCrossCollisionBlock(facing.getOpposite()).ifPresent(inputPropertiesToIgnore::add);
        }

        BlockMatchResult inputState = BlockMatchResult.compare(input, inputPropertiesToIgnore.toArray(new Property<?>[0]));
        BlockMatchResult outputState = BlockMatchResult.compare(output);

        // 输入端与输出端均正确
        if (inputState == BlockMatchResult.CORRECT && outputState == BlockMatchResult.CORRECT) {
            if (!isObserverInputChainReady(input)) {
                return Result.SKIP;
            }
            return Result.success(placementAction(facing));
        }

        // 输入端正确但输出端有问题
        if (inputState == BlockMatchResult.CORRECT) {
            // 检查输入端后面的落地方块链
            SchematicBlockContext temp = input;
            while (temp.requiredState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
                SchematicBlockContext offset = temp.offset(Direction.DOWN);
                if (BlockMatchResult.compare(offset) != BlockMatchResult.CORRECT) {
                    return Result.SKIP;
                }
                temp = offset;
            }
<<<<<<< HEAD
            if (!isObserverInputChainReady(input)) {
                return Result.SKIP;
=======
            if (!output.requiredState.isAir()) {
                if (!isObserverInputChainReady(input)) {
                    return Result.SKIP;
                }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }

            // 侦测器隔空激活活塞检查
            for (Direction direction : Direction.values()) {
                SchematicBlockContext offset = output.offset(direction);
                if (offset.blockPos.equals(output.blockPos)) continue;
                if (offset.blockPos.equals(input.blockPos)) continue;
                if (offset.blockPos.equals(blockPos)) continue;
                if (offset.requiredState.getBlock() instanceof PistonBaseBlock) {
                    if (!offset.currentState.isAir()) {
                        return Result.SKIP;
                    }
                }
            }
        } else if (inputState == BlockMatchResult.WRONG_STATE) {
            return Result.SKIP;
        } else {
            if (!output.requiredState.isAir()) {
                if (output.currentState.isAir() && input.requiredState.getBlock() instanceof WallBlock) {
                    return Result.success(placementAction(facing).setCooldownTicksOverride(2));
                }
                return Result.SKIP;
            } else {
<<<<<<< HEAD
                // 输出端为空不代表输入端已经安全。安全模式必须等侦测面链条就绪，
                // 否则放置瞬间仍可能产生非原理图预期的更新脉冲。
                return Result.SKIP;
=======
                if (isObserverInputChainReady(input)) {
                    return Result.success(placementAction(facing));
                }
                if (!isObserverInputChainReady(output)) {
                    return Result.SKIP;
                }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
        }

        return Result.success(placementAction(facing));
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }

    private static Action placementAction(Direction facing) {
        return new Action()
                .setLookDirection(facing)
                .setNeedWaitModifyLook();
    }

    private static boolean isObserverInputChainReady(SchematicBlockContext start) {
        Set<BlockPos> visited = new HashSet<>();
        SchematicBlockContext temp = start;
        while (temp.requiredState.getBlock() instanceof ObserverBlock) {
            if (!visited.add(temp.blockPos)) {
                return true;
            }
            Direction tempFacing = BlockStateUtils.getProperty(temp.requiredState, ObserverBlock.FACING).orElse(null);
            if (tempFacing == null) {
                return false;
            }
            SchematicBlockContext offset = temp.offset(tempFacing);
            if (BlockMatchResult.compare(offset) != BlockMatchResult.CORRECT) {
                return false;
            }
            temp = offset;
        }
        return true;
    }
}
