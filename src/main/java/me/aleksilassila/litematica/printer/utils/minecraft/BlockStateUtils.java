package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

@SuppressWarnings("EnhancedSwitchMigration")
public class BlockStateUtils extends BlockUtils {
    private final static BooleanProperty wallUpProperty = BlockStateProperties.UP;
    private final static EnumProperty<WallSide> wallNorthProperty = BlockStateProperties.NORTH_WALL;
    private final static EnumProperty<WallSide> wallSouthProperty = BlockStateProperties.SOUTH_WALL;
    private final static EnumProperty<WallSide> wallWestProperty = BlockStateProperties.WEST_WALL;
    private final static EnumProperty<WallSide> wallEastProperty = BlockStateProperties.EAST_WALL;
    private final static BooleanProperty northProperty = BlockStateProperties.NORTH;
    private final static BooleanProperty southProperty = BlockStateProperties.SOUTH;
    private final static BooleanProperty westProperty = BlockStateProperties.WEST;
    private final static BooleanProperty eastProperty = BlockStateProperties.EAST;

    public static boolean statesEqualIgnoreProperties(BlockState state1, BlockState state2, Property<?>... propertiesToIgnore) {
        if (state1.getBlock() != state2.getBlock()) {
            return false;
        }
        loop:
        for (Property<?> property : state1.getProperties()) {
            if (property == BlockStateProperties.WATERLOGGED && !(state1.getBlock() instanceof CoralPlantBlock)) {
                continue;
            }
            for (Property<?> ignoredProperty : propertiesToIgnore) {
                if (property == ignoredProperty) {
                    continue loop;
                }
            }
            try {
                if (!state1.getValue(property).equals(state2.getValue(property))) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public static <T extends Comparable<T>> Optional<T> getProperty(BlockState blockState, Property<T> property) {
        if (blockState.hasProperty(property)) {
            return Optional.of(blockState.getValue(property));
        }
        return Optional.empty();
    }

    public static boolean statesEqual(BlockState state1, BlockState state2) {
        return statesEqualIgnoreProperties(state1, state2);
    }

    protected static boolean canBeClicked(Level world, BlockPos pos) {
        return getOutlineShape(world, pos) != Shapes.empty();
    }

    private static VoxelShape getOutlineShape(Level level, BlockPos pos) {
        return level.getBlockState(pos).getShape(level, pos);
    }

    private static VoxelShape getOutlineShape(BlockState state, Level level, BlockPos pos) {
        return state.getShape(level, pos);
    }

    public static Optional<Property<?>> getWallFacingProperty(Direction wallFacing) {
        switch (wallFacing) {
            case UP:
                return Optional.of(wallUpProperty);
            case NORTH:
                return Optional.of(wallNorthProperty);
            case SOUTH:
                return Optional.of(wallSouthProperty);
            case WEST:
                return Optional.of(wallWestProperty);
            case EAST:
                return Optional.of(wallEastProperty);
        }
        return Optional.empty();
    }

    public static Optional<Property<?>> getCrossCollisionBlock(Direction wallFacing) {
        switch (wallFacing) {
            case NORTH:
                return Optional.of(northProperty);
            case SOUTH:
                return Optional.of(southProperty);
            case WEST:
                return Optional.of(westProperty);
            case EAST:
                return Optional.of(eastProperty);
        }
        return Optional.empty();
    }

    /**
     * 判断该方块是否是含水方块
     *
     * @param blockState 要判断的方块
     * @return 是否含水（是水）
     */
    public static boolean isWaterBlock(BlockState blockState) {
        return blockState.is(Blocks.WATER) && blockState.getValue(LiquidBlock.LEVEL) == 0
                || (blockState.hasProperty(BlockStateProperties.WATERLOGGED) && blockState.getValue(BlockStateProperties.WATERLOGGED))
                || blockState.getBlock() instanceof BubbleColumnBlock;
    }

    public static boolean hasSourceWaterFluid(BlockState blockState) {
        FluidState fluidState = blockState.getFluidState();
        return fluidState.is(FluidTags.WATER) && fluidState.isSource();
    }

    private static boolean isSourceWaterOrBubbleColumn(BlockState blockState) {
        if (!hasSourceWaterFluid(blockState)) {
            return false;
        }
        return blockState.is(Blocks.WATER) || blockState.getBlock() instanceof BubbleColumnBlock;
    }

    /**
     * 判断该方块是否需要水中才能放置（水生植物等）。
     * 这些方块虽然 canSurvive 可能返回 true（只检查支撑），但实际放置需要水。
     * 没有水时跳过放置，避免死循环切换物品。
     *
     * @param block 要判断的方块
     * @return 是否需要水环境
     */
    public static boolean requiresWaterToPlace(Block block) {
        return block instanceof SeagrassBlock
                || block instanceof TallSeagrassBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock;
    }

    public static boolean isCorrectWaterLevel(BlockState requiredState, BlockState currentState) {
        if (requiredState.is(Blocks.WATER)) {
            if (currentState.is(Blocks.WATER)) {
                return currentState.getValue(LiquidBlock.LEVEL).equals(requiredState.getValue(LiquidBlock.LEVEL));
            }
            return requiredState.getValue(LiquidBlock.LEVEL) == 0
                    && currentState.getBlock() instanceof BubbleColumnBlock
                    && hasSourceWaterFluid(currentState);
        }
        if (requiredState.getBlock() instanceof BubbleColumnBlock) {
            return isSourceWaterOrBubbleColumn(currentState);
        }
        return isSourceWaterOrBubbleColumn(currentState);
    }
}
