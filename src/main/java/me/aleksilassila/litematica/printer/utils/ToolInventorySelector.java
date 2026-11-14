package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.ToolSelectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntPredicate;

/** Selects and equips the most effective tool for one block state. */
public final class ToolInventorySelector {
    private ToolInventorySelector() {
    }

    public static boolean switchToBestTool(Minecraft client, LocalPlayer player, BlockState blockState) {
        ClientLevel level = client == null ? null : client.level;
        BlockPos pos = player == null ? null : player.blockPosition();
        return switchToBestTool(client, player, blockState, level, pos);
    }

    public static boolean switchToBestTool(
            Minecraft client,
            LocalPlayer player,
            BlockState blockState,
            ClientLevel level,
            BlockPos pos
    ) {
        if (client == null || player == null || blockState == null || blockState.isAir()) {
            return false;
        }
        if (RuntimeAccess.get().inventorySwitchGuard().isWaiting()
                || PlayerUtils.getAbilities(player).instabuild) {
            return false;
        }

        ItemStack currentStack = player.getMainHandItem();
        float bestProgress = destroyProgress(player, level, pos, blockState, currentStack);
        boolean preferSilkTouch = ToolSelectionUtils.prefersSilkTouchForDrops(blockState);
        boolean bestHasSilkTouch = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(currentStack);
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        NonNullList<ItemStack> stacks = InventoryUtils.getMainStacks(player.getInventory());
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float progress = destroyProgress(player, level, pos, blockState, stack);
            boolean stackHasSilkTouch = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(stack);
            if ((stackHasSilkTouch && !bestHasSilkTouch)
                    || stackHasSilkTouch == bestHasSilkTouch && progress > bestProgress) {
                bestProgress = progress;
                bestHasSilkTouch = stackHasSilkTouch;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot == -1 || bestStack.isEmpty()
                || !InventoryUtils.setPickedItemToHand(bestSlot, bestStack, client)) {
            return false;
        }
        if (!Inventory.isHotbarSlot(bestSlot)) {
            RuntimeAccess.get().inventorySwitchGuard().markSwitchIfNeeded(bestStack);
        }
        return true;
    }

    /** Finds an exact inventory slot; the caller supplies the centralized safety policy. */
    public static int findBestUsableToolSlot(
            LocalPlayer player,
            BlockState state,
            ClientLevel level,
            BlockPos pos,
            IntPredicate usableSlot,
            boolean replacement
    ) {
        int selected = InventoryUtils.getSelectedSlot(player.getInventory());
        ItemStack current = player.getMainHandItem();
        boolean preferSilk = ToolSelectionUtils.prefersSilkTouchForDrops(state);
        boolean currentUsable = !replacement && usableSlot.test(selected);
        float bestProgress = currentUsable ? destroyProgress(player, level, pos, state, current) : -1F;
        boolean bestSilk = currentUsable && preferSilk && ToolSelectionUtils.hasSilkTouch(current);
        int bestSlot = -1;
        var stacks = InventoryUtils.getMainStacks(player.getInventory());
        for (int slot = 0; slot < Math.min(36, stacks.size()); slot++) {
            ItemStack stack = stacks.get(slot);
            if (slot == selected || stack.isEmpty() || replacement && !stack.isDamageableItem()
                    || replacement && stack.get(DataComponents.TOOL) == null && !(stack.getItem() instanceof ShearsItem)
                    || replacement && state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)
                    || !usableSlot.test(slot)) continue;
            float progress = destroyProgress(player, level, pos, state, stack);
            if (!(progress > 0F)) continue;
            boolean silk = preferSilk && ToolSelectionUtils.hasSilkTouch(stack);
            if (silk && !bestSilk || silk == bestSilk && progress > bestProgress) {
                bestProgress = progress;
                bestSilk = silk;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static float destroyProgress(
            LocalPlayer player,
            ClientLevel level,
            BlockPos pos,
            BlockState state,
            ItemStack stack
    ) {
        float hardness = level == null || pos == null
                ? state.getBlock().defaultDestroyTime()
                : state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) return 0.0F;
        if (hardness == 0.0F) return 1.0F;
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }
}
