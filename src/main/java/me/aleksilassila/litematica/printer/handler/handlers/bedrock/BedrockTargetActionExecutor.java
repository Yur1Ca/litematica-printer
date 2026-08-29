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
        boolean tryRepowerTorch();
        int tickTimes();
        boolean hasTried();
        void setHasTried(boolean value);
        void setInitializeTick(int value);
        void setExecuteTick(int value);
        void recordTemporary(BlockPos pos);
        void recordNetworkAttempt();
        Set<BlockPos> ownedTorchPositions();
        boolean conservativeSync();
        BedrockCriticalExecutor criticalExecutor();
        BedrockPlacer placer();
        boolean canRepowerNow();
        boolean rebuildLimitReached();
        void recordRebuild();
        void markThroughputAction();
    }

    private final Host host;

    BedrockTargetActionExecutor(Host host) {
        this.host = host;
    }

    void execute(boolean allowExecute, boolean allowInitialize) {
        switch (this.host.currentStatus()) {
            case UNINITIALIZED -> this.initialize(allowInitialize);
            case EXTENDED -> this.executeTarget(allowExecute);
            case UNEXTENDED_WITHOUT_POWER_SOURCE -> this.host.tryRepowerTorch();
            case UNEXTENDED_WITH_POWER_SOURCE -> this.recoverPoweredStall();
            case RETRACTED, FAILED, STUCK, NEEDS_WAITING, RETRACTING -> {
            }
        }
    }

    private void initialize(boolean allowInitialize) {
        if (!allowInitialize || !this.host.canBuildInitialMachine()) return;
        BedrockMachineLayout layout = this.host.layout();
        if (!this.host.placer().placePiston(this.host.pistonPos(), layout.getPrimingFacing())) return;
        this.host.setInitializeTick(this.host.tickTimes());
        this.host.markThroughputAction();
        if (this.host.torchSupportPos() != null && !this.host.hasOwnedTorchPowerSource()) {
            this.host.placeInitialTorch();
        }
    }

    private void executeTarget(boolean allowExecute) {
        if (!allowExecute || this.host.hasTried()) return;
        BedrockMachineLayout layout = this.host.layout();
        if (!this.host.placer().preparePistonPlacementLook(this.host.pistonPos(), layout.getExecuteFacing())) return;
        if (!this.host.criticalExecutor().submit(
                this.host.level(),
                this.host.bedrockPos(),
                this.host.pistonPos(),
                layout.getExecuteFacing(),
                this.host.ownedTorchPositions()
        )) return;
        for (int offset = 1; offset < 6; offset++) {
            this.host.recordTemporary(this.host.pistonPos().relative(layout.getPistonOffset(), offset));
        }
        // The critical START -> STOP -> placement bundle above is intentionally complete
        // before network tracking starts; tracking is observation only and never gates it.
        this.host.recordNetworkAttempt();
        this.host.setHasTried(true);
        this.host.setExecuteTick(this.host.tickTimes());
        this.host.markThroughputAction();
    }

    private void recoverPoweredStall() {
        if (this.host.tickTimes() < POWERED_STALL_RECOVERY_TICKS) return;
        if (!BedrockInventory.hasAtLeast(Blocks.PISTON.asItem(), 1)) return;
        if (!this.host.hasOwnedTorchPowerSource()) {
            this.host.tryRepowerTorch();
            return;
        }
        if (!this.host.canRepowerNow()) return;
        if (this.host.rebuildLimitReached()) {
            this.host.setStatus(BedrockTarget.Status.FAILED);
            return;
        }

        BedrockMachineLayout layout = this.host.layout();
        if (!this.host.level().getBlockState(this.host.pistonPos()).isAir()
                && !this.host.placer().preparePistonPlacementLook(this.host.pistonPos(), layout.getPrimingFacing())) return;
        for (BlockPos torchPos : this.host.ownedTorchPositions()) {
            BedrockBreaker.breakBlock(torchPos, Direction.DOWN, !this.host.conservativeSync());
        }
        if (!this.host.level().getBlockState(this.host.pistonPos()).isAir()) {
            BedrockBreaker.breakBlock(this.host.pistonPos(), layout.getPrimingFacing(), !this.host.conservativeSync());
        }
        if (!this.host.placer().placePiston(this.host.pistonPos(), layout.getPrimingFacing())) return;
        if (!this.host.tryRepowerTorch()) return;

        this.host.setInitializeTick(this.host.tickTimes());
        this.host.recordRebuild();
        this.host.markThroughputAction();
    }
}
