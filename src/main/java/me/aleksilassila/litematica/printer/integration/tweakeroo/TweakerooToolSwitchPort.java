package me.aleksilassila.litematica.printer.integration.tweakeroo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Optional Tweakeroo tool-switch capability. The printer never reimplements its durability policy. */
public interface TweakerooToolSwitchPort {
    boolean isEffectiveToolSwitchEnabled();

    boolean isNearlyBrokenToolSwapEnabled();

    void switchToEffectiveTool(BlockPos pos);

    void swapNearlyBrokenTool();

    boolean isDurabilityGuardActive();

    boolean isCurrentToolUsable(ItemStack stack);

    boolean prepareCurrentTool(@Nullable BlockPos pos, @Nullable BlockState state);

}
