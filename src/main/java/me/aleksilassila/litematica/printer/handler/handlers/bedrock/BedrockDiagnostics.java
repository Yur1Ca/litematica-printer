package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

/** Opt in with -Dlitematica.printer.bedrockDebug=true; observation never gates execution. */
final class BedrockDiagnostics {
    private static final boolean ENABLED = Boolean.getBoolean("litematica.printer.bedrockDebug");

    private BedrockDiagnostics() { }

    static void trace(BedrockTarget target, String event) {
        if (!ENABLED) return;
        var player = Minecraft.getInstance().player;
        var level = target.level();
        float progress = player == null ? 0.0F
                : PlayerUtils.getDestroyProgress(player, Blocks.PISTON.defaultBlockState(), player.getMainHandItem());
        Reference.LOGGER.info(
                "[PrinterBedrock] event={} target={} age={} executeTick={} status={} pendingPlacement={} "
                        + "timeout={} bedrock={} piston={} head={} torches={} hand={} offhand={} pistonProgress={}",
                event, target.getBedrockPos(), target.tickTimes(), target.executeTick(), target.currentStatus(),
                target.hasPendingPistonRetry(), target.postExecuteSyncTimeoutTicks(),
                level.getBlockState(target.getBedrockPos()), level.getBlockState(target.getPistonPos()),
                level.getBlockState(target.getHeadPos()), target.getOwnedTorchPositions(),
                player == null ? "none" : player.getMainHandItem(),
                player == null ? "none" : player.getOffhandItem(), progress);
    }
}
