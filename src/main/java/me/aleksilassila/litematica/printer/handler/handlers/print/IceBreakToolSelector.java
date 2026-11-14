package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.interaction.MiningInteractionPort;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

final class IceBreakToolSelector {
    private IceBreakToolSelector() {
    }

    static boolean switchToNonSilkTouchBreakItem(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        BlockState iceState = Blocks.ICE.defaultBlockState();
        ItemStack currentStack = player.getMainHandItem();
        if (isEffectiveNonSilkTouchBreakItem(client, pos, player, iceState, currentStack)) {
            return true;
        }

        Inventory inventory = player.getInventory();
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;
        float bestProgress = 0.0F;
        for (int slot = 0; slot < InventoryUtils.getMainStacks(inventory).size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isEffectiveNonSilkTouchBreakItem(client, pos, player, iceState, stack)) {
                continue;
            }
            float progress = PlayerUtils.getDestroyProgress(player, iceState, stack);
            if (progress > bestProgress) {
                bestProgress = progress;
                bestSlot = slot;
                bestStack = stack;
            }
        }
        if (bestSlot >= 0) {
            return select(client, bestSlot, bestStack);
        }
        if (canBreakWithoutSilkTouch(client, pos, currentStack)) {
            return true;
        }
        // Direct warehouse placement leaves the previous tool in hand. Empty hands and
        // ordinary items can still break ice when every effective tool has Silk Touch.
        for (int slot = 0; slot < InventoryUtils.getMainStacks(inventory).size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (canBreakWithoutSilkTouch(client, pos, stack)
                    && (!stack.isEmpty() || Inventory.isHotbarSlot(slot))) {
                return select(client, slot, stack);
            }
        }
        return false;
    }

    private static boolean select(Minecraft client, int slot, ItemStack stack) {
        return Inventory.isHotbarSlot(slot) ? InventoryUtils.setPickedItemToHand(slot, stack, client)
                : RuntimeAccess.get().toolSwitchService().equipBreakToolSlot(slot);
    }

    private static boolean isEffectiveNonSilkTouchBreakItem(Minecraft client, BlockPos pos,
                                                          LocalPlayer player, BlockState iceState, ItemStack stack) {
        return canBreakWithoutSilkTouch(client, pos, stack)
                && !stack.isEmpty()
                && PlayerUtils.getDestroyProgress(player, iceState, stack) > PlayerUtils.getDestroyProgress(player, iceState, ItemStack.EMPTY);
    }

    private static boolean canBreakWithoutSilkTouch(Minecraft client, BlockPos pos, ItemStack stack) {
        if (hasSilkTouch(stack)) return false;
        var tools = RuntimeAccess.get().toolSwitchService();
        if (tools.isToolUsable(stack)) return true;
        // Continuing a paid hold requires less credit than starting another break.
        if (stack != client.player.getMainHandItem()
                || !(client.gameMode instanceof MiningInteractionPort port) || !port.isDestroying()
                || !pos.equals(port.destroyPos())) return false;
        return tools.canContinueReservedBreak(pos, Blocks.ICE.defaultBlockState(), port.destroyingItem());
    }

    private static boolean hasSilkTouch(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            Optional<ResourceKey<Enchantment>> enchantmentKey = enchantment.unwrapKey();
            if (enchantmentKey.isPresent() && enchantmentKey.get() == Enchantments.SILK_TOUCH) {
                return true;
            }
        }
        return false;
    }
}
