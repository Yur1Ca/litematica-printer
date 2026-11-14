package me.aleksilassila.litematica.printer.guide;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.function.Predicate;

/** A missing upper plant half requires resetting its confirmed lower half before replanting. */
final class DoublePlantRepair {
    private DoublePlantRepair() { }

    static void queueLowerResetIfNeeded(SchematicBlockContext context, Predicate<BlockPos> repairAllowed) {
        BlockState upper = context.requiredState;
        if (!(upper.getBlock() instanceof DoublePlantBlock)
                || upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER
                || BlockMatchResult.compare(context) != BlockMatchResult.MISSING) {
            return;
        }
        BlockPos lowerPos = context.blockPos.below();
        BlockState lower = upper.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        if (!stillNeeded(context, lowerPos, lower, repairAllowed)) return;

        // Vanilla cannot replace an existing lower half with the same plant item. The ordinary
        // lower-half Guide will replant only after the existing break path observes its removal.
        InteractionUtils.getRuntime().add(lowerPos,
                () -> stillNeeded(context, lowerPos, lower, repairAllowed), () -> { });
    }

    private static boolean stillNeeded(SchematicBlockContext context, BlockPos lowerPos,
                                       BlockState lower, Predicate<BlockPos> repairAllowed) {
        if (!Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue()
                || context.client.level != context.level
                || !repairAllowed.test(lowerPos)
                || !repairAllowed.test(context.blockPos)
                || !context.schematic.getBlockState(context.blockPos).equals(context.requiredState)
                || !context.schematic.getBlockState(lowerPos).equals(lower)
                || !context.level.getBlockState(lowerPos).equals(lower)) {
            return false;
        }
        var live = new SchematicBlockContext(context.client, context.level, context.schematic, context.blockPos);
        return BlockMatchResult.compare(live) == BlockMatchResult.MISSING
                && lower.canSurvive(context.level, lowerPos)
                && InteractionUtils.canBreakBlock(lowerPos)
                && InteractionUtils.breakRestriction(lower);
    }
}
