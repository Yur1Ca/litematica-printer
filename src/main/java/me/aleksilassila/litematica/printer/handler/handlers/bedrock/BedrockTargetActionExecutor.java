package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/** Executes one target's actions after its observed status has been resolved. */
final class BedrockTargetActionExecutor {
    private static final int POWERED_STALL_RECOVERY_TICKS = 2;

    interface Host {
        BedrockTarget.Status currentStatus();
        void setStatus(BedrockTarget.Status status);
        ClientLevel level();
        BedrockMachineLayout layout();
        BlockPos bedrockPos();
        BlockPos pistonPos();
        BlockPos torchSupportPos();
        boolean hasOwnedTorchPowerSource();
        boolean canBuildInitialMachine();
        boolean placeInitialTorch();
        void deferInitialTorch();
        boolean tryRepowerTorch();
        int tickTimes();
        boolean hasTried();
        void setHasTried(boolean value);
        void markDispatched();
        void setInitializeTick(int value);
        void setExecuteTick(int value);
        void recordTemporary(BlockPos pos);
        void recordNetworkAttempt();
        Set<BlockPos> ownedTorchPositions();
        BedrockCriticalExecutor criticalExecutor();
        BedrockPlacer placer();
        boolean canRepowerNow();
        boolean rebuildLimitReached();
        void recordRebuild();
        void markThroughputAction();
        boolean canMutatePower();
        void cleanupRecoveryResidue();
    }

    private final Host host;

    BedrockTargetActionExecutor(Host host) {
        this.host = host;
    }

    void execute(boolean allowExecute, boolean allowInitialize) {
        this.execute(allowExecute, allowInitialize, true);
    }

    void executePreparation() {
        this.execute(false, true, false);
    }

    private void execute(boolean allowExecute, boolean allowInitialize, boolean allowInitialPiston) {
        if (this.host.hasTried()) return;
        switch (this.host.currentStatus()) {
            case UNINITIALIZED -> this.initialize(allowInitialize && allowInitialPiston);
            case RECOVERING -> {
                if (allowInitialize) this.cleanupRecovery();
            }
            case EXTENDED -> this.executeTarget(allowExecute);
            case UNEXTENDED_WITHOUT_POWER_SOURCE -> {
                if (allowInitialize) this.host.tryRepowerTorch();
            }
            case UNEXTENDED_WITH_POWER_SOURCE -> {
                if (allowInitialize) this.recoverPoweredStall();
            }
            case RETRACTED, FAILED, STUCK, NEEDS_WAITING, RETRACTING -> {
            }
        }
    }

    void cleanupRecovery() {
        if (!this.host.hasTried() && this.host.currentStatus() == BedrockTarget.Status.RECOVERING
                && this.host.canMutatePower()) this.host.cleanupRecoveryResidue();
    }

    private void initialize(boolean allowInitialize) {
        if (!allowInitialize || !this.host.canMutatePower() || !this.host.canBuildInitialMachine()) return;
        BedrockMachineLayout layout = this.host.layout();
        if (!this.host.placer().placePiston(this.host.pistonPos(), layout.getPrimingFacing())) return;
        this.initialized();
        if (this.host.torchSupportPos() != null && !this.host.hasOwnedTorchPowerSource()
                && !this.host.placer().isInventorySettling()) this.host.placeInitialTorch();
    }

    void initialized() {
        this.host.setInitializeTick(this.host.tickTimes());
        this.host.markThroughputAction();
        if (this.host.torchSupportPos() != null && !this.host.hasOwnedTorchPowerSource()) {
            this.host.deferInitialTorch();
        }
    }

    private void executeTarget(boolean allowExecute) {
        if (!allowExecute || this.host.hasTried() || !this.host.canMutatePower()) return;
        BedrockMachineLayout layout = this.host.layout();
        if (!this.host.placer().preparePistonPlacementLook(this.host.pistonPos(), layout.getExecuteFacing())) return;
        if (!this.host.criticalExecutor().submit(
                this.host.level(),
                this.host.bedrockPos(),
                this.host.pistonPos(),
                layout.getExecuteFacing(),
                this.host.ownedTorchPositions()
        )) return;
        this.dispatched();
    }

    void dispatched() {
        BedrockMachineLayout layout = this.host.layout();
        for (int offset = 1; offset < 6; offset++) {
            this.host.recordTemporary(this.host.pistonPos().relative(layout.getPistonOffset(), offset));
        }
        // The critical START -> STOP -> placement bundle above is intentionally complete
        // before network tracking starts; tracking is observation only and never gates it.
        this.host.markDispatched();
        this.host.recordNetworkAttempt();
        this.host.markThroughputAction();
    }

    private void recoverPoweredStall() {
        if (this.host.tickTimes() < POWERED_STALL_RECOVERY_TICKS) return;
        if (!this.host.canMutatePower()) return;
        if (!this.host.hasOwnedTorchPowerSource()) {
            this.host.tryRepowerTorch();
            return;
        }
        if (!this.host.canRepowerNow()) return;
        if (this.host.rebuildLimitReached()) {
            this.host.setStatus(BedrockTarget.Status.FAILED);
            return;
        }

        this.host.recordRebuild();
        this.host.setStatus(BedrockTarget.Status.RECOVERING);
        this.host.cleanupRecoveryResidue();
    }
}
