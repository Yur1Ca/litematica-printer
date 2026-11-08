package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Resolves one target's lifecycle state before its action executor runs. */
final class BedrockTargetLifecycle {
    private final BedrockTarget owner;
    private final ClientLevel level;
    private final BlockPos bedrockPos;
    private final BedrockTargetMachine machine;
    private final BedrockTargetStatusResolver statusResolver;
    private final BedrockTargetState state;

    BedrockTargetLifecycle(
            BedrockTarget owner,
            ClientLevel level,
            BlockPos bedrockPos,
            BedrockTargetMachine machine,
            BedrockTargetStatusResolver statusResolver,
            BedrockTargetState state
    ) {
        this.owner = owner;
        this.level = level;
        this.bedrockPos = bedrockPos;
        this.machine = machine;
        this.statusResolver = statusResolver;
        this.state = state;
    }

    void updateStatus() {
        if (this.isTargetCompleted()) {
            this.state.setStatus(BedrockTarget.Status.RETRACTED);
            return;
        }

        if (!this.state.hasTried() && !this.owner.canMutatePower()) {
            if (this.state.status() != BedrockTarget.Status.RECOVERING) {
                this.state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            }
            return;
        }

        if (!this.state.hasTried()
                && this.state.status() != BedrockTarget.Status.RECOVERING
                && !this.ensureTorchPlacement()) {
            if (this.machine.isInventorySettling()) {
                this.state.setStatus(BedrockTarget.Status.NEEDS_WAITING);
                return;
            }
            this.state.setStatus(BedrockTarget.Status.FAILED);
            BedrockMessages.actionBar("bedrockminer.fail.place.redstonetorch");
            return;
        }

        this.state.setStatus(this.statusResolver.resolve(true));
    }

    BedrockTarget.Status observeStatus() {
        return this.statusResolver.resolve(false);
    }

    private boolean ensureTorchPlacement() {
        return this.machine.ensureTorchPlacement(
                this::isPlacementReservedByOtherTarget,
                this.owner::markThroughputAction
        );
    }

    private boolean isTargetCompleted() {
        return !BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(this.bedrockPos));
    }

    private boolean isPlacementReservedByOtherTarget(BedrockTorchPlacement placement) {
        return placement != null
                && BedrockController.isTorchPlacementReservedByOtherTarget(placement, this.owner);
    }
}
