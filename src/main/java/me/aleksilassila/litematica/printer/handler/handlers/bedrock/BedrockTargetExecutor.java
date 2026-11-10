package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;


/** Advances active target state machines without owning admission or scanning policy. */
final class BedrockTargetExecutor {
    private static final int FAILURE_RETRY_COOLDOWN_TICKS = 12;
    private static final int OUT_OF_RANGE_RETRY_COOLDOWN_TICKS = 6;

    private final BedrockTargetRegistry targets;
    private final BedrockCleanupCoordinator cleanup;
    private final BedrockRunStats stats;
    private final BedrockPlacer placer;
    private final BedrockNetworkSync networkSync;
    private final BiConsumer<BlockPos, Integer> retryCooldown;
    private final BedrockCriticalExecutor critical;
    private final BedrockThroughputScheduler<BlockPos> starts;
    private final Set<BedrockTarget> processed = new LinkedHashSet<>();
    private long processedTick = Long.MIN_VALUE;

    BedrockTargetExecutor(
            BedrockTargetRegistry targets,
            BedrockCleanupCoordinator cleanup,
            BedrockRunStats stats,
            BiConsumer<BlockPos, Integer> retryCooldown,
            BedrockPlacer placer,
            BedrockNetworkSync networkSync,
            BedrockCriticalExecutor critical,
            BedrockThroughputScheduler<BlockPos> starts
    ) {
        this.targets = targets;
        this.cleanup = cleanup;
        this.stats = stats;
        this.retryCooldown = retryCooldown;
        this.placer = placer;
        this.networkSync = networkSync;
        this.critical = critical;
        this.starts = starts;
    }

    void reset() {
        this.processed.clear();
        this.processedTick = Long.MIN_VALUE;
    }

    boolean hasPreparationMaterialHand() {
        var player = Minecraft.getInstance().player;
        return player != null && (player.getMainHandItem().is(Items.SLIME_BLOCK)
                || player.getMainHandItem().is(Items.REDSTONE_TORCH));
    }

    /** One dispatcher owns both initial construction and already-powered machines. */
    void advance(ClientLevel level, boolean allowConstruction) {
        long now = me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get().currentTick();
        if (now != this.processedTick) {
            this.processedTick = now;
            this.processed.clear();
        }
        if (this.placer.isInventorySettling() || this.placer.hasActiveCleanupMining()) return;
        var active = this.snapshot();
        var side = this.targets.findSideExclusive();
        for (var target : active) {
            if (side != null && target != side || this.processed.contains(target)) continue;
            if (BedrockEnvironment.findFirstOutOfRangePosition(target.getStaticMachinePositions()) != null) {
                this.retire(target, "out_of_range", level);
                this.processed.add(target);
            }
        }
        boolean finishMaterials = this.hasPreparationMaterialHand();
        if (finishMaterials) this.prepareTargets(active, side, allowConstruction);
        if (!this.placer.isToolSettling() && !this.placer.hasActiveCleanupMining() && side == null) {
            var ready = active.stream().filter(target -> !this.processed.contains(target) && !target.hasTried()
                    && target.getStatus() == BedrockTarget.Status.EXTENDED && target.canMutatePower()).toList();
            var sent = this.critical.submitBatch(ready, this.availableNewStarts(),
                    target -> this.starts.hasStarted(target.getBedrockPos()));
            sent.forEach(target -> {
                this.starts.started(target.getBedrockPos());
                target.onCriticalBatchDispatched();
            });
            this.processed.addAll(sent);
        }
        if (!finishMaterials) this.prepareTargets(active, side, allowConstruction);
        this.observeAndRetire(level);
        if (allowConstruction && side == null && !this.placer.isInventorySettling()
                && !this.placer.hasActiveCleanupMining()) this.primeTargets();
        // Finish the current material handoff before recovery may select or move a tool.
        if (side == null && this.snapshot().stream().anyMatch(target ->
                this.canAdvanceConstruction(target) && this.needsCriticalTool(target))) {
            this.placer.prewarmCriticalTool();
        }
        for (var target : active) {
            if (this.processed.contains(target) || side != null && target != side) continue;
            if (this.placer.isInventorySettling() || this.placer.hasActiveCleanupMining()) break;
            boolean recovering = target.hasTried() || target.getStatus() == BedrockTarget.Status.RECOVERING;
            boolean poweredStall = target.getStatus() == BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE;
            boolean polluted = !recovering && target.hasPollutedMachineState();
            if (recovering) target.settleDispatchedAttempt(true);
            else if (poweredStall || polluted) {
                if (!allowConstruction) continue;
                // A polluted support may need material preparation before its residue can be cleaned.
                if (polluted && this.placer.isToolSettling()) continue;
                if (this.canAdvanceConstruction(target)) this.placer.withConstructionStart(
                        target.getBedrockPos(), this.starts, target::tickPreparation);
            } else continue;
            this.processed.add(target);
        }
        this.observeAndRetire(level);
    }

    private boolean needsCriticalTool(BedrockTarget target) {
        if (target.hasTried() || !target.hasPreparedPower() || target.hasPollutedMachineState()) return false;
        return switch (target.getStatus()) {
            case EXTENDED -> true;
            case UNINITIALIZED -> target.initializeTick() == target.tickTimes();
            case NEEDS_WAITING -> {
                int age = target.tickTimes() - target.initializeTick();
                yield age >= 0 && age < target.postExecuteSyncTimeoutTicks();
            }
            default -> false;
        };
    }

    private void prepareTargets(List<BedrockTarget> active, BedrockTarget side, boolean allowConstruction) {
        if (!allowConstruction) return;
        // Already-confirmed priming pistons can finish their Torch before another material or recovery stage.
        for (int pass = 0; pass < 2; pass++) {
            for (var target : active) {
                if (this.processed.contains(target) || side != null && target != side || target.hasTried()
                        || target.getStatus() == BedrockTarget.Status.RECOVERING
                        || target.getStatus() == BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                        || target.hasPollutedMachineState()
                        || target.getStatus() == BedrockTarget.Status.EXTENDED && !target.isHorizontalLayout()) continue;
                boolean torchReady = target.getStatus() == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
                if (torchReady != (pass == 0)) continue;
                if (!this.canAdvanceConstruction(target)) continue;
                if (this.placer.isInventorySettling() || this.placer.isToolSettling() || this.placer.hasActiveCleanupMining()) return;
                this.placer.withConstructionStart(target.getBedrockPos(), this.starts, () -> {
                    if (target.isHorizontalLayout()) target.tick(true, true);
                    else target.tickPreparation();
                });
                if (target.hasTried()) this.starts.started(target.getBedrockPos());
                if (target.getStatus() != BedrockTarget.Status.RECOVERING) this.processed.add(target);
            }
        }
    }

    private void primeTargets() {
        boolean toolPending = this.placer.isToolSettling();
        var player = Minecraft.getInstance().player;
        // A pending MAIN acknowledgement may overlap only an already-funded absolute OFF_HAND use.
        if (toolPending && (player == null || !InventoryUtils.getOffhandStack(player).is(Items.PISTON)
                || this.placer.availablePistons() <= 0)) return;
        var owners = new LinkedHashMap<BlockPos, BedrockTarget>();
        var requests = new ArrayList<BedrockPlacer.PistonRequest>();
        for (BedrockTarget target : this.snapshot()) {
            if (target.getStatus() != BedrockTarget.Status.UNINITIALIZED || target.hasTried()
                    || !target.canMutatePower() || !target.canBuildInitialMachine()
                    || !BedrockEnvironment.arePositionsInteractable(target.getStaticMachinePositions())) continue;
            if (!this.canAdvanceConstruction(target)) continue;
            requests.add(new BedrockPlacer.PistonRequest(target.getPistonPos(), target.layout().getPrimingFacing(),
                    toolPending ? null : target.initialBatchTorchPlacement()));
            owners.put(target.getPistonPos(), target);
        }
        for (var sent : this.placer.placePistonBatch(requests, this.availableNewStarts(),
                request -> this.starts.hasStarted(owners.get(request.pistonPos()).getBedrockPos()))) {
            var owner = owners.get(sent.pistonPos());
            this.starts.started(owner.getBedrockPos());
            owner.onInitialBatchDispatched(sent.torchPlacement());
        }
    }

    private boolean canAdvanceConstruction(BedrockTarget target) {
        return this.starts.hasStarted(target.getBedrockPos()) || this.availableNewStarts() > 0;
    }

    private int availableNewStarts() {
        int cap = BedrockSchedulingPolicy.effectiveThroughput(Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue(),
                Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue());
        int running = 0;
        for (var iterator = this.targets.iterator(); iterator.hasNext();) {
            var target = iterator.next();
            if (target != null && this.starts.hasStarted(target.getBedrockPos())) running++;
        }
        // Late server residue can reclaim a retired owner's slot. Pending reservations
        // cannot start through it, including after the configured cap was reduced.
        return Math.max(0, Math.min(this.starts.availableStarts(), cap - running - this.cleanup.reachableCycleCount()));
    }

    private List<BedrockTarget> snapshot() {
        var result = new ArrayList<BedrockTarget>();
        this.targets.iterator().forEachRemaining(target -> { if (target != null) result.add(target); });
        return result;
    }

    private void retire(BedrockTarget target, String reason, ClientLevel level) {
        Iterator<BedrockTarget> iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == target) {
                this.cleanupTarget(iterator, target, reason, level);
                return;
            }
        }
    }

    /** Completion bookkeeping and queue ownership do not mutate inventory or world state. */
    void observeAndRetire(ClientLevel level) {
        Iterator<BedrockTarget> iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null) { iterator.remove(); continue; }
            if (!BedrockEnvironment.isChunkLoaded(level, target.getBedrockPos())) continue;
            BedrockTarget.Status status = target.getStatus();
            if (status != BedrockTarget.Status.FAILED && status != BedrockTarget.Status.STUCK) {
                status = target.refreshStatusOnly();
            }
            if (status == BedrockTarget.Status.RETRACTED || status == BedrockTarget.Status.FAILED
                    || status == BedrockTarget.Status.STUCK) this.cleanupTarget(iterator, target, null, level);
        }
    }

    private void cleanupTarget(
            Iterator<BedrockTarget> iterator,
            BedrockTarget target,
            String reason,
            ClientLevel level
    ) {
        BedrockDiagnostics.trace(target, reason == null ? "retired" : "retired:" + reason);
        if (target.getStatus() == BedrockTarget.Status.FAILED
                || target.getStatus() == BedrockTarget.Status.STUCK) {
            this.retryCooldown.accept(target.getBedrockPos(), FAILURE_RETRY_COOLDOWN_TICKS);
        } else if ("out_of_range".equals(reason)) {
            this.retryCooldown.accept(target.getBedrockPos(), OUT_OF_RANGE_RETRY_COOLDOWN_TICKS);
        }
        if (reason == null && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()))) {
            this.networkSync.confirmed(target.getBedrockPos());
            this.stats.confirmedSuccesses++;
            this.stats.lastReason = "running";
            HudStatsManager.getRuntime().recordRateUnit(HudStatsManager.Mode.BEDROCK, 1);
        }
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            this.networkSync.discarded(target.getBedrockPos(), false);
            this.stats.failedTargets++;
            this.stats.lastReason = "failed";
        } else if (target.getStatus() == BedrockTarget.Status.STUCK) {
            this.networkSync.discarded(target.getBedrockPos(), true);
            this.stats.stuckTargets++;
            this.stats.lastReason = "stuck";
        } else if ("out_of_range".equals(reason)) {
            this.networkSync.discarded(target.getBedrockPos(), false);
        }
        this.placer.cancelPistonPlacement(target.getPistonPos());
        iterator.remove();
        this.cleanup.enqueueCycle(target.getBedrockPos(), target.getCleanupPositions(), target.isHorizontalLayout());
    }
}
