package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Classifies and cleans transient machine residue for one target. */
final class BedrockTargetResidue {
    private final ClientLevel level;
    private final BedrockMachineLayout layout;
    private final BlockPos pistonPos;
    private final BlockPos headPos;
    private final BedrockPlacer placer;

    BedrockTargetResidue(ClientLevel level, BedrockMachineLayout layout,
                         BlockPos pistonPos, BlockPos headPos, BedrockPlacer placer) {
        this.level = level;
        this.layout = layout;
        this.pistonPos = pistonPos;
        this.headPos = headPos;
        this.placer = placer;
    }

    boolean hasRecoveryResidue(java.util.Set<BlockPos> torches) {
        return !torches.isEmpty() || this.hasCleanupResidue(this.pistonPos) || this.hasCleanupResidue(this.headPos);
    }

    void cleanupRecoveryResidue(java.util.Set<BlockPos> torches) {
        if (!this.hasRecoveryResidue(torches)) return;
        for (BlockPos torch : torches) BedrockBreaker.breakBlock(torch, Direction.DOWN, this.placer);
        if (this.hasCleanupResidue(this.headPos)) BedrockBreaker.breakBlock(this.headPos, this.placer);
        if (this.hasCleanupResidue(this.pistonPos)) BedrockBreaker.breakBlock(this.pistonPos, this.placer);
    }

    boolean hasCleanupResidue(BlockPos pos) {
        return pos != null && BedrockTargetBlocks.isCleanupResidue(this.level.getBlockState(pos));
    }

    boolean hasAnyTransientMachineResidue(BlockPos torchSupportPos, BlockPos slimePos) {
        return this.isTransientMachineResidue(this.pistonPos, false)
                || this.isTransientMachineResidue(this.headPos, true)
                || this.isTransientMachineResidue(torchSupportPos, false)
                || this.isTransientMachineResidue(slimePos, false);
    }

    boolean hasPollutedMachineState(
            BedrockTargetState state,
            int syncTimeoutTicks,
            BedrockTargetMachine machine
    ) {
        return this.hasPollutedMachineState(
                state.hasTried(), state.executeTick(), state.tickTimes(), syncTimeoutTicks,
                machine.torchPlacement(), machine.torchSupportPos(), machine.slimePos());
    }

    void cleanupPollutedMachineState(
            BedrockTargetState state,
            int syncTimeoutTicks,
            BedrockTargetMachine machine
    ) {
        this.cleanupPollutedMachineState(
                state.tickTimes(), state.hasTried(), state.executeTick(), syncTimeoutTicks,
                machine.torchPlacement(), machine.torchSupportPos(), machine.slimePos());
    }

    private boolean hasPollutedMachineState(
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

    private void cleanupPollutedMachineState(
            int tickTimes,
            boolean hasTried,
            int executeTick,
            int syncTimeoutTicks,
            BedrockTorchPlacement torchPlacement,
            BlockPos torchSupportPos,
            BlockPos slimePos
    ) {
        boolean pollutedPiston = this.isPollutedPistonState();
        boolean pollutedHead = this.isPollutedHeadState(hasTried, executeTick, tickTimes, syncTimeoutTicks);
        boolean pollutedTorchSupport = this.isPollutedTorchSupportState(
                torchPlacement, torchSupportPos, slimePos);
        boolean pollutedTorch = this.isPollutedTorchState(torchPlacement);
        boolean pollutedSlime = this.isPollutedSlimeSupportState(slimePos);
        if (!pollutedPiston && !pollutedHead && !pollutedTorchSupport
                && !pollutedTorch && !pollutedSlime) {
            return;
        }
        if (pollutedTorch) BedrockBreaker.breakBlock(torchPlacement.getTorchPos(), this.placer);
        if (pollutedHead) BedrockBreaker.breakBlock(this.headPos, this.placer);
        if (pollutedPiston) BedrockBreaker.breakBlock(this.pistonPos, this.placer);
        if (pollutedTorchSupport) BedrockBreaker.breakBlock(torchSupportPos, this.placer);
        if (pollutedSlime) BedrockBreaker.breakBlock(slimePos, this.placer);
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
