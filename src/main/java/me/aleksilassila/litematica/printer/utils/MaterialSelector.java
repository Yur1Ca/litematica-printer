package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Selects, reserves and requests material stacks without owning inventory transport state. */
public final class MaterialSelector {
    private static final int MAIN_INVENTORY_SLOT_COUNT = 36;
    private static final MaterialSelectionAccess execution = new ClientMaterialSelectionAccess();

    private MaterialSelector() {
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items, int reserveCount) {
        return switchToItems(player, items, reserveCount, MaterialRequest.Source.PRINT);
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items, int reserveCount, MaterialRequest.Source source) {
        if (player == null || execution.isWaitingForSwitch()) {
            return false;
        }
        if (items == null || items.length == 0) {
            items = new Item[]{Items.AIR};
        }
        Inventory inventory = player.getInventory();
        ItemStack mainHandStack = player.getMainHandItem();
        for (Item item : items) {
            if (mainHandStack.getItem().equals(item)
                    // 「虚空仓库」触发物不能当建材：右键它会被服务端接管成「开界面」，
                    // 方块永远放不下，于是每 tick 重试一次（按 Esc 也退不出这个循环）。
                    && !VoidWarehouseTriggerItem.isTrigger(mainHandStack)
                    && getConsumableSurplus(player, mainHandStack, null, reserveCount) > 0) {
                return true;
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (PlayerUtils.getAbilities(player).instabuild) {
            return execution.setPickedItemToHand(new ItemStack(items[0]), client);
        }
        for (Item item : items) {
            int surplus = -1;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack itemStack = inventory.getItem(slot);
                if (VoidWarehouseTriggerItem.isTrigger(itemStack)) {
                    // 改名成「虚空仓库」的工作台：留在背包里，不当作可放置的材料
                    continue;
                }
                if (itemStack.getItem().equals(item)) {
                    if (requiresReserve(player, itemStack, reserveCount)) {
                        if (surplus < 0) surplus = getConsumableSurplus(player, itemStack, null, reserveCount);
                        if (surplus <= 0) continue;
                    }
                    boolean needsInventoryConfirmation = !Inventory.isHotbarSlot(slot);
                    if (execution.setPickedItemToHand(slot, itemStack, client)) {
                        return !needsInventoryConfirmation
                                || !execution.markSwitchIfNeeded(item);
                    }
                    return false;
                }
            }
        }
        execution.requestItems(items, source);
        return false;
    }

    public static boolean switchToMatchingStack(
            LocalPlayer player,
            Predicate<ItemStack> predicate,
            ItemStack creativeFallback,
            int reserveCount
    ) {
        if (player == null || predicate == null || execution.isWaitingForSwitch()) {
            return false;
        }
        // 调用方给的 predicate 只描述「什么样的堆叠符合这个方块」，不管「能不能安全右键」。
        // 触发物（改名的仓库工作台）在这里统一否掉，免得每个 action 各写一份。
        Predicate<ItemStack> usable = stack -> VoidWarehouseTriggerItem.isUsableAsMaterial(stack)
                && predicate.test(stack);
        ItemStack mainHandStack = player.getMainHandItem();
        // Reuse only the reserve total within this decision. No inventory writes happen before
        // selection returns, and the next call must observe server updates even in the same tick.
        int surplus = -1;
        if (usable.test(mainHandStack)) {
            if (!requiresReserve(player, mainHandStack, reserveCount)) return true;
            surplus = getConsumableSurplus(player, mainHandStack, usable, reserveCount);
            if (surplus > 0) return true;
        }

        Minecraft client = Minecraft.getInstance();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !usable.test(stack)) {
                continue;
            }
            if (requiresReserve(player, stack, reserveCount)) {
                if (surplus < 0) surplus = getConsumableSurplus(player, stack, usable, reserveCount);
                if (surplus <= 0) continue;
            }
            boolean needsInventoryConfirmation = !Inventory.isHotbarSlot(slot);
            if (execution.setPickedItemToHand(slot, stack, client)) {
                return !needsInventoryConfirmation
                        || !execution.markSwitchIfNeeded(stack.getItem());
            }
            return false;
        }

        if (!PlayerUtils.getAbilities(player).instabuild
                && MaterialRequest.isEmptyShulker(creativeFallback)
                && usable.test(creativeFallback)) {
            execution.requestEmptyShulker(creativeFallback.getItem());
        }
        return PlayerUtils.getAbilities(player).instabuild
                && creativeFallback != null
                && usable.test(creativeFallback)
                && execution.setPickedItemToHand(creativeFallback.copy(), client);
    }

    public static int getConsumableSurplus(
            LocalPlayer player,
            ItemStack stack,
            @Nullable Predicate<ItemStack> requiredStackPredicate,
            int reserveCount
    ) {
        if (player == null || stack == null) {
            return 0;
        }
        if (!requiresReserve(player, stack, reserveCount)) {
            return Integer.MAX_VALUE;
        }
        Predicate<ItemStack> base = requiredStackPredicate != null
                ? requiredStackPredicate
                : candidate -> candidate.is(stack.getItem());
        // 计数同样要排除触发物：它是仓库入口，不是「还能再放几次」里的那一次
        Predicate<ItemStack> countable = candidate -> VoidWarehouseTriggerItem.isUsableAsMaterial(candidate)
                && base.test(candidate);
        return Math.max(0, countMatchingMainInventory(player, countable) - reserveCount);
    }

    public static ItemStack findReserveBlockedStack(
            LocalPlayer player,
            Item[] items,
            @Nullable Predicate<ItemStack> requiredStackPredicate,
            int reserveCount
    ) {
        if (player == null || reserveCount < 0 || PlayerUtils.getAbilities(player).instabuild) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        int size = Math.min(MAIN_INVENTORY_SLOT_COUNT, inventory.getContainerSize());
        if (requiredStackPredicate != null) {
            Predicate<ItemStack> usable = candidate -> VoidWarehouseTriggerItem.isUsableAsMaterial(candidate)
                    && requiredStackPredicate.test(candidate);
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && usable.test(stack)
                        && getConsumableSurplus(player, stack, usable, reserveCount) <= 0) {
                    return stack.copy();
                }
            }
            return ItemStack.EMPTY;
        }

        Item[] targetItems = items == null || items.length == 0 ? new Item[]{Items.AIR} : items;
        for (Item item : targetItems) {
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && VoidWarehouseTriggerItem.isUsableAsMaterial(stack)
                        && stack.is(item)
                        && getConsumableSurplus(player, stack, null, reserveCount) <= 0) {
                    return stack.copy();
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static int countMatchingMainInventory(LocalPlayer player, Predicate<ItemStack> predicate) {
        Inventory inventory = player.getInventory();
        int size = Math.min(MAIN_INVENTORY_SLOT_COUNT, inventory.getContainerSize());
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean requiresReserve(LocalPlayer player, ItemStack stack, int reserveCount) {
        return reserveCount >= 0 && !PlayerUtils.getAbilities(player).instabuild
                && !stack.isEmpty() && !stack.isDamageableItem();
    }
}
