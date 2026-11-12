package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Separates observation of a dispatched event from preparation of the next machine. */
final class BedrockTargetStatusResolver {
    private static final int POST_EXECUTE_SETTLE_TICKS = 4;
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
        boolean initialExtensionObserved();
        void observeInitialExtension();
        boolean initialRequestAcknowledged();
        int tickTimes();
        int stuckTicks();
        BedrockTarget.Status currentStatus();
        boolean hasOwnedTorchPowerSource();
        boolean hasDeferredInitialTorch();
        BedrockPlacer placer();
        boolean hasAnyTransientMachineResidue();
        boolean hasPollutedMachineState();
        void cleanupPollutedMachineState();
        void incrementStuckTicks();
        int postExecuteSyncTimeoutTicks();
        void recordNetworkTimeout();
        void resetPostExecuteAttempt(BedrockTarget.Status recoveryStatus);
        boolean rebuildLimitReached();
        boolean hasRecoveryResidue();
    }

    private final Host host;

    BedrockTargetStatusResolver(Host host) {
        this.host = host;
    }

    BedrockTarget.Status resolve(boolean applyRecovery) {
        ClientLevel level = this.host.level();
        if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(this.host.bedrockPos()))) {
            return BedrockTarget.Status.RETRACTED;
        }
        // No preparation, cleanup or power mutation is permitted while this event is in flight.
        if (this.host.hasTried()) return this.resolveDispatchedAttempt(applyRecovery);
        if (this.host.currentStatus() == BedrockTarget.Status.RECOVERING) {
            if (this.host.tickTimes() >= MAX_STUCK_TICKS) return BedrockTarget.Status.STUCK;
            if (this.host.rebuildLimitReached()) return BedrockTarget.Status.FAILED;
            return this.host.hasRecoveryResidue()
                    ? BedrockTarget.Status.RECOVERING : BedrockTarget.Status.UNINITIALIZED;
        }
        if (this.host.stuckTicks() >= MAX_STUCK_TICKS) return BedrockTarget.Status.STUCK;

        BlockState pistonState = level.getBlockState(this.host.pistonPos());
        boolean primingPiston = pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.FACING) == this.host.layout().getPrimingFacing();
        if (primingPiston && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            this.host.observeInitialExtension();
            return BedrockTarget.Status.EXTENDED;
        }
        if (primingPiston && this.host.hasDeferredInitialTorch()
                && !this.host.placer().isInventorySettling() && !this.host.hasOwnedTorchPowerSource()) {
            return BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
        }
        // A confirmed initial extension does not regain cold-start grace after losing its torch.
        if (primingPiston && this.host.initialExtensionObserved() && !this.host.hasOwnedTorchPowerSource()
                && !this.host.hasAnyTransientMachineResidue() && !this.host.hasPollutedMachineState()) {
            return BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
        }
        // The exact initial request has been processed, including rejected placements.
        // Keep animation, pollution and powered-stall observation on their existing paths.
        if (primingPiston && !this.host.layout().isHorizontal() && this.host.initialRequestAcknowledged()
                && !this.host.hasOwnedTorchPowerSource() && !this.host.hasAnyTransientMachineResidue()
                && !this.host.hasPollutedMachineState()) {
            return BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
        }
        // The base, head and power updates can arrive separately during initialization.
        if (this.host.initializeTick() >= 0
                && this.host.tickTimes() - this.host.initializeTick() < this.host.postExecuteSyncTimeoutTicks()) {
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (this.host.hasPollutedMachineState()) {
            if (applyRecovery) {
                this.host.cleanupPollutedMachineState();
                this.host.incrementStuckTicks();
            }
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        if (pistonState.is(Blocks.PISTON) && !primingPiston) {
            return BedrockTarget.Status.RECOVERING;
        }
        if (this.host.hasAnyTransientMachineResidue()) {
            if (applyRecovery) this.host.incrementStuckTicks();
            return this.host.initializeTick() >= 0
                    ? BedrockTarget.Status.RECOVERING : BedrockTarget.Status.NEEDS_WAITING;
        }
        if (primingPiston) return this.powerStatus();
        if (BedrockEnvironment.hasRoomForPiston(level, this.host.pistonPos(), this.host.layout().getPistonOffset())) {
            return BedrockTarget.Status.UNINITIALIZED;
        }
        if (applyRecovery) {
            BedrockMessages.actionBar("bedrockminer.fail.place.piston");
            return BedrockTarget.Status.FAILED;
        }
        return this.host.currentStatus();
    }

    private BedrockTarget.Status resolveDispatchedAttempt(boolean applyRecovery) {
        int elapsed = this.host.executeTick() < 0 ? 0 : this.host.tickTimes() - this.host.executeTick();
        if (elapsed >= this.host.postExecuteSyncTimeoutTicks()) {
            if (applyRecovery) this.host.recordNetworkTimeout();
            return this.closeAttempt(applyRecovery);
        }
        if (elapsed < POST_EXECUTE_SETTLE_TICKS) return BedrockTarget.Status.NEEDS_WAITING;

        BlockState piston = this.host.level().getBlockState(this.host.pistonPos());
        BlockState head = this.host.level().getBlockState(this.host.headPos());
        if (piston.is(Blocks.MOVING_PISTON) || head.is(Blocks.MOVING_PISTON) || head.is(Blocks.PISTON_HEAD)) {
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        // An unchanged extended priming piston can still be an unacknowledged snapshot.
        // A reverse piston or collapsed machine is a response to the complete bundle.
        if (piston.is(Blocks.PISTON)
                && piston.getValue(PistonBaseBlock.EXTENDED)
                && piston.getValue(PistonBaseBlock.FACING) == this.host.layout().getPrimingFacing()) {
            return BedrockTarget.Status.NEEDS_WAITING;
        }
        return this.closeAttempt(applyRecovery);
    }

    private BedrockTarget.Status closeAttempt(boolean applyRecovery) {
        if (applyRecovery) this.host.resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);
        return BedrockTarget.Status.RECOVERING;
    }

    private BedrockTarget.Status powerStatus() {
        return this.host.hasOwnedTorchPowerSource()
                ? BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                : BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }
}
