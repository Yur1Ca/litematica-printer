package me.aleksilassila.litematica.printer.integration.tweakeroo;

import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.UsageRestrictionCache;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.core.BlockPos;

/** Optional Tweakeroo capability boundary used by mining. */
public final class TweakerooAdapter implements TweakerooToolSwitchPort {
    private final UsageRestrictionCache restrictionCache = new UsageRestrictionCache();

    public boolean isLoaded() {
        return ModLoadUtils.isTweakerooLoaded();
    }

    public boolean isToolSwitchEnabled() {
        return this.isLoaded() && TweakerooUtils.isToolSwitchEnabled();
    }

    @Override
    public boolean isEffectiveToolSwitchEnabled() {
        return this.isToolSwitchEnabled();
    }

    @Override
    public boolean isNearlyBrokenToolSwapEnabled() {
        return this.isLoaded() && TweakerooUtils.isSwapAlmostBrokenToolsEnabled();
    }

    @Override
    public void switchToEffectiveTool(BlockPos pos) {
        if (this.isEffectiveToolSwitchEnabled()) {
            TweakerooUtils.trySwitchToEffectiveTool(pos);
        }
    }

    @Override
    public void swapNearlyBrokenTool() {
        if (this.isNearlyBrokenToolSwapEnabled()) {
            TweakerooUtils.trySwapCurrentToolIfNearlyBroken();
        }
    }

    @Override
    public boolean isDurabilityGuardActive() {
        return this.isNearlyBrokenToolSwapEnabled()
                && Configs.Core.WORK_RANGE.getIntegerValue() <= 6
                && Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue() > 0;
    }

    @Override
    public boolean isCurrentToolUsable(net.minecraft.world.item.ItemStack stack) {
        if (!this.isDurabilityGuardActive()) {
            return true;
        }
        return !TweakerooUtils.isCurrentToolUnsafe(stack);
    }

    @Override
    public boolean prepareCurrentTool(BlockPos pos, BlockState state) {
        if (!this.isDurabilityGuardActive()) {
            return true;
        }
        if (this.isCurrentToolUsable(this.currentStack())) {
            return true;
        }
        this.swapNearlyBrokenTool();
        if (this.isCurrentToolUsable(this.currentStack())) {
            return true;
        }
        if (pos != null && this.isEffectiveToolSwitchEnabled()) {
            this.switchToEffectiveTool(pos);
        }
        return this.isCurrentToolUsable(this.currentStack());
    }

    private net.minecraft.world.item.ItemStack currentStack() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? net.minecraft.world.item.ItemStack.EMPTY : client.player.getMainHandItem();
    }

    public boolean allowsBreak(BlockState state) {
        if (!this.isLoaded()) {
            return true;
        }
        UsageRestriction.ListType listType = TweakerooUtils.getBreakRestrictionListType();
        return this.restrictionCache.allows(
                "tweakeroo",
                listType,
                TweakerooUtils.getBreakRestrictionBlacklist(),
                TweakerooUtils.getBreakRestrictionWhitelist(),
                state
        );
    }

    public boolean allowsConfiguredBreak(BlockState state) {
        Object optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
        UsageRestriction.ListType listType = optionListValue instanceof UsageRestriction.ListType type
                ? type
                : UsageRestriction.ListType.NONE;
        return this.restrictionCache.allows(
                "custom",
                listType,
                Configs.Mine.EXCAVATE_BLACKLIST.getStrings(),
                Configs.Mine.EXCAVATE_WHITELIST.getStrings(),
                state
        );
    }
}
