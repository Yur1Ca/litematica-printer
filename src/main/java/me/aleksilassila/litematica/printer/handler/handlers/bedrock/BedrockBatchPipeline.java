package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Keeps inventory-owning batches separate from machine preparation and observation. */
final class BedrockBatchPipeline {
    private final BedrockTargetRegistry targets;
    private final BedrockTargetExecutor executor;
    private final BedrockCriticalExecutor critical;
    private final BedrockPlacer placer;

    BedrockBatchPipeline(BedrockTargetRegistry targets, BedrockTargetExecutor executor,
                         BedrockCriticalExecutor critical, BedrockPlacer placer) {
        this.targets = targets;
        this.executor = executor;
        this.critical = critical;
        this.placer = placer;
    }

    void advance(ClientLevel level, boolean allowPreparation) {
        if (this.placer.isInventorySettling()) return;
        var active = this.snapshot();
        if (active.isEmpty()) return;
        // Horizontal look settlement retains its existing single-machine path.
        if (active.stream().anyMatch(BedrockTarget::isHorizontalLayout)) {
            this.advanceSinglePath(level, active.size(), allowPreparation);
            return;
        }

        var ready = active.stream().filter(target -> !target.hasTried() && target.canMutatePower()
                && target.getStatus() == BedrockTarget.Status.EXTENDED).toList();
        if (allowPreparation && !ready.isEmpty()) {
            var player = Minecraft.getInstance().player;
            boolean hadPistons = player != null && InventoryUtils.getOffhandStack(player).is(Items.PISTON);
            var sent = this.critical.submitBatch(ready);
            sent.forEach(BedrockTarget::onCriticalBatchDispatched);
            if (!sent.isEmpty() || !hadPistons || this.placer.isInventorySettling() || this.placer.isToolSettling()
                    || RuntimeAccess.get().inventorySwitchGuard().isWaiting()) return;
        }

        // Each existing machine can progress once, independently of the admission interval.
        var processed = new LinkedHashSet<BedrockTarget>();
        int budget = active.size();
        int remaining = this.executor.processPreparation(level, budget, true, processed, allowPreparation);
        this.executor.processPreparation(level, remaining, false, processed, allowPreparation);
        if (!allowPreparation || this.placer.isInventorySettling()) return;

        var uninitialized = new LinkedHashMap<BlockPos, BedrockTarget>();
        var requests = new ArrayList<BedrockPlacer.PistonRequest>();
        for (BedrockTarget target : this.snapshot()) {
            if (target.getStatus() != BedrockTarget.Status.UNINITIALIZED || target.hasTried()
                    || !target.canMutatePower() || !target.canBuildInitialMachine()
                    || !BedrockEnvironment.arePositionsInteractable(target.getStaticMachinePositions())) continue;
            requests.add(new BedrockPlacer.PistonRequest(target.getPistonPos(), target.layout().getPrimingFacing(),
                    target.initialBatchTorchPlacement()));
            uninitialized.put(target.getPistonPos(), target);
        }
        for (var sent : this.placer.placePistonBatch(requests)) {
            uninitialized.get(sent.pistonPos()).onInitialBatchDispatched(sent.torchPlacement());
        }
        // Preparing the tool while priming pistons settle overlaps the real
        // equipment-tick acknowledgement with the machine's natural extension.
        this.placer.prewarmCriticalTool();
    }

    private List<BedrockTarget> snapshot() {
        var result = new ArrayList<BedrockTarget>();
        this.targets.iterator().forEachRemaining(target -> { if (target != null) result.add(target); });
        return result;
    }

    private void advanceSinglePath(ClientLevel level, int budget, boolean allowPreparation) {
        var processed = new LinkedHashSet<BedrockTarget>();
        var sideLook = allowPreparation ? this.targets.findSideLookTarget() : null;
        int remaining = this.executor.process(level, budget, true, processed, sideLook, allowPreparation);
        this.executor.process(level, remaining, false, processed, sideLook, allowPreparation);
    }
}
