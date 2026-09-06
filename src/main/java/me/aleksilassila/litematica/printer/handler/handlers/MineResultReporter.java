package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.HudStatus;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.core.BlockPos;

final class MineResultReporter {
    private MineResultReporter() {
    }

    static void record(BlockPos blockPos, BlockBreakResult result) {
        switch (result) {
            case COMPLETED -> {
                InteractionUtils.getRuntime().markRecentlyBroken(blockPos);
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordRateUnit(HudStatsManager.Mode.MINE, 1);
                HudStatsManager.getRuntime().recordStatus(HudStatsManager.Mode.MINE, HudStatus.RUNNING);
            }
            case COMPLETED_WAIT -> {
                InteractionUtils.getRuntime().markRecentlyBroken(blockPos);
                InteractionUtils.getRuntime().markPendingBroken(blockPos, ConfigUtils.getBreakCooldown());
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordDeferred(HudStatsManager.Mode.MINE, HudStatus.WAITING_SERVER_CONFIRM);
            }
            case IN_PROGRESS -> {
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordStatus(HudStatsManager.Mode.MINE, HudStatus.BREAKING);
            }
            case ABORTED -> {
                HudStatsManager.getRuntime().trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.getRuntime().recordDeferred(HudStatsManager.Mode.MINE, HudStatus.MINING_INTERRUPTED);
            }
            case FAILED -> HudStatsManager.getRuntime().recordFailure(HudStatsManager.Mode.MINE, HudStatus.BREAKING_FAILED);
        }
    }
}
