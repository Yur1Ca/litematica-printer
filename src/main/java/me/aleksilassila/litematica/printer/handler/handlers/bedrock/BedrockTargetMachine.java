package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Owns mutable power-source placement and recovery for one bedrock machine. */
final class BedrockTargetMachine {
    private static final int REPOWER_INTERVAL_TICKS = 4;
    private static final int POWERED_STALL_REBUILD_LIMIT = 3;

    private final ClientLevel level;
    private final BedrockMachineLayout layout;
    private final BlockPos bedrockPos;
    private final BlockPos pistonPos;
    private final BlockPos headPos;
    private final BedrockTargetFootprint footprint;
    private final BedrockPlacer placer;
    private BedrockTorchPlacement torchPlacement;
    private BlockPos torchSupportPos;
    private BlockPos slimePos;
    private int lastRepowerTick = -1;
    private int poweredStallRebuildCount;
    private boolean deferredInitialTorch;
    private boolean valid;

    BedrockTargetMachine(
            ClientLevel level,
            BedrockMachineLayout layout,
            BlockPos bedrockPos,
            BlockPos pistonPos,
            BlockPos headPos,
            BedrockTargetFootprint footprint,
            BedrockTorchPlacement precomputedPlacement,
            BlockPos precomputedSlimePos,
            BedrockPlacer placer
    ) {
        this.level = level;
        this.layout = layout;
        this.bedrockPos = bedrockPos;
        this.pistonPos = pistonPos;
        this.headPos = headPos;
        this.footprint = footprint;
        this.placer = placer;
        if (layout == null) {
            return;
        }
        this.torchPlacement = precomputedPlacement != null
                ? precomputedPlacement
                : BedrockEnvironment.findTorchPlacement(
                level,
                pistonPos,
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                pistonPos,
                headPos
        );
        this.slimePos = this.resolveInitialSlimeSupport(precomputedPlacement, precomputedSlimePos);
        this.torchSupportPos = this.torchSupportFromPlacement();
        if (this.torchPlacement == null) {
            BedrockTorchPlacement slimePlacement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                    level,
                    pistonPos,
                    layout.getPistonOffset().getOpposite(),
                    bedrockPos,
                    pistonPos,
                    headPos
            );
            if (slimePlacement == null) {
                return;
            }
            this.slimePos = slimePlacement.getSupportPos();
            this.torchPlacement = slimePlacement;
            this.torchSupportPos = this.torchSupportFromPlacement();
        }
        this.valid = true;
    }

    boolean isValid() {
        return this.valid;
    }

    BedrockTorchPlacement torchPlacement() {
        return this.torchPlacement;
    }

    BedrockTorchPlacement initialBatchTorchPlacement() {
        return this.torchPlacement != null && this.torchPlacement.getSupportPos() != null && this.slimePos == null
                && !BedrockTargetBlocks.requiresSneakPlacement(this.level.getBlockState(this.torchPlacement.getSupportPos()))
                ? this.torchPlacement : null;
    }

    BlockPos torchSupportPos() {
        return this.torchSupportPos;
    }

    BlockPos torchPos() {
        return this.torchPlacement == null ? null : this.torchPlacement.getTorchPos();
    }

    BlockPos slimePos() {
        return this.slimePos;
    }

    int lastRepowerTick() {
        return this.lastRepowerTick;
    }

    boolean canRepowerNow(int tickTimes) {
        return this.lastRepowerTick < 0
                || tickTimes - this.lastRepowerTick >= REPOWER_INTERVAL_TICKS;
    }

    boolean rebuildLimitReached() {
        return this.poweredStallRebuildCount >= POWERED_STALL_REBUILD_LIMIT;
    }

    void recordRebuild(int tickTimes) {
        this.lastRepowerTick = tickTimes;
        this.poweredStallRebuildCount++;
    }

    void resetAttempt() {
        this.lastRepowerTick = -1;
        this.deferredInitialTorch = false;
    }

    void deferInitialTorch() {
        this.deferredInitialTorch = true;
    }

    boolean hasDeferredInitialTorch() {
        return this.deferredInitialTorch;
    }

    void onTorchBatchDispatched(BedrockTorchPlacement placement) {
        if (!this.matchesTorchPlacement(placement)) return;
        this.footprint.recordTemporary(placement.getTorchPos());
        this.deferredInitialTorch = false;
    }

    boolean isInventorySettling() { return this.placer.isInventorySettling(); }

    boolean canBuildInitialMachine() {
        if (!BedrockInventory.hasAtLeast(Blocks.PISTON.asItem(), 1)) return false;
        if (!this.hasOwnedTorchPowerSource()
                && !BedrockInventory.hasAtLeast(Blocks.REDSTONE_TORCH.asItem(), 1)) return false;
        return this.slimePos == null
                || this.level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)
                || BedrockInventory.hasAtLeast(Blocks.SLIME_BLOCK.asItem(), 1);
    }

    boolean ensureTorchPlacement(
            Predicate<BedrockTorchPlacement> reservedByOther,
            Runnable markThroughput
    ) {
        // Keeping an installed source is observation, not new construction. The
        // lifecycle's canMutatePower gate still protects another in-flight event.
        if (BedrockEnvironment.isTorchPlacementUsable(this.level, this.torchPlacement)
                && (this.hasInstalledPowerSource() || !reservedByOther.test(this.torchPlacement))) return true;
        if (this.slimePos != null
                && this.torchPlacement != null
                && !reservedByOther.test(this.torchPlacement)
                && this.slimePos.equals(this.torchPlacement.getSupportPos())
                && BedrockEnvironment.isSlimePlacementUsable(this.level, this.torchPlacement)) {
            if (!this.level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)) {
                if (!this.placer.placeSlimeSupport(this.slimePos)) {
                    return false;
                }
                this.footprint.recordTemporary(this.slimePos);
            }
            this.torchSupportPos = this.torchSupportFromPlacement();
            return true;
        }

        BedrockTorchPlacement naturalPlacement = BedrockEnvironment.findTorchPlacement(
                this.level,
                this.pistonPos,
                this.layout.getPistonOffset().getOpposite(),
                placement -> !reservedByOther.test(placement),
                this.bedrockPos,
                this.pistonPos,
                this.headPos
        );
        if (naturalPlacement != null) {
            this.torchPlacement = naturalPlacement;
            this.torchSupportPos = this.torchSupportFromPlacement();
            if (this.slimePos != null
                    && !this.level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)
                    && !this.slimePos.equals(this.torchSupportPos)) {
                this.slimePos = null;
            }
            return true;
        }

        BedrockTorchPlacement slimePlacement = this.torchPlacement;
        if (slimePlacement == null
                || reservedByOther.test(slimePlacement)
                || !BedrockEnvironment.isSlimePlacementUsable(this.level, slimePlacement)
                || !slimePlacement.getSupportPos().equals(this.slimePos)) {
            slimePlacement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                    this.level,
                    this.pistonPos,
                    this.layout.getPistonOffset().getOpposite(),
                    placement -> !reservedByOther.test(placement),
                    this.bedrockPos,
                    this.pistonPos,
                    this.headPos
            );
        }
        if (slimePlacement == null) return false;

        this.torchPlacement = slimePlacement;
        this.slimePos = slimePlacement.getSupportPos();
        if (!this.level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)) {
            if (!this.placer.placeSlimeSupport(this.slimePos)) {
                return false;
            }
            this.footprint.recordTemporary(this.slimePos);
            markThroughput.run();
        }
        this.torchSupportPos = this.torchSupportFromPlacement();
        return true;
    }

    private boolean hasInstalledPowerSource() {
        BlockState piston = this.level.getBlockState(this.pistonPos);
        return this.layout != null && piston.is(Blocks.PISTON)
                && piston.getValue(PistonBaseBlock.FACING) == this.layout.getPrimingFacing()
                && this.hasOwnedTorchPowerSource()
                && this.level.getBlockState(this.torchPos()).getValue(RedstoneTorchBlock.LIT);
    }

    boolean tryRepowerTorch(int tickTimes, Runnable markThroughput) {
        if (!BedrockInventory.hasAtLeast(Blocks.REDSTONE_TORCH.asItem(), 1)) return false;
        if (this.lastRepowerTick >= 0 && tickTimes - this.lastRepowerTick < REPOWER_INTERVAL_TICKS) {
            return false;
        }
        if (this.torchSupportPos == null || !this.placeTorch()) return false;
        this.lastRepowerTick = tickTimes;
        markThroughput.run();
        return true;
    }

    boolean hasOwnedTorchPowerSource() {
        return !this.footprint.ownedTorchPositions(this.torchPos()).isEmpty();
    }

    Set<BlockPos> ownedTorchPositions() {
        return this.footprint.ownedTorchPositions(this.torchPos());
    }

    boolean matchesTorchPlacement(BedrockTorchPlacement placement) {
        return this.torchPlacement != null
                && placement != null
                && this.torchPlacement.getClickedFace() == placement.getClickedFace()
                && Objects.equals(this.torchPlacement.getSupportPos(), placement.getSupportPos())
                && Objects.equals(this.torchPlacement.getTorchPos(), placement.getTorchPos());
    }

    boolean canReusePowerReservation(BlockPos pos, BlockState state) {
        if (pos == null || state == null || state.isAir()) return false;
        if (pos.equals(this.torchPos())) {
            return this.torchPlacement != null
                    && BedrockEnvironment.isTorchPlacementUsable(this.level, this.torchPlacement);
        }
        if (this.slimePos != null && pos.equals(this.slimePos)) {
            return this.torchPlacement != null
                    && BedrockEnvironment.isSlimePlacementUsable(this.level, this.torchPlacement);
        }
        return this.torchPlacement != null
                && pos.equals(this.torchSupportPos)
                && state.is(Blocks.SLIME_BLOCK)
                && BedrockEnvironment.isTorchPlacementUsable(this.level, this.torchPlacement);
    }

    boolean isReusablePistonState(BlockState state) {
        if (state == null || !state.is(Blocks.PISTON) || state.getValue(PistonBaseBlock.EXTENDED)) {
            return false;
        }
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        return facing == this.layout.getPrimingFacing();
    }

    boolean placeTorch() {
        if (this.torchPlacement == null || this.torchSupportPos == null) return false;
        if (!this.placer.placeSimple(
                this.torchSupportPos,
                this.torchPlacement.getClickedFace(),
                Blocks.REDSTONE_TORCH.asItem()
        )) return false;
        this.footprint.recordTemporary(this.torchPos());
        this.deferredInitialTorch = false;
        return true;
    }

    private BlockPos resolveInitialSlimeSupport(
            BedrockTorchPlacement placement,
            BlockPos precomputedSlimePos
    ) {
        if (precomputedSlimePos != null) return precomputedSlimePos;
        if (placement == null || placement.getSupportPos() == null) return null;
        return this.level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)
                ? placement.getSupportPos() : null;
    }

    private BlockPos torchSupportFromPlacement() {
        return this.torchPlacement == null ? null : this.torchPlacement.getSupportPos();
    }
}
