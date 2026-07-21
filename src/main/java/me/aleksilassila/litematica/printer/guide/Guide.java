package me.aleksilassila.litematica.printer.guide;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public abstract class Guide extends BlockStateUtils {
    protected final SchematicBlockContext context;
    public final Minecraft client;
    public final ClientLevel level;
    public final WorldSchematic schematic;
    public final BlockPos blockPos;
    public final BlockState currentState;
    public final BlockState requiredState;
    protected final Block currentBlock;
    protected final Block requiredBlock;

    public Guide(SchematicBlockContext context) {
        this.context = context;
        this.client = context.client;
        this.level = context.level;
        this.schematic = context.schematic;
        this.blockPos = context.blockPos;
        this.currentBlock = context.currentState.getBlock();
        this.requiredBlock = context.requiredState.getBlock();
        this.currentState = context.currentState;
        this.requiredState = context.requiredState;
    }

    /**
     * 构建 Action 的入口方法。
     */
    public final Result buildAction(BlockMatchResult state) {
        // 前置检查: 完全一致
        if (state == BlockMatchResult.CORRECT) {
            return this.onBuildActionCorrect(state);
        }

        if (state == BlockMatchResult.MISSING) {
            // 水相关方块由 WaterGuide 特判处理，不能在这里提前拦掉
            if (!BlockStateUtils.isWaterBlock(requiredState) && !requiredState.canSurvive(level, blockPos)) {
                return Result.PASS;
            }
            // 双格方块的上半部分由下半部分生成，缺失时不独立放置
            if (requiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                return Result.PASS;
            }
        }

        // 水生植物（海草等）需要水环境才能放置
        if (BlockStateUtils.requiresWaterToPlace(requiredBlock)) {
<<<<<<< HEAD
            if (!BlockStateUtils.hasSourceWaterFluid(level.getBlockState(blockPos))) {
=======
            BlockPos waterPos = requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                    ? blockPos : blockPos.above();
            if (!BlockStateUtils.hasSourceWaterFluid(level.getBlockState(waterPos))) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                return Result.PASS;
            }
        }

        // 交给子类的 onBuildAction 拦截钩子
        Result result = this.onBuildAction(state);
        if (!result.passToNext() || result.skipOtherGuide()) {
            return result;
        }

        // 分状态分发
        return switch (state) {
            case MISSING -> this.onBuildActionMissingBlock(state);
            case WRONG_BLOCK -> this.onBuildActionWrongBlock(state);
            case WRONG_STATE -> this.onBuildActionWrongState(state);
            default -> Result.PASS;
        };
    }

    /**
     * 检查此 Guide 是否应该处理当前方块
     * 可被子类覆盖以实现更细粒度的过滤
     * @return true 表示应该执行此 Guide
     */
    protected boolean canExecute() {
        return true;
    }

    // -------------------------------------------------------
    // 子类钩子
    // -------------------------------------------------------

    /**
     * 所有状态均会先经过此钩子，可在此拦截任意状态
     */
    protected Result onBuildAction(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 位置为空气 / 可替换方块：需要放置
     */
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 方块类型完全不同：需要先破坏再放置
     */
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 方块类型相同但状态不对：可能需要交互修正
     */
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 完全正确：通常无需操作
     */
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        return Result.PASS;
    }

}
