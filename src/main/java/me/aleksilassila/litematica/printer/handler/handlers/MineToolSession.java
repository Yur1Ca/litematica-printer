package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

import java.util.List;

final class MineToolSession {
    private Item sessionToolItem;
    private int remainingInstantBudget;
    private final TweakerooAdapter tweakeroo;

    MineToolSession(TweakerooAdapter tweakeroo) {
        this.tweakeroo = tweakeroo;
    }

    void reset() {
        this.sessionToolItem = null;
        this.remainingInstantBudget = 0;
    }

    void beginTick() {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        // 0 = unlimited: batch-dispatch the whole candidate set per tick. The interaction service
        // owns durability protection independently of this batch budget.
        this.remainingInstantBudget = configuredBudget <= 0 ? -1 : configuredBudget;
    }

    MineBreakExecutor.Target selectTarget(List<MineBreakExecutor.Target> candidates) {
        // The running vanilla target continues in MineHandler before fresh selection. Completed,
        // failed or deferred candidates re-enter their original rank instead of tool affinity.
        MineBreakExecutor.Target selected = candidates.get(0);
        this.sessionToolItem = selected.bestToolItem();
        return selected;
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
                || result == BlockBreakResult.BLOCKED
                || hasActiveMinePos
                || !this.hasInstantBudget()
                ;
    }

    void consumeInstantBudget() {
        if (this.remainingInstantBudget > 0) {
            this.remainingInstantBudget--;
        }
    }

    boolean hasInstantBudget() {
        return this.remainingInstantBudget < 0 || this.remainingInstantBudget > 0;
    }

    boolean ensureHandToolProtected(LocalPlayer player, MineBreakExecutor.Target target) {
        // The native service must be allowed to find an existing safe spare before the old hand
        // can reject this target. Preserve the optional-mod precheck when native protection is off.
        if (Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue() > 0) return true;
        if (player == null || player.getAbilities().instabuild) {
            return true;
        }
        if (this.tweakeroo.isCurrentToolUsable(player.getMainHandItem())) {
            return true;
        }
        boolean protectedTool = this.tweakeroo.prepareCurrentTool(
                target == null ? null : target.pos(), target == null ? null : target.state());
        return protectedTool;
    }

}
