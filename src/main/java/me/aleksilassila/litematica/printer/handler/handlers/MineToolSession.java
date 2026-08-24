package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

final class MineToolSession {
    private Item sessionToolItem;
    private BlockPos lastSessionPos;

    void reset() {
        this.sessionToolItem = null;
        this.lastSessionPos = null;
    }

    Comparator<MineBreakExecutor.Target> comparator(LocalPlayer player) {
        return Comparator
                .comparingDouble((MineBreakExecutor.Target target) -> distanceScore(player, target))
                .thenComparingInt(target -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getX())
                .thenComparingInt(target -> target.pos().getZ());
    }

    MineBreakExecutor.Target selectTarget(List<MineBreakExecutor.Target> candidates, MineBreakExecutor analyzer, LocalPlayer player) {
        MineBreakExecutor.Target nearest = candidates.get(0);
        if (this.lastSessionPos != null) {
            for (MineBreakExecutor.Target target : candidates) {
                if (target.pos().equals(this.lastSessionPos)) {
                    this.sessionToolItem = target.bestToolItem();
                    return target;
                }
            }
        }
        if (this.sessionToolItem != null) {
            for (MineBreakExecutor.Target target : candidates) {
                if (analyzer.hasSameBestTool(target, this.sessionToolItem)) {
                    this.lastSessionPos = target.pos();
                    return target;
                }
            }
        }
        this.sessionToolItem = nearest.bestToolItem();
        this.lastSessionPos = nearest.pos();
        return nearest;
    }

    void startSession(MineBreakExecutor.Target firstTarget) {
        this.sessionToolItem = firstTarget.bestToolItem();
    }

    boolean matchesSessionTool(MineBreakExecutor analyzer, MineBreakExecutor.Target target) {
        return analyzer.hasSameBestTool(target, this.sessionToolItem);
    }

    boolean shouldStop(BlockBreakResult result, boolean hasActiveMinePos) {
        return result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.ABORTED
                || hasActiveMinePos
                ;
    }

    /**
     * 当一个破坏目标已破掉(或服务端已确认完成)时,释放单目标黏性,允许 selectTarget 选下一个目标。
     * 块破掉后会自动离开候选集,黏性循环本就找不到它而失效;这里显式清除以覆盖「同位置被掉落方块/流体重新占据」等边缘情况。
     */
    void onTargetResolved(BlockBreakResult result, BlockPos pos) {
        if ((result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT)
                && pos.equals(this.lastSessionPos)) {
            this.lastSessionPos = null;
        }
    }

    static double distanceScore(LocalPlayer player, MineBreakExecutor.Target target) {
        Vec3 eye = player.getEyePosition();
        return Vec3.atCenterOf(target.pos()).distanceToSqr(eye);
    }

}
