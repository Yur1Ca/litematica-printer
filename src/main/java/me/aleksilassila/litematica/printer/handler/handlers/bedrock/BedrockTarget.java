package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

public class BedrockTarget implements BedrockTargetStatusResolver.Host, BedrockTargetActionExecutor.Host {
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
    private final BedrockTargetResidue residue;
    private final BedrockTargetFootprint footprint;
    private final BedrockTargetMachine machine;
    private final BedrockTargetActionExecutor actionExecutor;
    private final BedrockTargetLifecycle lifecycle;
    private final BedrockCriticalExecutor criticalExecutor;
    private final BedrockPlacer placer;
    private final BedrockNetworkSync networkSync;
    private final BedrockTargetState state = new BedrockTargetState();

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level) {
        this(bedrockPos, level, new BedrockPlacer(net.minecraft.client.Minecraft.getInstance()));
    }

    public BedrockTarget(BlockPos bedrockPos, ClientLevel level, BedrockMachineLayout precomputedLayout, BedrockTorchPlacement precomputedPlacement, BlockPos precomputedSlimePos) {
        this(bedrockPos, level, precomputedLayout, precomputedPlacement, precomputedSlimePos,
                new BedrockPlacer(net.minecraft.client.Minecraft.getInstance()));
    }

    BedrockTarget(BlockPos bedrockPos, ClientLevel level, BedrockCriticalExecutor criticalExecutor, BedrockPlacer placer) {
        this(bedrockPos, level, null, null, null, criticalExecutor, placer, null);
    }

    BedrockTarget(
            BlockPos bedrockPos,
            ClientLevel level,
            BedrockCriticalExecutor criticalExecutor,
            BedrockPlacer placer,
            BedrockNetworkSync networkSync
    ) {
        this(bedrockPos, level, null, null, null, criticalExecutor, placer, networkSync);
    }

    private BedrockTarget(BlockPos bedrockPos, ClientLevel level, BedrockPlacer placer) {
        this(bedrockPos, level, null, null, null, new BedrockCriticalExecutor(placer), placer, null);
    }

    private BedrockTarget(
            BlockPos bedrockPos,
            ClientLevel level,
            BedrockMachineLayout precomputedLayout,
            BedrockTorchPlacement precomputedPlacement,
            BlockPos precomputedSlimePos,
            BedrockPlacer placer
    ) {
        this(bedrockPos, level, precomputedLayout, precomputedPlacement, precomputedSlimePos,
                new BedrockCriticalExecutor(placer), placer, null);
    }

    BedrockTarget(
            BlockPos bedrockPos,
            ClientLevel level,
            BedrockMachineLayout precomputedLayout,
            BedrockTorchPlacement precomputedPlacement,
            BlockPos precomputedSlimePos,
            BedrockCriticalExecutor criticalExecutor,
            BedrockPlacer placer,
            BedrockNetworkSync networkSync
    ) {
        this.bedrockPos = bedrockPos;
        this.level = level;
        this.criticalExecutor = criticalExecutor;
        this.placer = placer;
        this.networkSync = networkSync;
        this.layout = precomputedLayout != null ? precomputedLayout : BedrockMachineLayout.find(level, bedrockPos);
        this.pistonPos = this.layout == null ? bedrockPos.above() : this.layout.getPistonPos();
        this.headPos = this.layout == null ? this.pistonPos.above() : this.layout.getHeadPos();
        this.residue = new BedrockTargetResidue(level, this.layout, this.pistonPos, this.headPos);
        this.footprint = new BedrockTargetFootprint(level, bedrockPos, this.pistonPos, this.headPos);
        this.machine = new BedrockTargetMachine(
                level, this.layout, bedrockPos, this.pistonPos, this.headPos,
                this.footprint, precomputedPlacement, precomputedSlimePos, placer);
        BedrockTargetStatusResolver statusResolver = new BedrockTargetStatusResolver(this);
        this.actionExecutor = new BedrockTargetActionExecutor(this);
        this.lifecycle = new BedrockTargetLifecycle(
                this, level, bedrockPos, this.machine, statusResolver, this.state);
        this.conservativeSync = BedrockTargetBlocks.requiresConservativeSync(level.getBlockState(bedrockPos));
        if (this.layout == null || !this.machine.isValid()) {
            this.state.setStatus(Status.FAILED);
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
        return this.machine.torchSupportPos();
    }

    @Override
    public BlockPos torchSupportPos() {
        return getTorchSupportPos();
    }

    public BlockPos getTorchPos() {
        return this.machine.torchPos();
    }

    public BlockPos getSlimePos() {
        return this.machine.slimePos();
    }

    public Status getStatus() {
        return this.state.status();
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
        this.state.beginTick(allowExecute);

        this.lifecycle.updateStatus();
        this.actionExecutor.execute(allowExecute, allowInitialize);
        return this.state.status();
    }

    boolean hasPendingHorizontalLook() {
        return this.criticalExecutor.placer().hasPendingHorizontalLook(this.pistonPos);
    }

    public Status refreshStatusOnly() {
        this.state.setStatus(this.lifecycle.observeStatus());
        return this.state.status();
    }

    public Status refreshStatusOnlyAndAdvance() {
        this.state.advanceStatusOnly();
        this.lifecycle.updateStatus();
        return this.state.status();
    }

    public boolean consumedThroughputThisTick() {
        return this.state.consumedThroughput();
    }

    public Set<BlockPos> getCleanupPositions() {
        return this.footprint.cleanupPositions(
                this.residue, getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getStructuralPositions() {
        return this.footprint.structuralPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getPowerReservationPositions() {
        return this.footprint.powerReservationPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getReservedPositions() {
        return this.footprint.reservedPositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public boolean sharesTorchPlacementWith(BedrockTarget other) {
        return other != null && matchesTorchPlacement(other.machine.torchPlacement());
    }

    public boolean matchesTorchPlacement(BedrockTorchPlacement placement) {
        return this.machine.matchesTorchPlacement(placement);
    }

    public boolean isTorchPoweredBy(BlockPos torchPos) {
        return torchPos != null && BedrockEnvironment.getTorchInfluencePositions(this.pistonPos).contains(torchPos);
    }

    public boolean canReusePowerReservation(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return this.machine.canReusePowerReservation(pos, state);
    }

    public boolean canReusePendingCleanupPosition(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        if (pos.equals(this.pistonPos)) {
            return this.machine.isReusablePistonState(state);
        }
        return canReusePowerReservation(pos, state);
    }

    public Set<BlockPos> getStaticMachinePositions() {
        return this.footprint.staticMachinePositions(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getMachineFootprint() {
        return this.footprint.machineFootprint(
                getTorchSupportPos(), getTorchPos(), getSlimePos());
    }

    public Set<BlockPos> getOwnedTorchPositions() {
        return this.footprint.ownedTorchPositions(getTorchPos());
    }

    @Override
    public void recordTemporary(BlockPos pos) {
        this.footprint.recordTemporary(pos);
    }

    @Override
    public void recordNetworkAttempt() { if (this.networkSync != null) this.networkSync.beginAttempt(this.bedrockPos, this.getMachineFootprint()); }

    @Override
    public int postExecuteSyncTimeoutTicks() { return this.networkSync == null ? BedrockNetworkSync.DEFAULT_CONFIRMATION_TIMEOUT_TICKS : this.networkSync.confirmationTimeoutTicks(); }

    @Override
    public void recordNetworkTimeout() { if (this.networkSync != null) this.networkSync.recordTimeout(this.bedrockPos); }

    @Override
    public boolean canBuildInitialMachine() {
        return this.machine.canBuildInitialMachine();
    }

    @Override
    public boolean hasOwnedTorchPowerSource() {
        return this.machine.hasOwnedTorchPowerSource();
    }

    @Override
    public boolean tryRepowerTorch() {
        return this.machine.tryRepowerTorch(this.state.tickTimes(), this::markThroughputAction);
    }

    @Override
    public void resetPostExecuteAttempt(Status recoveryStatus) {
        if (this.networkSync != null) {
            this.networkSync.recordRetry(this.bedrockPos);
        }
        clearPostExecuteAttemptState();
    }

    private void clearPostExecuteAttemptState() {
        this.state.resetAttempt();
        this.machine.resetAttempt();
        this.residue.resetAttempt();
    }

    @Override
    public boolean hasMachineCleanupResidue() {
        return this.residue.hasMachineCleanupResidue(getSlimePos(), getTorchPos());
    }

    @Override
    public boolean hasPostExecuteSyncResidue() {
        return this.residue.hasPostExecuteSyncResidue();
    }

    private boolean isPostExecuteCollapsed() {
        return this.residue.isPostExecuteCollapsed(this.state.hasTried());
    }

    @Override
    public boolean hasTransientMachineResidue() {
        return this.residue.hasTransientMachineResidue();
    }

    @Override
    public boolean hasAnyTransientMachineResidue() {
        return this.residue.hasAnyTransientMachineResidue(getTorchSupportPos(), getSlimePos());
    }

    @Override
    public boolean hasStablePostExecuteResidue() {
        return this.residue.hasStablePostExecuteResidue();
    }

    @Override
    public void cleanupStablePostExecuteResidue() {
        if (this.residue.cleanupStablePostExecuteResidue(this.state.tickTimes())) {
            markThroughputAction();
        }
    }

    @Override
    public boolean hasPollutedMachineState() {
        return this.residue.hasPollutedMachineState(
                this.state.hasTried(),
                this.state.executeTick(),
                this.state.tickTimes(),
                this.postExecuteSyncTimeoutTicks(),
                this.machine.torchPlacement(),
                getTorchSupportPos(),
                getSlimePos()
        );
    }

    @Override
    public void cleanupPollutedMachineState() {
        if (this.residue.cleanupPollutedMachineState(
                this.state.tickTimes(),
                this.state.hasTried(),
                this.state.executeTick(),
                this.postExecuteSyncTimeoutTicks(),
                this.machine.torchPlacement(),
                getTorchSupportPos(),
                getSlimePos()
        )) {
            markThroughputAction();
        }
    }

    @Override
    public ClientLevel level() {
        return this.level;
    }

    @Override
    public BedrockMachineLayout layout() {
        return this.layout;
    }

    @Override
    public BlockPos bedrockPos() {
        return this.bedrockPos;
    }

    @Override
    public BlockPos pistonPos() {
        return this.pistonPos;
    }

    @Override
    public BlockPos headPos() {
        return this.headPos;
    }

    @Override
    public boolean hasTried() {
        return this.state.hasTried();
    }

    @Override
    public int executeTick() {
        return this.state.executeTick();
    }

    @Override
    public int initializeTick() {
        return this.state.initializeTick();
    }

    @Override
    public int tickTimes() {
        return this.state.tickTimes();
    }

    @Override
    public int stuckTicks() {
        return this.state.stuckTicks();
    }

    @Override
    public Status currentStatus() {
        return this.state.status();
    }

    @Override
    public void setStatus(Status status) {
        this.state.setStatus(status);
    }

    @Override
    public boolean placeInitialTorch() {
        return this.machine.placeTorch();
    }

    @Override
    public void setHasTried(boolean value) {
        this.state.setHasTried(value);
    }

    @Override
    public void setInitializeTick(int value) {
        this.state.setInitializeTick(value);
    }

    @Override
    public void setExecuteTick(int value) {
        this.state.setExecuteTick(value);
    }

    @Override
    public Set<BlockPos> ownedTorchPositions() {
        return this.machine.ownedTorchPositions();
    }

    @Override
    public boolean conservativeSync() {
        return this.conservativeSync;
    }

    @Override
    public BedrockCriticalExecutor criticalExecutor() {
        return this.criticalExecutor;
    }

    @Override
    public BedrockPlacer placer() {
        return this.placer;
    }

    @Override
    public boolean canRepowerNow() {
        return this.machine.canRepowerNow(this.state.tickTimes());
    }

    @Override
    public boolean rebuildLimitReached() {
        return this.machine.rebuildLimitReached();
    }

    @Override
    public void recordRebuild() {
        this.machine.recordRebuild(this.state.tickTimes());
    }

    @Override
    public void incrementStuckTicks() {
        this.state.incrementStuckTicks();
    }

    @Override
    public void markThroughputAction() {
        this.state.markThroughputAction();
    }

}
