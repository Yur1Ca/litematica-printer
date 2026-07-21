package me.aleksilassila.litematica.printer.mixin_extension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

@SuppressWarnings("UnusedReturnValue")
public interface MultiPlayerGameModeExtension {
    InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit);

    default BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction) {
        return this.litematica_printer$continueDestroyBlock(localPrediction, blockPos, direction, false);
    }

    default BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction, boolean forceDelayedDestroy) {
        return this.litematica_printer$continueDestroyBlock(localPrediction, blockPos, direction, forceDelayedDestroy, true);
    }

    BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction, boolean forceDelayedDestroy, boolean allowToolSwitch);

    default BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos blockPos, Direction direction) {
        return this.litematica_printer$continueDestroyBlockForMine(blockPos, direction, true);
    }

    default BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos blockPos, Direction direction, boolean allowToolSwitch) {
        return this.litematica_printer$continueDestroyBlock(false, blockPos, direction, false, allowToolSwitch);
    }

    default boolean litematica_printer$isPendingDelayedDestroy(BlockPos blockPos) {
        return false;
    }
<<<<<<< HEAD

    default void litematica_printer$resetRuntime() {
    }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
}
