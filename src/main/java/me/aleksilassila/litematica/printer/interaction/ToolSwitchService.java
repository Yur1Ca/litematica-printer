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

import java.util.ArrayList;

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
        if (this.tweakeroo.isDurabilityGuardActive()
                && !this.tweakeroo.prepareCurrentTool(pos, state)) {
            return ToolPreparationResult.BLOCKED_BY_DURABILITY;
        }
        int afterSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        if (beforeSlot != afterSlot || stackFingerprintChanged(before, player.getMainHandItem())) {
            this.switchGuard.markSwitchIfNeeded(player.getMainHandItem());
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        return ToolPreparationResult.READY;
    }

    /** Bedrock follows a pure hotbar selection with its own native equipment-tick acknowledgement. */
    public ToolPreparationResult prepareForBedrockBreak(BlockPos pos, BlockState state) {
        LocalPlayer player = this.client.player;
        if (player == null || this.switchGuard.isWaiting() || !this.tweakeroo.isDurabilityGuardActive()
                || this.tweakeroo.isCurrentToolUsable(player.getMainHandItem())) {
            return this.prepareForBreak(pos, state, false);
        }
        var menu = player.containerMenu;
        if (menu == null || menu != player.inventoryMenu || !menu.getCarried().isEmpty()) {
            return this.prepareForBreak(pos, state, false);
        }
        // Only a real guard-driven replacement pays for this comparison. Ordinary mining
        // keeps the existing fast path; a selection plus any menu mutation stays exclusive.
        var inventory = player.getInventory();
        int beforeSlot = InventoryUtils.getSelectedSlot(inventory);
        var beforeItems = new ArrayList<ItemStack>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) beforeItems.add(inventory.getItem(slot).copy());
        var result = this.prepareForBreak(pos, state, false);
        long receipt = this.switchGuard.receipt();
        if (result != ToolPreparationResult.SWITCHED_WAITING_SYNC || player != this.client.player
                || player.containerMenu != menu || !menu.getCarried().isEmpty()
                || beforeSlot == InventoryUtils.getSelectedSlot(inventory)
                || beforeItems.size() != inventory.getContainerSize()) return result;
        for (int slot = 0; slot < beforeItems.size(); slot++) {
            if (!ItemStack.matches(beforeItems.get(slot), inventory.getItem(slot))) return result;
        }
        this.switchGuard.confirmReceipt(receipt);
        return ToolPreparationResult.READY;
    }

    public boolean isDurabilityGuardActive() {
        return this.tweakeroo.isDurabilityGuardActive();
    }

    public boolean isToolUsable(ItemStack stack) {
        return this.tweakeroo.isCurrentToolUsable(stack);
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
