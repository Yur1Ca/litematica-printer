package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Classifies and cleans transient machine residue for one target. */
final class BedrockTargetResidue {
    private static final int CLEANUP_INTERVAL_TICKS = 4;

    private final ClientLevel level;
    private final BedrockMachineLayout layout;
    private final BlockPos pistonPos;
    private final BlockPos headPos;
    private int lastStableCleanupTick = -1;
    private int lastPollutedCleanupTick = -1;

    BedrockTargetResidue(
            ClientLevel level,
            BedrockMachineLayout layout,
            BlockPos pistonPos,
            BlockPos headPos
    ) {
        this.level = level;
        this.layout = layout;
        this.pistonPos = pistonPos;
        this.headPos = headPos;
    }

    void resetAttempt() {
        this.lastStableCleanupTick = -1;
        this.lastPollutedCleanupTick = -1;
    }

    boolean hasCleanupResidue(BlockPos pos) {
        return pos != null && BedrockTargetBlocks.isCleanupResidue(this.level.getBlockState(pos));
    }

    boolean hasMachineCleanupResidue(BlockPos slimePos, BlockPos torchPos) {
        return this.hasCleanupResidue(this.pistonPos)
                || this.hasCleanupResidue(this.headPos)
                || this.hasCleanupResidue(slimePos)
                || this.hasCleanupResidue(torchPos);
    }

    boolean hasPostExecuteSyncResidue() {
        return this.hasCleanupResidue(this.pistonPos) || this.hasCleanupResidue(this.headPos);
    }

    boolean isPostExecuteCollapsed(boolean hasTried) {
        return hasTried
                && this.level.getBlockState(this.pistonPos).isAir()
                && !this.level.getBlockState(this.headPos).is(Blocks.PISTON_HEAD);
    }

    boolean hasTransientMachineResidue() {
        return this.isTransientMachineResidue(this.pistonPos, false)
                || this.isTransientMachineResidue(this.headPos, true);
    }

    boolean hasAnyTransientMachineResidue(BlockPos torchSupportPos, BlockPos slimePos) {
        return this.isTransientMachineResidue(this.pistonPos, false)
                || this.isTransientMachineResidue(this.headPos, true)
                || this.isTransientMachineResidue(torchSupportPos, false)
                || this.isTransientMachineResidue(slimePos, false);
    }

    boolean hasStablePostExecuteResidue() {
        return this.isStablePostExecuteResidue(this.pistonPos, false)
                || this.isStablePostExecuteResidue(this.headPos, true);
    }

    boolean cleanupStablePostExecuteResidue(int tickTimes) {
        if (this.lastStableCleanupTick >= 0
                && tickTimes - this.lastStableCleanupTick < CLEANUP_INTERVAL_TICKS) {
            return false;
        }
        boolean headResidue = this.isStablePostExecuteResidue(this.headPos, true);
        boolean pistonResidue = this.isStablePostExecuteResidue(this.pistonPos, false);
        if (!headResidue && !pistonResidue) {
            return false;
        }
        this.lastStableCleanupTick = tickTimes;
        if (headResidue) {
            BedrockBreaker.breakBlock(this.headPos, false);
        }
        if (pistonResidue) {
            BedrockBreaker.breakBlock(this.pistonPos, false);
        }
        return true;
    }

    boolean hasPollutedMachineState(
            boolean hasTried,
            int executeTick,
            int tickTimes,
            int syncTimeoutTicks,
            BedrockTorchPlacement torchPlacement,
            BlockPos torchSupportPos,
            BlockPos slimePos
    ) {
        return this.isPollutedPistonState()
                || this.isPollutedHeadState(hasTried, executeTick, tickTimes, syncTimeoutTicks)
                || this.isPollutedTorchSupportState(torchPlacement, torchSupportPos, slimePos)
                || this.isPollutedTorchState(torchPlacement)
                || this.isPollutedSlimeSupportState(slimePos);
    }

    boolean cleanupPollutedMachineState(
            int tickTimes,
            boolean hasTried,
            int executeTick,
            int syncTimeoutTicks,
            BedrockTorchPlacement torchPlacement,
            BlockPos torchSupportPos,
            BlockPos slimePos
    ) {
        if (this.lastPollutedCleanupTick >= 0
                && tickTimes - this.lastPollutedCleanupTick < CLEANUP_INTERVAL_TICKS) {
            return false;
        }
        boolean pollutedPiston = this.isPollutedPistonState();
        boolean pollutedHead = this.isPollutedHeadState(hasTried, executeTick, tickTimes, syncTimeoutTicks);
        boolean pollutedTorchSupport = this.isPollutedTorchSupportState(
                torchPlacement, torchSupportPos, slimePos);
        boolean pollutedTorch = this.isPollutedTorchState(torchPlacement);
        boolean pollutedSlime = this.isPollutedSlimeSupportState(slimePos);
        if (!pollutedPiston && !pollutedHead && !pollutedTorchSupport
                && !pollutedTorch && !pollutedSlime) {
            return false;
        }
        this.lastPollutedCleanupTick = tickTimes;
        if (pollutedTorch) BedrockBreaker.breakBlock(torchPlacement.getTorchPos(), false);
        if (pollutedHead) BedrockBreaker.breakBlock(this.headPos, false);
        if (pollutedPiston) BedrockBreaker.breakBlock(this.pistonPos, false);
        if (pollutedTorchSupport) BedrockBreaker.breakBlock(torchSupportPos, false);
        if (pollutedSlime) BedrockBreaker.breakBlock(slimePos, false);
        return true;
    }

    private boolean isStablePostExecuteResidue(BlockPos pos, boolean headSlot) {
        if (pos == null) {
            return false;
        }
        BlockState state = this.level.getBlockState(pos);
        return BedrockTargetBlocks.isCleanupResidue(state)
                && !this.isTransientMachineResidue(pos, headSlot);
    }

    private boolean isTransientMachineResidue(BlockPos pos, boolean headSlot) {
        if (pos == null) {
            return false;
        }
        BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.is(Blocks.MOVING_PISTON)) return true;
        if (headSlot && state.is(Blocks.PISTON_HEAD)) return true;
        if (state.is(Blocks.PISTON) && pos.equals(this.pistonPos) && this.layout != null) {
            Direction facing = state.getValue(PistonBaseBlock.FACING);
            boolean validFacing = facing == this.layout.getPrimingFacing()
                    || facing == this.layout.getExecuteFacing();
            return !validFacing || state.getValue(PistonBaseBlock.EXTENDED);
        }
        return false;
    }

    private boolean isPollutedPistonState() {
        BlockState state = this.level.getBlockState(this.pistonPos);
        if (state.isAir() || state.is(Blocks.MOVING_PISTON) || state.is(Blocks.PISTON)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedHeadState(
            boolean hasTried,
            int executeTick,
            int tickTimes,
            int syncTimeoutTicks
    ) {
        BlockState state = this.level.getBlockState(this.headPos);
        if (state.isAir() || state.is(Blocks.MOVING_PISTON) || state.is(Blocks.PISTON_HEAD)) {
            return false;
        }
        if (hasTried && executeTick >= 0
                && tickTimes - executeTick < syncTimeoutTicks
                && state.is(Blocks.PISTON)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedTorchState(BedrockTorchPlacement placement) {
        if (placement == null || placement.getTorchPos() == null) {
            return false;
        }
        BlockState state = this.level.getBlockState(placement.getTorchPos());
        return !state.isAir()
                && !isExpectedTorchState(state, placement)
                && BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedTorchSupportState(
            BedrockTorchPlacement placement,
            BlockPos torchSupportPos,
            BlockPos slimePos
    ) {
        if (torchSupportPos == null) return false;
        BlockState state = this.level.getBlockState(torchSupportPos);
        if (state.isAir() || (slimePos != null && torchSupportPos.equals(slimePos))) return false;
        if (state.is(Blocks.SLIME_BLOCK)
                && placement != null
                && BedrockEnvironment.isTorchPlacementUsable(this.level, placement)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedSlimeSupportState(BlockPos slimePos) {
        if (slimePos == null) return false;
        BlockState state = this.level.getBlockState(slimePos);
        return !state.isAir()
                && !state.is(Blocks.SLIME_BLOCK)
                && BedrockTargetBlocks.isCleanupResidue(state);
    }

    private static boolean isExpectedTorchState(
            BlockState state,
            BedrockTorchPlacement placement
    ) {
        if (placement.getClickedFace() == null) return false;
        return placement.getClickedFace() == Direction.UP
                ? state.is(Blocks.REDSTONE_TORCH)
                : state.is(Blocks.REDSTONE_WALL_TORCH);
    }
}
