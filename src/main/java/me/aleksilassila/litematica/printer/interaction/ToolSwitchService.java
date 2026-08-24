package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Coordinates Printer's ordinary effective-tool selection with Tweakeroo's own tool policies. */
public final class ToolSwitchService {
    private final Minecraft client;
    private final TweakerooToolSwitchPort tweakeroo;
    private final InventorySwitchGuard switchGuard;

    public ToolSwitchService(
            Minecraft client,
            TweakerooToolSwitchPort tweakeroo,
            InventorySwitchGuard switchGuard
    ) {
        this.client = client;
        this.tweakeroo = tweakeroo;
        this.switchGuard = switchGuard;
    }

    /**
     * Prepares the hand for one damage-producing break packet. Printer may select an effective
     * tool, but only Tweakeroo decides whether a nearly-broken tool should be replaced.
     */
    public ToolPreparationResult prepareForBreak(BlockPos pos, BlockState state, boolean allowEffectiveSwitch) {
        LocalPlayer player = this.client.player;
        if (player == null || player.getAbilities().instabuild || pos == null || state == null
                || state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return player == null ? ToolPreparationResult.UNAVAILABLE : ToolPreparationResult.READY;
        }
        if (this.switchGuard.isWaiting()) {
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }

        int beforeSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        ItemStack before = player.getMainHandItem().copy();
        if (allowEffectiveSwitch) {
            if (this.tweakeroo.isEffectiveToolSwitchEnabled()) {
                this.tweakeroo.switchToEffectiveTool(pos);
            } else if (Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()) {
                InventoryUtils.switchToBestTool(player, state, pos);
            }
        }
        // Tweakeroo checks its own toggle and durability threshold inside this call. Printer
        // deliberately does not mirror either policy.
        this.tweakeroo.swapNearlyBrokenTool();
        int afterSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        if (beforeSlot != afterSlot || stackFingerprintChanged(before, player.getMainHandItem())) {
            this.switchGuard.markSwitchIfNeeded(player.getMainHandItem());
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        return ToolPreparationResult.READY;
    }

    private static boolean stackFingerprintChanged(ItemStack before, ItemStack after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isEmpty() != after.isEmpty();
        }
        return before.getItem() != after.getItem()
                || before.getDamageValue() != after.getDamageValue()
                || before.getCount() != after.getCount();
    }
}
