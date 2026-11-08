package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Set;

/** Evaluates piston power after the owned torch removal without predicting world changes. */
final class BedrockPowerQuery {
    private static final Direction[] DIRECTIONS = Direction.values();

    private BedrockPowerQuery() { }

    static boolean hasResidualPower(ClientLevel level, BlockPos pistonPos, Direction primingFacing,
                                    Direction executeFacing, Set<BlockPos> ownedTorchPositions) {
        if (level == null || pistonPos == null || primingFacing == null || executeFacing == null
                || ownedTorchPositions == null || ownedTorchPositions.isEmpty()) return true;

        var afterTorchRemoval = new SignalView(level, ownedTorchPositions, null, null, null);
        if (isPowered(afterTorchRemoval, pistonPos, primingFacing)) return true;

        // An unextended replacement is a conductor; the old extended base is not.
        // Its own DOWN signal query must therefore use the replacement state too.
        var replacement = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, executeFacing);
        var afterReplacement = new SignalView(level, ownedTorchPositions, pistonPos, replacement,
                pistonPos.relative(primingFacing));
        return isPowered(afterReplacement, pistonPos, executeFacing);
    }

    private static boolean isPowered(SignalView view, BlockPos pos, Direction facing) {
        // Matches PistonBaseBlock.getNeighborSignal, including the self query and QC.
        for (Direction direction : DIRECTIONS) {
            if (direction != facing && view.hasSignal(pos.relative(direction), direction)) return true;
        }
        if (view.hasSignal(pos, Direction.DOWN)) return true;
        BlockPos above = pos.above();
        for (Direction direction : DIRECTIONS) {
            if (direction != Direction.DOWN && view.hasSignal(above.relative(direction), direction)) return true;
        }
        return false;
    }

    /** BlockGetter keeps this read-only signal view available on 1.18/1.19 as well. */
    private static final class SignalView implements BlockGetter {
        private final ClientLevel level;
        private final Set<BlockPos> removedTorches;
        private final BlockPos replacementPos;
        private final BlockState replacementState;
        private final BlockPos removedHead;

        private SignalView(ClientLevel level, Set<BlockPos> removedTorches, BlockPos replacementPos,
                           BlockState replacementState, BlockPos removedHead) {
            this.level = level;
            this.removedTorches = removedTorches;
            this.replacementPos = replacementPos;
            this.replacementState = replacementState;
            this.removedHead = removedHead;
        }

        @Override public BlockState getBlockState(BlockPos pos) {
            if (this.removedTorches.contains(pos) || pos.equals(this.removedHead)) return Blocks.AIR.defaultBlockState();
            return pos.equals(this.replacementPos) ? this.replacementState : this.level.getBlockState(pos);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return this.isOverridden(pos) ? null : this.level.getBlockEntity(pos);
        }

        @Override public FluidState getFluidState(BlockPos pos) {
            return this.isOverridden(pos) ? this.getBlockState(pos).getFluidState() : this.level.getFluidState(pos);
        }

        @Override public int getHeight() { return this.level.getHeight(); }
        @Override public int getMinY() { return this.level.getMinY(); }

        private boolean isOverridden(BlockPos pos) {
            return this.removedTorches.contains(pos) || pos.equals(this.replacementPos) || pos.equals(this.removedHead);
        }

        private boolean hasSignal(BlockPos pos, Direction direction) {
            // Same weak/strong conductor composition as vanilla SignalGetter.getSignal.
            // Query the masked view all the way through so owned torch power cannot
            // leak back in through a neighboring solid block.
            BlockState state = this.getBlockState(pos);
            int signal = state.getSignal(this, pos, direction);
            if (state.isRedstoneConductor(this, pos)) {
                for (Direction neighbor : DIRECTIONS) {
                    BlockPos source = pos.relative(neighbor);
                    signal = Math.max(signal, this.getBlockState(source).getDirectSignal(this, source, neighbor));
                    if (signal > 0) return true;
                }
            }
            return signal > 0;
        }
    }
}
