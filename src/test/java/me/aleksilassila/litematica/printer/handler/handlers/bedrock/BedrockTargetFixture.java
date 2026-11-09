package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Lets cross-package regression probes inject the actual production owner without a production-only test constructor. */
public abstract class BedrockTargetFixture extends BedrockTarget {
    protected BedrockTargetFixture(BlockPos pos, ClientLevel level, BedrockMachineLayout layout,
                                   BedrockTorchPlacement torch, BlockPos slime, Object critical,
                                   BedrockPlacer placer, Object networkSync) {
        super(pos, level, layout, torch, slime, (BedrockCriticalExecutor) critical, placer,
                (BedrockNetworkSync) networkSync);
    }
}
