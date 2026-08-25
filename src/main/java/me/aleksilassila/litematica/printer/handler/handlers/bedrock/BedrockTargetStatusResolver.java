package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves observed target state while keeping recovery effects explicit on the host. */
final class BedrockTargetStatusResolver {
    private static final int POST_EXECUTE_SYNC_TIMEOUT_TICKS = 16;
    private static final int INITIALIZE_SYNC_GRACE_TICKS = 2;
    private static final int INITIALIZE_SYNC_TIMEOUT_TICKS = 40;
    private static final int POST_EXECUTE_AIR_SETTLE_TICKS = 4;
    private static final int MAX_STUCK_TICKS = 200;

    interface Host {
        ClientLevel level();
        BedrockMachineLayout layout();
        BlockPos bedrockPos();
        BlockPos pistonPos();
        BlockPos headPos();
        boolean hasTried();
        int executeTick();
        int initializeTick();
        int tickTimes();
        int stuckTicks();
        BedrockTarget.Status currentStatus();
        boolean hasOwnedTorchPowerSource();
        boolean hasMachineCleanupResidue();
        boolean hasPostExecuteSyncResidue();
        boolean hasTransientMachineResidue();
        boolean hasAnyTransientMachineResidue();
        boolean hasStablePostExecuteResidue();
        boolean hasPollutedMachineState();
        void cleanupPollutedMachineState();
        void cleanupStablePostExecuteResidue();
        void incrementStuckTicks();
        void resetPostExecuteAttempt(BedrockTarget.Status recoveryStatus);
    }

    private final Host host;

    BedrockTargetStatusResolver(Host host) {
        this.host = host;
    }

    BedrockTarget.Status resolve(boolean applyRecovery) {
        if (this.isTargetCompleted()) {
            return BedrockTarget.Status.RETRACTED;
        }
        if (this.host.stuckTicks() >= MAX_STUCK_TICKS) {
            if (applyRecovery) {
                this.host.resetPostExecuteAttempt(BedrockTarget.Status.UNINITIALIZED);
            }
            return BedrockTarget.Status.UNINITIALIZED;
        }
        ClientLevel level = this.host.level();
        BlockState bedrockState = level.getBlockState(this.host.bedrockPos());
        BlockState pistonState = level.getBlockState(this.host.pistonPos());
        BlockState headState = level.getBlockState(this.host.headPos());
        boolean targetBlock = BedrockTargetBlocks.isTargetBlock(bedrockState);
        if (!targetBlock && pistonState.is(Blocks.PISTON)) return BedrockTarget.Status.RETRACTED;
        if (pistonState.is(Blocks.MOVING_PISTON)) return BedrockTarget.Status.RETRACTING;
        if (!targetBlock && this.host.hasMachineCleanupResidue()) return BedrockTarget.Status.RETRACTED;
        if (this.hasExceededSyncWaitTimeout()) {
            BedrockTarget.Status recovery = this.getRecoverablePostExecuteStatus();
            if (recovery != null) {
                if (applyRecovery) this.host.resetPostExecuteAttempt(recovery);
                return recovery;
            }
            return BedrockTarget.Status.STUCK;
        }
        if (this.host.hasTried() && this.shouldRestartAfterPostExecuteCollapse()) {
            if (applyRecovery) this.host.resetPostExecuteAttempt(BedrockTarget.Status.UNINITIALIZED);
            return BedrockTarget.Status.UNINITIALIZED;
        }
        if (this.host.hasPollutedMachineState()) {
            if (applyRecovery) {
                this.host.cleanupPollutedMachineState();
                if (this.host.hasTried()) this.host.incrementStuckTicks();
            }
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (this.host.hasTried() && pistonState.isAir() && headState.is(Blocks.PISTON_HEAD)) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (this.host.hasTried()
                && pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (pistonState.is(Blocks.PISTON) && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            return BedrockTarget.Status.EXTENDED;
        }
        if (this.shouldCleanupStablePostExecuteResidue()) {
            if (applyRecovery) {
                this.host.cleanupStablePostExecuteResidue();
                this.host.incrementStuckTicks();
            }
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (this.shouldWaitForPostExecuteAirTransition()) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (!this.host.hasTried()
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
            if (facing == this.host.layout().getPrimingFacing()
                    || facing == this.host.layout().getExecuteFacing()) {
                return this.powerStatus();
            }
        }
        if (this.host.hasTried()
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)
                && pistonState.getValue(PistonBaseBlock.FACING) == this.host.layout().getPrimingFacing()
                && targetBlock) {
            BedrockTarget.Status result = this.powerStatus();
            if (applyRecovery) this.host.resetPostExecuteAttempt(result);
            return result;
        }
        if (this.host.hasTried()
                && (pistonState.is(Blocks.PISTON) || pistonState.isAir())
                && this.host.stuckTicks() < 15) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (this.host.hasTried() && this.host.hasPostExecuteSyncResidue()) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        BedrockTarget.Status recoverable = this.getRecoverableUnextendedStatus();
        if (recoverable != null) return recoverable;
        if (this.shouldWaitForInitializeSettle()) return BedrockTarget.Status.NEEDS_WAITING;
        if (applyRecovery
                && !this.host.hasTried()
                && this.host.initializeTick() >= 0
                && this.host.tickTimes() - this.host.initializeTick() <= INITIALIZE_SYNC_TIMEOUT_TICKS) {
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (!this.host.hasTried() && this.host.hasAnyTransientMachineResidue()) {
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (BedrockEnvironment.hasRoomForPiston(
                level, this.host.pistonPos(), this.host.layout().getPistonOffset())) {
            return BedrockTarget.Status.UNINITIALIZED;
        }
        if (pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.FACING) != this.host.layout().getPrimingFacing()
                && pistonState.getValue(PistonBaseBlock.FACING) != this.host.layout().getExecuteFacing()) {
            return BedrockTarget.Status.UNINITIALIZED;
        }
        if (applyRecovery) {
            BedrockMessages.actionBar("bedrockminer.fail.place.piston");
            return BedrockTarget.Status.FAILED;
        }
        return this.host.currentStatus();
    }

    private boolean hasExceededSyncWaitTimeout() {
        return this.host.hasTried()
                && this.host.executeTick() >= 0
                && this.host.tickTimes() - this.host.executeTick() >= POST_EXECUTE_SYNC_TIMEOUT_TICKS
                && (this.host.level().getBlockState(this.host.pistonPos()).isAir()
                || this.host.hasPostExecuteSyncResidue());
    }

    private boolean shouldRestartAfterPostExecuteCollapse() {
        if (!this.isPostExecuteCollapsed() || this.host.executeTick() < 0) return false;
        if (this.host.tickTimes() - this.host.executeTick() < POST_EXECUTE_AIR_SETTLE_TICKS) return false;
        if (this.host.hasStablePostExecuteResidue()) return false;
        return BedrockEnvironment.hasRoomForPiston(
                this.host.level(), this.host.pistonPos(), this.host.layout().getPistonOffset());
    }

    private boolean shouldCleanupStablePostExecuteResidue() {
        return this.isPostExecuteCollapsed()
                && this.host.executeTick() >= 0
                && this.host.tickTimes() - this.host.executeTick() >= POST_EXECUTE_AIR_SETTLE_TICKS
                && this.host.tickTimes() - this.host.executeTick() < POST_EXECUTE_SYNC_TIMEOUT_TICKS
                && this.host.hasStablePostExecuteResidue();
    }

    private boolean shouldWaitForPostExecuteAirTransition() {
        if (!this.host.hasTried() || !this.host.level().getBlockState(this.host.pistonPos()).isAir()) {
            return false;
        }
        if (this.host.hasTransientMachineResidue()) return true;
        return this.host.executeTick() >= 0
                && this.host.tickTimes() - this.host.executeTick() < POST_EXECUTE_AIR_SETTLE_TICKS
                && !BedrockEnvironment.hasRoomForPiston(
                this.host.level(), this.host.pistonPos(), this.host.layout().getPistonOffset());
    }

    private boolean shouldWaitForInitializeSettle() {
        return !this.host.hasTried()
                && this.host.initializeTick() >= 0
                && this.host.tickTimes() - this.host.initializeTick() <= INITIALIZE_SYNC_GRACE_TICKS
                && this.host.hasTransientMachineResidue();
    }

    private boolean isTargetCompleted() {
        return !BedrockTargetBlocks.isTargetBlock(
                this.host.level().getBlockState(this.host.bedrockPos()));
    }

    private BedrockTarget.Status getRecoverablePostExecuteStatus() {
        if (!this.host.hasTried() || this.host.executeTick() < 0) return null;
        if (this.shouldRestartAfterPostExecuteCollapse()) return BedrockTarget.Status.UNINITIALIZED;
        return this.getRecoverableUnextendedStatus();
    }

    private BedrockTarget.Status getRecoverableUnextendedStatus() {
        ClientLevel level = this.host.level();
        if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(this.host.bedrockPos()))) return null;
        BlockState pistonState = level.getBlockState(this.host.pistonPos());
        if (!pistonState.is(Blocks.PISTON) || pistonState.getValue(PistonBaseBlock.EXTENDED)) return null;
        Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
        if (facing != this.host.layout().getPrimingFacing()
                && facing != this.host.layout().getExecuteFacing()) return null;
        return this.powerStatus();
    }

    private boolean isPostExecuteCollapsed() {
        return this.host.hasTried()
                && this.host.level().getBlockState(this.host.pistonPos()).isAir()
                && !this.host.level().getBlockState(this.host.headPos()).is(Blocks.PISTON_HEAD);
    }

    private BedrockTarget.Status powerStatus() {
        return this.host.hasOwnedTorchPowerSource()
                ? BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                : BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }
}
