package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class BedrockTarget {
    private static final int REPOWER_INTERVAL_TICKS = 4;
    private static final int POWERED_STALL_RECOVERY_TICKS = 2;
    private static final int POST_EXECUTE_SYNC_TIMEOUT_TICKS = 16;
    private static final int INITIALIZE_SYNC_GRACE_TICKS = 2;
<<<<<<< HEAD
    private static final int INITIALIZE_SYNC_TIMEOUT_TICKS = 40;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    private static final int POST_EXECUTE_AIR_SETTLE_TICKS = 4;
    private static final int POST_EXECUTE_RESIDUE_CLEANUP_INTERVAL_TICKS = 4;
    private static final int POLLUTED_MACHINE_CLEANUP_INTERVAL_TICKS = 4;
    private static final int POWERED_STALL_REBUILD_LIMIT = 3;

    public enum Status {
        FAILED,
        UNINITIALIZED,
        UNEXTENDED_WITH_POWER_SOURCE,
        UNEXTENDED_WITHOUT_POWER_SOURCE,
        EXTENDED,
        NEEDS_WAITING,
        RETRACTING,
        RETRACTED,
        STUCK
    }

    private final ClientLevel level;
    private final BedrockMachineLayout layout;
    private final BlockPos bedrockPos;
    private final BlockPos pistonPos;
    private final BlockPos headPos;
    private final boolean conservativeSync;
    private BedrockTorchPlacement torchPlacement;
    private BlockPos torchSupportPos;
    private BlockPos slimePos;
    private int tickTimes;
    private boolean hasTried;
    private int stuckTicksCounter;
    private int lastRepowerTick = -1;
    private int poweredStallRebuildCount;
    private int executeTick = -1;
    private int initializeTick = -1;
    private int lastPostExecuteResidueCleanupTick = -1;
    private int lastPollutedMachineCleanupTick = -1;
    private boolean throughputConsumedThisTick;
    private Status status = Status.UNINITIALIZED;
    public final Set<BlockPos> tempBlocks = new LinkedHashSet<>();

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level) {
        this(bedrockPos, level, null, null, null);
    }

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level, BedrockMachineLayout precomputedLayout, BedrockTorchPlacement precomputedPlacement, BlockPos precomputedSlimePos) {
        this.bedrockPos = bedrockPos;
        this.level = level;
        this.layout = precomputedLayout != null ? precomputedLayout : BedrockMachineLayout.find(level, bedrockPos);
        if (this.layout == null) {
            this.pistonPos = bedrockPos.above();
            this.headPos = this.pistonPos.above();
            this.status = Status.FAILED;
            this.conservativeSync = BedrockTargetBlocks.requiresConservativeSync(level.getBlockState(bedrockPos));
            return;
        }
        this.pistonPos = this.layout.getPistonPos();
        this.headPos = this.layout.getHeadPos();
        this.conservativeSync = BedrockTargetBlocks.requiresConservativeSync(level.getBlockState(bedrockPos));
        this.torchPlacement = precomputedPlacement != null
                ? precomputedPlacement
                : BedrockEnvironment.findTorchPlacement(level, this.pistonPos, this.layout.getPistonOffset().getOpposite(), this.bedrockPos, this.pistonPos, this.headPos);
        this.slimePos = resolveInitialSlimeSupport(precomputedPlacement, precomputedSlimePos);
        this.torchSupportPos = getTorchSupportFromPlacement();
        if (this.torchPlacement == null) {
            BedrockTorchPlacement slimePlacement = BedrockEnvironment.findPossibleSlimeTorchPlacement(level, this.pistonPos, this.layout.getPistonOffset().getOpposite(), this.bedrockPos, this.pistonPos, this.headPos);
            if (slimePlacement != null) {
                this.slimePos = slimePlacement.getSupportPos();
                this.torchPlacement = slimePlacement;
                this.torchSupportPos = getTorchSupportFromPlacement();
            } else {
                this.status = Status.FAILED;
            }
        }
    }

    public BlockPos getBedrockPos() {
        return bedrockPos;
    }

    public BlockPos getPistonPos() {
        return pistonPos;
    }

    public BlockPos getHeadPos() {
        return headPos;
    }

    public BlockPos getTorchSupportPos() {
        return torchSupportPos;
    }

    public BlockPos getTorchPos() {
        return this.torchPlacement == null ? null : this.torchPlacement.getTorchPos();
    }

    public BlockPos getSlimePos() {
        return slimePos;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isHorizontalLayout() {
        return this.layout != null && this.layout.isHorizontal();
    }

    public Status tick() {
        return this.tick(true, true);
    }

    public Status tick(boolean allowExecute) {
        return this.tick(allowExecute, true);
    }

    public Status tick(boolean allowExecute, boolean allowInitialize) {
        this.throughputConsumedThisTick = false;

        if (this.status != Status.UNINITIALIZED && this.status != Status.EXTENDED) {
            this.tickTimes++;
        } else if (this.status == Status.EXTENDED && allowExecute) {
            this.tickTimes++;
        }

        updateStatus();
        switch (this.status) {
            case UNINITIALIZED -> {
                if (!allowInitialize) {
                    break;
                }
                if (!canBuildInitialMachine()) {
                    break;
                }
                if (!BedrockPlacer.placePiston(this.pistonPos, this.layout.getPrimingFacing())) {
                    break;
                }
<<<<<<< HEAD
                this.initializeTick = this.tickTimes;
                markThroughputAction();
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                if (this.torchSupportPos != null && !hasOwnedTorchPowerSource()) {
                    if (!placeTorch()) {
                        break;
                    }
                }
<<<<<<< HEAD
=======
                this.initializeTick = this.tickTimes;
                markThroughputAction();
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
            case EXTENDED -> {
                if (!allowExecute) {
                    break;
                }
                if (this.hasTried) {
                    break;
                }
                if (!BedrockPlacer.preparePistonPlacementLook(this.pistonPos, this.layout.getExecuteFacing())) {
                    break;
                }
                for (BlockPos torchPos : getOwnedTorchPositions()) {
                    BedrockBreaker.breakBlock(torchPos, Direction.DOWN, !this.conservativeSync);
                }
                BedrockBreaker.breakBlock(this.pistonPos, this.layout.getExecuteFacing(), !this.conservativeSync);
                for (int offset = 1; offset < 6; offset++) {
                    recordTemp(this.pistonPos.relative(this.layout.getPistonOffset(), offset));
                }
                if (!BedrockPlacer.placePiston(this.pistonPos, this.layout.getExecuteFacing())) {
                    break;
                }
                this.hasTried = true;
                this.executeTick = this.tickTimes;
                markThroughputAction();
            }
            case UNEXTENDED_WITHOUT_POWER_SOURCE -> {
                if (!tryRepowerTorch()) {
                    break;
                }
            }
            case UNEXTENDED_WITH_POWER_SOURCE -> {
                if (this.tickTimes < POWERED_STALL_RECOVERY_TICKS) {
                    break;
                }
                if (!BedrockInventory.hasAtLeast(Blocks.PISTON.asItem(), 1)) {
                    break;
                }
                if (!hasOwnedTorchPowerSource()) {
                    if (!tryRepowerTorch()) {
                        break;
                    }
                    break;
                }
                if (this.lastRepowerTick >= 0 && this.tickTimes - this.lastRepowerTick < REPOWER_INTERVAL_TICKS) {
                    break;
                }
                if (this.poweredStallRebuildCount >= POWERED_STALL_REBUILD_LIMIT) {
                    this.status = Status.FAILED;
                    return this.status;
                }

                if (!level.getBlockState(this.pistonPos).isAir()
                        && !BedrockPlacer.preparePistonPlacementLook(this.pistonPos, this.layout.getPrimingFacing())) {
                    break;
                }
                for (BlockPos torchPos : getOwnedTorchPositions()) {
                    BedrockBreaker.breakBlock(torchPos, Direction.DOWN, !this.conservativeSync);
                }
                if (!level.getBlockState(this.pistonPos).isAir()) {
                    BedrockBreaker.breakBlock(this.pistonPos, this.layout.getPrimingFacing(), !this.conservativeSync);
                }
                if (!BedrockPlacer.placePiston(this.pistonPos, this.layout.getPrimingFacing())) {
                    break;
                }
                if (!tryRepowerTorch()) {
                    break;
                }

                this.initializeTick = this.tickTimes;
                this.lastRepowerTick = this.tickTimes;
                this.poweredStallRebuildCount++;
                markThroughputAction();
            }
            case RETRACTED, FAILED, STUCK, NEEDS_WAITING, RETRACTING -> {
            }
        }
        return this.status;
    }

    public Status refreshStatusOnly() {
        this.status = observeStatus();
        return this.status;
    }

    public Status refreshStatusOnlyAndAdvance() {
        this.tickTimes++;
        updateStatus();
        return this.status;
    }

    public boolean consumedThroughputThisTick() {
        return this.throughputConsumedThisTick;
    }

    public Set<BlockPos> getCleanupPositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(this.pistonPos);
        positions.add(this.headPos);
        if (hasCleanupResidue(this.torchSupportPos)) {
            positions.add(this.torchSupportPos);
        }
        if (this.slimePos != null) {
            positions.add(this.slimePos);
        }
        if (hasCleanupResidue(getTorchPos())) {
            positions.add(getTorchPos());
        }
        positions.addAll(this.tempBlocks);
        return positions;
    }

    public Set<BlockPos> getStructuralPositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(this.bedrockPos);
        positions.add(this.pistonPos);
        positions.add(this.headPos);
        for (BlockPos tempPos : this.tempBlocks) {
            if (!isPowerReservationPosition(tempPos)) {
                positions.add(tempPos);
            }
        }
        return positions;
    }

    public Set<BlockPos> getPowerReservationPositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        if (this.torchSupportPos != null) {
            positions.add(this.torchSupportPos);
        }
        if (getTorchPos() != null) {
            positions.add(getTorchPos());
        }
        if (this.slimePos != null) {
            positions.add(this.slimePos);
        }
        return positions;
    }

    public Set<BlockPos> getReservedPositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(this.bedrockPos);
        positions.add(this.pistonPos);
        positions.add(this.headPos);
        if (this.torchSupportPos != null) {
            positions.add(this.torchSupportPos);
        }
        if (getTorchPos() != null) {
            positions.add(getTorchPos());
        }
        if (this.slimePos != null) {
            positions.add(this.slimePos);
        }
        positions.addAll(this.tempBlocks);
        return positions;
    }

    public boolean sharesTorchPlacementWith(BedrockTarget other) {
        return other != null && matchesTorchPlacement(other.torchPlacement);
    }

    public boolean matchesTorchPlacement(BedrockTorchPlacement placement) {
        if (this.torchPlacement == null || placement == null) {
            return false;
        }
        return this.torchPlacement.getClickedFace() == placement.getClickedFace()
                && Objects.equals(this.torchPlacement.getSupportPos(), placement.getSupportPos())
                && Objects.equals(this.torchPlacement.getTorchPos(), placement.getTorchPos());
    }

    public boolean isTorchPoweredBy(BlockPos torchPos) {
        return torchPos != null && BedrockEnvironment.getTorchInfluencePositions(this.pistonPos).contains(torchPos);
    }

    public boolean canReusePowerReservation(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        BlockPos torchPos = getTorchPos();
        if (pos.equals(torchPos)) {
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

    public boolean canReusePendingCleanupPosition(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        if (pos.equals(this.pistonPos)) {
            return isReusablePistonState(state);
        }
        return canReusePowerReservation(pos, state);
    }

    public Set<BlockPos> getStaticMachinePositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(this.bedrockPos);
        positions.add(this.pistonPos);
        positions.add(this.headPos);
        if (this.torchSupportPos != null) {
            positions.add(this.torchSupportPos);
        }
        if (getTorchPos() != null) {
            positions.add(getTorchPos());
        }
        if (this.slimePos != null) {
            positions.add(this.slimePos);
        }
        return positions;
    }

    public Set<BlockPos> getMachineFootprint() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>(getReservedPositions());
        positions.addAll(BedrockEnvironment.getTorchInfluencePositions(this.pistonPos));
        return positions;
    }

    public Set<BlockPos> getOwnedTorchPositions() {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        BlockPos torchPos = getTorchPos();
        if (torchPos != null && BedrockEnvironment.isRedstoneTorchAt(this.level, torchPos)) {
            positions.add(torchPos);
        }
        return positions;
    }

    private void recordTemp(BlockPos pos) {
        if (pos != null) {
            this.tempBlocks.add(pos);
        }
    }

    private BlockPos resolveInitialSlimeSupport(BedrockTorchPlacement placement, BlockPos precomputedSlimePos) {
        if (precomputedSlimePos != null) {
            return precomputedSlimePos;
        }
        if (placement == null || placement.getSupportPos() == null) {
            return null;
        }
        return this.level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)
                ? placement.getSupportPos()
                : null;
    }

    private boolean isPowerReservationPosition(BlockPos pos) {
        return pos != null
                && (pos.equals(this.torchSupportPos)
                || pos.equals(getTorchPos())
                || pos.equals(this.slimePos));
    }

    private boolean canBuildInitialMachine() {
        if (!BedrockInventory.hasAtLeast(Blocks.PISTON.asItem(), 1)) {
            return false;
        }
        if (!hasOwnedTorchPowerSource() && !BedrockInventory.hasAtLeast(Blocks.REDSTONE_TORCH.asItem(), 1)) {
            return false;
        }
        if (this.slimePos != null && !level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)
                && !BedrockInventory.hasAtLeast(Blocks.SLIME_BLOCK.asItem(), 1)) {
            return false;
        }
        return true;
    }

    private boolean isReusablePistonState(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null || !state.is(Blocks.PISTON) || state.getValue(PistonBaseBlock.EXTENDED)) {
            return false;
        }
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        return facing == this.layout.getPrimingFacing() || facing == this.layout.getExecuteFacing();
    }

    private boolean hasOwnedTorchPowerSource() {
        return !getOwnedTorchPositions().isEmpty();
    }

    private boolean tryRepowerTorch() {
        if (!BedrockInventory.hasAtLeast(Blocks.REDSTONE_TORCH.asItem(), 1)) {
            return false;
        }
        if (this.lastRepowerTick >= 0 && this.tickTimes - this.lastRepowerTick < REPOWER_INTERVAL_TICKS) {
            return false;
        }
        if (this.torchSupportPos == null) {
            return false;
        }
        if (!placeTorch()) {
            return false;
        }
        this.lastRepowerTick = this.tickTimes;
        markThroughputAction();
        return true;
    }

    private void updateStatus() {
        if (this.tickTimes > 40) {
            this.status = Status.FAILED;
            return;
        }

        if (isTargetCompleted()) {
            this.status = Status.RETRACTED;
            return;
        }

        if (!BedrockEnvironment.isTorchPlacementUsable(level, this.torchPlacement)) {
            if (this.slimePos != null && this.torchPlacement != null && this.slimePos.equals(this.torchPlacement.getSupportPos())
                    && BedrockEnvironment.isSlimePlacementUsable(level, this.torchPlacement)) {
                if (!level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)) {
                    BedrockPlacer.placeSimple(this.slimePos, Direction.UP, Blocks.SLIME_BLOCK.asItem());
                    recordTemp(this.slimePos);
                }
                this.torchSupportPos = getTorchSupportFromPlacement();
            } else {
                BedrockTorchPlacement naturalPlacement = BedrockEnvironment.findTorchPlacement(level, this.pistonPos, this.layout.getPistonOffset().getOpposite(), this.bedrockPos, this.pistonPos, this.headPos);
                if (isPlacementReservedByOtherTarget(naturalPlacement)) {
                    naturalPlacement = null;
                }
                if (naturalPlacement != null) {
                    this.torchPlacement = naturalPlacement;
                    this.torchSupportPos = getTorchSupportFromPlacement();
                    if (this.slimePos != null && !level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK) && !this.slimePos.equals(this.torchSupportPos)) {
                        this.slimePos = null;
                    }
                } else {
                    BedrockTorchPlacement slimePlacement = this.torchPlacement;
                    if (slimePlacement == null || !BedrockEnvironment.isSlimePlacementUsable(level, slimePlacement) || !slimePlacement.getSupportPos().equals(this.slimePos)) {
                        slimePlacement = BedrockEnvironment.findPossibleSlimeTorchPlacement(level, this.pistonPos, this.layout.getPistonOffset().getOpposite(), this.bedrockPos, this.pistonPos, this.headPos);
                    }
                    if (isPlacementReservedByOtherTarget(slimePlacement)) {
                        slimePlacement = null;
                    }
                    if (slimePlacement != null) {
                        this.torchPlacement = slimePlacement;
                        this.slimePos = slimePlacement.getSupportPos();
                        if (!level.getBlockState(this.slimePos).is(Blocks.SLIME_BLOCK)) {
                            BedrockPlacer.placeSimple(this.slimePos, Direction.UP, Blocks.SLIME_BLOCK.asItem());
                            recordTemp(this.slimePos);
                            markThroughputAction();
                        }
                        this.torchSupportPos = getTorchSupportFromPlacement();
                    } else {
                        this.status = Status.FAILED;
                        BedrockMessages.actionBar("bedrockminer.fail.place.redstonetorch");
                        return;
                    }
                }
            }
        }
        BlockState bedrockState = level.getBlockState(this.bedrockPos);
        BlockState pistonState = level.getBlockState(this.pistonPos);
        BlockState headState = level.getBlockState(this.headPos);
        boolean targetBlock = BedrockTargetBlocks.isTargetBlock(bedrockState);
        if (!targetBlock && pistonState.is(Blocks.PISTON)) {
            this.status = Status.RETRACTED;
            return;
        }
        if (pistonState.is(Blocks.MOVING_PISTON)) {
            this.status = Status.RETRACTING;
            if (!targetBlock) {
                this.status = Status.RETRACTED;
            }
            return;
        }
        if (!targetBlock && hasMachineCleanupResidue()) {
            this.status = Status.RETRACTED;
            return;
        }
        if (hasExceededSyncWaitTimeout()) {
            Status recoveryStatus = getRecoverablePostExecuteStatus();
            if (recoveryStatus != null) {
                resetPostExecuteAttempt("sync_timeout_recover", recoveryStatus);
                this.status = recoveryStatus;
            } else {
                this.status = Status.STUCK;
            }
            return;
        }
        if (this.hasTried && shouldRestartAfterPostExecuteCollapse()) {
            resetPostExecuteAttempt("post_execute_collapse_recover", Status.UNINITIALIZED);
            this.status = Status.UNINITIALIZED;
            return;
        }
        if (hasPollutedMachineState()) {
            cleanupPollutedMachineState();
            this.status = Status.NEEDS_WAITING;
            if (this.hasTried) {
                this.stuckTicksCounter++;
            }
            return;
        }
        if (this.hasTried && pistonState.isAir() && headState.is(Blocks.PISTON_HEAD)) {
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        if (this.hasTried
                && pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        if (pistonState.is(Blocks.PISTON) && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            this.status = Status.EXTENDED;
            return;
        }
        if (shouldCleanupStablePostExecuteResidue()) {
            cleanupStablePostExecuteResidue();
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        if (shouldWaitForPostExecuteAirTransition()) {
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        if (!this.hasTried
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
            if (facing == this.layout.getPrimingFacing() || facing == this.layout.getExecuteFacing()) {
                if (hasOwnedTorchPowerSource()) {
                    this.status = Status.UNEXTENDED_WITH_POWER_SOURCE;
                } else {
                    this.status = Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
                }
                return;
            }
        }
        if (this.hasTried
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)
                && pistonState.getValue(PistonBaseBlock.FACING) == this.layout.getPrimingFacing()
                && targetBlock) {
            if (hasOwnedTorchPowerSource()) {
                this.status = Status.UNEXTENDED_WITH_POWER_SOURCE;
            } else {
                this.status = Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
            }
            clearPostExecuteAttemptState();
            return;
        }
        if (this.hasTried && (pistonState.is(Blocks.PISTON) || pistonState.isAir()) && this.stuckTicksCounter < 15) {
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        if (this.hasTried && hasPostExecuteSyncResidue()) {
            this.status = Status.NEEDS_WAITING;
            this.stuckTicksCounter++;
            return;
        }
        Status recoverableUnextendedStatus = getRecoverableUnextendedStatus();
        if (recoverableUnextendedStatus != null) {
            this.status = recoverableUnextendedStatus;
            return;
        }
        if (shouldWaitForInitializeSettle()) {
            this.status = Status.NEEDS_WAITING;
            return;
        }
<<<<<<< HEAD
        if (!this.hasTried
                && this.initializeTick >= 0
                && this.tickTimes - this.initializeTick <= INITIALIZE_SYNC_TIMEOUT_TICKS) {
            this.status = Status.NEEDS_WAITING;
            return;
        }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (!this.hasTried && hasAnyTransientMachineResidue()) {
            this.status = Status.NEEDS_WAITING;
            return;
        }
        if (BedrockEnvironment.hasRoomForPiston(this.level, this.pistonPos, this.layout.getPistonOffset())) {
            this.status = Status.UNINITIALIZED;
            return;
        }
        if (pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.FACING) != this.layout.getPrimingFacing()
                && pistonState.getValue(PistonBaseBlock.FACING) != this.layout.getExecuteFacing()) {
            this.status = Status.UNINITIALIZED;
            return;
        }
        this.status = Status.FAILED;
        BedrockMessages.actionBar("bedrockminer.fail.place.piston");
    }

    private Status observeStatus() {
        if (isTargetCompleted()) {
            return Status.RETRACTED;
        }

        BlockState bedrockState = level.getBlockState(this.bedrockPos);
        BlockState pistonState = level.getBlockState(this.pistonPos);
        BlockState headState = level.getBlockState(this.headPos);
        boolean targetBlock = BedrockTargetBlocks.isTargetBlock(bedrockState);
        if (!targetBlock && pistonState.is(Blocks.PISTON)) {
            return Status.RETRACTED;
        }
        if (pistonState.is(Blocks.MOVING_PISTON)) {
            return Status.RETRACTING;
        }
        if (!targetBlock && hasMachineCleanupResidue()) {
            return Status.RETRACTED;
        }
        if (hasExceededSyncWaitTimeout()) {
            Status recoveryStatus = getRecoverablePostExecuteStatus();
            if (recoveryStatus != null) {
                return recoveryStatus;
            }
            return Status.STUCK;
        }
        if (shouldRestartAfterPostExecuteCollapse()) {
            return Status.UNINITIALIZED;
        }
        if (hasPollutedMachineState()) {
            return Status.NEEDS_WAITING;
        }
        if (this.hasTried && pistonState.isAir() && headState.is(Blocks.PISTON_HEAD)) {
            return Status.NEEDS_WAITING;
        }
        if (this.hasTried
                && pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            return Status.NEEDS_WAITING;
        }
        if (pistonState.is(Blocks.PISTON) && pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            return Status.EXTENDED;
        }
        if (shouldRestartAfterPostExecuteCollapse()) {
            return Status.UNINITIALIZED;
        }
        if (shouldCleanupStablePostExecuteResidue()) {
            return Status.NEEDS_WAITING;
        }
        if (shouldWaitForPostExecuteAirTransition()) {
            return Status.NEEDS_WAITING;
        }
        if (!this.hasTried
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)) {
            Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
            if (facing == this.layout.getPrimingFacing() || facing == this.layout.getExecuteFacing()) {
                if (hasOwnedTorchPowerSource()) {
                    return Status.UNEXTENDED_WITH_POWER_SOURCE;
                }
                return Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
            }
        }
        if (this.hasTried
                && pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)
                && pistonState.getValue(PistonBaseBlock.FACING) == this.layout.getPrimingFacing()
                && targetBlock) {
            if (hasOwnedTorchPowerSource()) {
                return Status.UNEXTENDED_WITH_POWER_SOURCE;
            }
            return Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
        }
        if (this.hasTried && (pistonState.is(Blocks.PISTON) || pistonState.isAir()) && this.stuckTicksCounter < 15) {
            return Status.NEEDS_WAITING;
        }
        if (this.hasTried && hasPostExecuteSyncResidue()) {
            return Status.NEEDS_WAITING;
        }
        Status recoverableUnextendedStatus = getRecoverableUnextendedStatus();
        if (recoverableUnextendedStatus != null) {
            return recoverableUnextendedStatus;
        }
        if (shouldWaitForInitializeSettle()) {
            return Status.NEEDS_WAITING;
        }
        if (!this.hasTried && hasAnyTransientMachineResidue()) {
            return Status.NEEDS_WAITING;
        }
        if (BedrockEnvironment.hasRoomForPiston(this.level, this.pistonPos, this.layout.getPistonOffset())) {
            return Status.UNINITIALIZED;
        }
        if (pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.FACING) != this.layout.getPrimingFacing()
                && pistonState.getValue(PistonBaseBlock.FACING) != this.layout.getExecuteFacing()) {
            return Status.UNINITIALIZED;
        }
        return this.status;
    }

    private boolean hasExceededSyncWaitTimeout() {
        return this.hasTried
                && this.executeTick >= 0
                && this.tickTimes - this.executeTick >= POST_EXECUTE_SYNC_TIMEOUT_TICKS
                && (level.getBlockState(this.pistonPos).isAir() || hasPostExecuteSyncResidue());
    }

    private boolean shouldRestartAfterPostExecuteCollapse() {
        if (!isPostExecuteCollapsed() || this.executeTick < 0) {
            return false;
        }
        if (this.tickTimes - this.executeTick < POST_EXECUTE_AIR_SETTLE_TICKS) {
            return false;
        }
        if (hasStablePostExecuteResidue()) {
            return false;
        }
        return BedrockEnvironment.hasRoomForPiston(this.level, this.pistonPos, this.layout.getPistonOffset());
    }

    private boolean shouldCleanupStablePostExecuteResidue() {
        return isPostExecuteCollapsed()
                && this.executeTick >= 0
                && this.tickTimes - this.executeTick >= POST_EXECUTE_AIR_SETTLE_TICKS
                && this.tickTimes - this.executeTick < POST_EXECUTE_SYNC_TIMEOUT_TICKS
                && hasStablePostExecuteResidue();
    }

    private boolean shouldWaitForPostExecuteAirTransition() {
        if (!this.hasTried || !level.getBlockState(this.pistonPos).isAir()) {
            return false;
        }
        if (hasTransientMachineResidue()) {
            return true;
        }
        return this.executeTick >= 0
                && this.tickTimes - this.executeTick < POST_EXECUTE_AIR_SETTLE_TICKS
                && !BedrockEnvironment.hasRoomForPiston(this.level, this.pistonPos, this.layout.getPistonOffset());
    }

    private boolean shouldWaitForInitializeSettle() {
        return !this.hasTried
                && this.initializeTick >= 0
                && this.tickTimes - this.initializeTick <= INITIALIZE_SYNC_GRACE_TICKS
                && hasTransientMachineResidue();
    }

    private boolean isTargetCompleted() {
        return !BedrockTargetBlocks.isTargetBlock(level.getBlockState(this.bedrockPos));
    }

    private Status getRecoverablePostExecuteStatus() {
        if (!this.hasTried || this.executeTick < 0) {
            return null;
        }
        if (shouldRestartAfterPostExecuteCollapse()) {
            return Status.UNINITIALIZED;
        }
        return getRecoverableUnextendedStatus();
    }

    private Status getRecoverableUnextendedStatus() {
        if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(this.bedrockPos))) {
            return null;
        }
        if (!level.getBlockState(this.pistonPos).is(Blocks.PISTON)
                || level.getBlockState(this.pistonPos).getValue(PistonBaseBlock.EXTENDED)) {
            return null;
        }

        Direction facing = level.getBlockState(this.pistonPos).getValue(PistonBaseBlock.FACING);
        if (facing != this.layout.getPrimingFacing() && facing != this.layout.getExecuteFacing()) {
            return null;
        }
        if (hasOwnedTorchPowerSource()) {
            return Status.UNEXTENDED_WITH_POWER_SOURCE;
        }
        return Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }

    private boolean isPlacementReservedByOtherTarget(BedrockTorchPlacement placement) {
        if (placement == null) {
            return false;
        }
        return BedrockController.isTorchPlacementReservedByOtherTarget(placement, this);
    }

    private void resetPostExecuteAttempt(String reason, Status recoveryStatus) {
        clearPostExecuteAttemptState();
    }

    private void clearPostExecuteAttemptState() {
        this.tickTimes = 0;
        this.hasTried = false;
        this.stuckTicksCounter = 0;
        this.lastRepowerTick = -1;
        this.executeTick = -1;
        this.initializeTick = -1;
        this.lastPostExecuteResidueCleanupTick = -1;
        this.lastPollutedMachineCleanupTick = -1;
    }

    private boolean hasMachineCleanupResidue() {
        return hasCleanupResidue(this.pistonPos)
                || hasCleanupResidue(this.headPos)
                || hasCleanupResidue(this.slimePos)
                || hasCleanupResidue(getTorchPos());
    }

    private boolean hasPostExecuteSyncResidue() {
        return hasCleanupResidue(this.pistonPos) || hasCleanupResidue(this.headPos);
    }

    private boolean isPostExecuteCollapsed() {
        return this.hasTried
                && level.getBlockState(this.pistonPos).isAir()
                && !level.getBlockState(this.headPos).is(Blocks.PISTON_HEAD);
    }

    private boolean hasTransientMachineResidue() {
        return isTransientMachineResidue(this.pistonPos, false)
                || isTransientMachineResidue(this.headPos, true);
    }

    private boolean hasAnyTransientMachineResidue() {
        return isTransientMachineResidue(this.pistonPos, false)
                || isTransientMachineResidue(this.headPos, true)
                || isTransientMachineResidue(this.torchSupportPos, false)
                || isTransientMachineResidue(this.slimePos, false);
    }

    private boolean hasStablePostExecuteResidue() {
        return isStablePostExecuteResidue(this.pistonPos, false)
                || isStablePostExecuteResidue(this.headPos, true);
    }

    private boolean isStablePostExecuteResidue(BlockPos pos, boolean headSlot) {
        if (pos == null) {
            return false;
        }
        var state = level.getBlockState(pos);
        return BedrockTargetBlocks.isCleanupResidue(state) && !isTransientMachineResidue(pos, headSlot);
    }

    private void cleanupStablePostExecuteResidue() {
        if (this.lastPostExecuteResidueCleanupTick >= 0
                && this.tickTimes - this.lastPostExecuteResidueCleanupTick < POST_EXECUTE_RESIDUE_CLEANUP_INTERVAL_TICKS) {
            return;
        }

        boolean headResidue = isStablePostExecuteResidue(this.headPos, true);
        boolean pistonResidue = isStablePostExecuteResidue(this.pistonPos, false);
        if (!headResidue && !pistonResidue) {
            return;
        }

        this.lastPostExecuteResidueCleanupTick = this.tickTimes;
        if (headResidue) {
            BedrockBreaker.breakBlock(this.headPos, false);
        }
        if (pistonResidue) {
            BedrockBreaker.breakBlock(this.pistonPos, false);
        }
        markThroughputAction();
    }

    private boolean isTransientMachineResidue(BlockPos pos, boolean headSlot) {
        if (pos == null) {
            return false;
        }

        var state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (state.is(Blocks.MOVING_PISTON)) {
            return true;
        }
        if (headSlot && state.is(Blocks.PISTON_HEAD)) {
            return true;
        }
        if (state.is(Blocks.PISTON)) {
            if (pos.equals(this.pistonPos)) {
                Direction facing = state.getValue(PistonBaseBlock.FACING);
                boolean validFacing = facing == this.layout.getPrimingFacing() || facing == this.layout.getExecuteFacing();
                if (validFacing && !state.getValue(PistonBaseBlock.EXTENDED)) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    private boolean hasCleanupResidue(BlockPos pos) {
        return pos != null && BedrockTargetBlocks.isCleanupResidue(level.getBlockState(pos));
    }

    private boolean hasPollutedMachineState() {
        return isPollutedPistonState()
                || isPollutedHeadState()
                || isPollutedTorchSupportState()
                || isPollutedTorchState()
                || isPollutedSlimeSupportState();
    }

    private boolean isPollutedPistonState() {
        var state = level.getBlockState(this.pistonPos);
        if (state.isAir() || state.is(Blocks.MOVING_PISTON)) {
            return false;
        }
        if (state.is(Blocks.PISTON)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedHeadState() {
        var state = level.getBlockState(this.headPos);
        if (state.isAir() || state.is(Blocks.MOVING_PISTON) || state.is(Blocks.PISTON_HEAD)) {
            return false;
        }
        if (this.hasTried && this.executeTick >= 0 && this.tickTimes - this.executeTick < POST_EXECUTE_SYNC_TIMEOUT_TICKS) {
            if (state.is(Blocks.PISTON)) {
                return false;
            }
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedTorchState() {
        BlockPos torchPos = getTorchPos();
        if (torchPos == null) {
            return false;
        }
        var state = level.getBlockState(torchPos);
        if (state.isAir()) {
            return false;
        }
        if (isExpectedTorchState(state)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isPollutedTorchSupportState() {
        if (this.torchSupportPos == null) {
            return false;
        }
        var state = level.getBlockState(this.torchSupportPos);
        if (state.isAir()) {
            return false;
        }
        if (this.slimePos != null && this.torchSupportPos.equals(this.slimePos)) {
            return false;
        }
        if (state.is(Blocks.SLIME_BLOCK)
                && this.torchPlacement != null
                && BedrockEnvironment.isTorchPlacementUsable(level, this.torchPlacement)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private boolean isExpectedTorchState(net.minecraft.world.level.block.state.BlockState state) {
        if (this.torchPlacement == null || this.torchPlacement.getClickedFace() == null) {
            return false;
        }
        Direction clickedFace = this.torchPlacement.getClickedFace();
        if (clickedFace == Direction.UP) {
            return state.is(Blocks.REDSTONE_TORCH);
        }
        return state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private boolean isPollutedSlimeSupportState() {
        if (this.slimePos == null) {
            return false;
        }
        var state = level.getBlockState(this.slimePos);
        if (state.isAir() || state.is(Blocks.SLIME_BLOCK)) {
            return false;
        }
        return BedrockTargetBlocks.isCleanupResidue(state);
    }

    private void cleanupPollutedMachineState() {
        if (this.lastPollutedMachineCleanupTick >= 0
                && this.tickTimes - this.lastPollutedMachineCleanupTick < POLLUTED_MACHINE_CLEANUP_INTERVAL_TICKS) {
            return;
        }

        boolean pollutedPiston = isPollutedPistonState();
        boolean pollutedHead = isPollutedHeadState();
        boolean pollutedTorchSupport = isPollutedTorchSupportState();
        boolean pollutedTorch = isPollutedTorchState();
        boolean pollutedSlime = isPollutedSlimeSupportState();
        if (!pollutedPiston && !pollutedHead && !pollutedTorchSupport && !pollutedTorch && !pollutedSlime) {
            return;
        }

        this.lastPollutedMachineCleanupTick = this.tickTimes;

        if (pollutedTorch) {
            BedrockBreaker.breakBlock(getTorchPos(), false);
        }
        if (pollutedHead) {
            BedrockBreaker.breakBlock(this.headPos, false);
        }
        if (pollutedPiston) {
            BedrockBreaker.breakBlock(this.pistonPos, false);
        }
        if (pollutedTorchSupport) {
            BedrockBreaker.breakBlock(this.torchSupportPos, false);
        }
        if (pollutedSlime) {
            BedrockBreaker.breakBlock(this.slimePos, false);
        }

        markThroughputAction();
    }

    private boolean placeTorch() {
        if (this.torchPlacement == null || this.torchSupportPos == null) {
            return false;
        }
        if (!BedrockPlacer.placeSimple(this.torchSupportPos, this.torchPlacement.getClickedFace(), Blocks.REDSTONE_TORCH.asItem())) {
            return false;
        }
        recordTemp(getTorchPos());
        return true;
    }

    private BlockPos getTorchSupportFromPlacement() {
        return this.torchPlacement == null ? null : this.torchPlacement.getSupportPos();
    }

    private void markThroughputAction() {
        this.throughputConsumedThisTick = true;
    }

}
