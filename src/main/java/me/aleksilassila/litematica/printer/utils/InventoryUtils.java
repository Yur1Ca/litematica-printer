package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.mixin.printer.litematica.EasyPlaceUtilsAccessor;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
=======
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.EasyPlaceUtilsAccessor;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
<<<<<<< HEAD
=======
import net.minecraft.network.chat.Component;
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
<<<<<<< HEAD
import java.util.function.Predicate;

//#if MC >= 12005
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
//#else
//$$ import net.minecraft.world.item.alchemy.PotionUtils;
//#endif
import net.minecraft.world.item.alchemy.Potions;
=======
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)

import static fi.dy.masa.malilib.util.InventoryUtils.*;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList;

@SuppressWarnings({"DataFlowIssue", "SpellCheckingInspection", "GrazieInspection"})
public class InventoryUtils {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int OFFHAND_SLOT_INDEX = 40;
    private static final long MESSAGE_COOLDOWN_MS = 5000L;
    private static final Map<String, Long> LAST_MESSAGE_SEND_TIME = new ConcurrentHashMap<>();
<<<<<<< HEAD
=======
    @Getter
    @Setter
    private static ItemStack orderlyStoreItem; //有序存放临时存储

>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
    public static int getSelectedSlot(Inventory inventory) {
        //#if MC > 12104
        return inventory.getSelectedSlot();
        //#else
        //$$ return inventory.selected;
        //#endif
    }

    public static void setSelectedSlot(Inventory inventory, int slot) {
        //#if MC > 12101
        inventory.setSelectedSlot(slot);
        //#else
        //$$ inventory.selected = slot;
        //#endif
    }

    public static NonNullList<ItemStack> getMainStacks(Inventory inventory) {
        //#if MC > 12104
        return inventory.getNonEquipmentItems();
        //#else
        //$$ return inventory.items;
        //#endif
    }

    public static boolean playerHasAccessToItem(LocalPlayer playerEntity, Item item) {
        return playerHasAccessToItems(playerEntity, item);
    }

<<<<<<< HEAD
    public static boolean playerHasItemInInventory(LocalPlayer playerEntity, Item item) {
        if (playerEntity == null || item == null) {
            return false;
        }
        if (PlayerUtils.getAbilities(playerEntity).instabuild) {
            return true;
        }
        Inventory inventory = playerEntity.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean playerHasAccessToItems(LocalPlayer playerEntity, Item... items) {
        if (items == null || items.length == 0) return true;
        if (playerEntity == null) return false;
        if (PlayerUtils.getAbilities(playerEntity).instabuild) return true;
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) return false;
        Inventory inventory = playerEntity.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Item inventoryItem = inventory.getItem(i).getItem();
            for (Item item : items) {
                if (inventoryItem == item) {
                    return true;
                }
            }
        }
        for (Item item : items) {
=======
    public static boolean playerHasAccessToItems(LocalPlayer playerEntity, Item... items) {
        if (items == null || items.length == 0) return true;
        if (PlayerUtils.getAbilities(playerEntity).mayBuild) return true;
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) return false;
        Inventory inventory = playerEntity.getInventory();
        for (Item item : items) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).getItem() == item) {
                    return true;
                }
            }
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.add(item);
        }
        return false;
    }

    public static boolean setPickedItemToHand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        int slotNum = mc.player.getInventory().findSlotMatchingItem(stack);
        return setPickedItemToHand(slotNum, stack, mc);
    }

    public static void setHotbarSlot(int slot, Inventory inventory) {
        setSelectedSlot(inventory, slot);
        syncSelectedHotbarSlot();
    }

    public static void syncSelectedHotbarSlot() {
        LocalPlayer player = client.player;
        ClientPacketListener connection = client.getConnection();
        if (player == null || connection == null) {
            return;
        }
        connection.send(new ServerboundSetCarriedItemPacket(getSelectedSlot(player.getInventory())));
    }

    /**
     * 检查是否有可用的 Pick 槽位
     *
     * @param sourceSlot 源槽位（-1表示自动寻找）
     * @param mc         Minecraft实例
     * @return PickResult 枚举结果
     */
    public static PickResult checkPickSlotAvailable(int sourceSlot, Minecraft mc) {
        // 基础校验失败 → 返回通用FAIL
        if (mc.player == null) return PickResult.FAIL;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 源槽位是快捷栏 → 成功
        if (Inventory.isHotbarSlot(sourceSlot)) return PickResult.SUCCESS;
        // 无配置可拾取槽位 → 精准失败类型
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return PickResult.FAIL_NO_PICK_SLOTS_CONFIGURED;
        }
        // 寻找可用槽位
        int hotbarSlot = sourceSlot;
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        // 无可用槽位 → 精准失败类型；否则成功
        return hotbarSlot != -1 ? PickResult.SUCCESS : PickResult.FAIL_NO_SUITABLE_SLOT_FOUND;
    }

    public static boolean setPickedItemToHand(int sourceSlot, ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 目标物品在热键栏中
        if (Inventory.isHotbarSlot(sourceSlot)) {
            setHotbarSlot(sourceSlot, inventory);
            return true;
        }
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_valid_slots_configured");
            return false;
        }
        int hotbarSlot = sourceSlot;
        // 尝试寻找一个空的可拾取方块的热键栏槽位
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        // 如果没有空槽位，则寻找一个可拾取方块的热键栏槽位
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        if (hotbarSlot != -1) {
            setHotbarSlot(hotbarSlot, inventory);
            if (EntityUtils.isCreativeMode(player)) {
                getMainStacks(inventory).set(hotbarSlot, stack.copy());
                client.gameMode.handleCreativeModeItemAdd(client.player.getMainHandItem(), 36 + hotbarSlot);
                return true;
            }
            EasyPlaceUtilsAccessor.callSetEasyPlaceLastPickBlockTime();
            return swapItemToMainHand(stack.copy(), mc);
        } else {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }
    }

    public static boolean swapItemToMainHand(ItemStack stackReference, Minecraft mc) {
        Player player = mc.player;
        if (player == null) return false;

        //#if MC > 12004
        boolean b = areStacksEqualIgnoreNbt(stackReference, player.getMainHandItem());
        //#else
        //$$ boolean b = areStacksEqual(stackReference, player.getMainHandItem());
        //#endif
        if (b) {
            return false;
        }

        int slot = findSlotWithItem(player.inventoryMenu, stackReference, true);
        if (slot != -1) {
            if (client.gameMode == null) {
                return false;
            }
            int currentHotbarSlot = getSelectedSlot(player.getInventory());
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, currentHotbarSlot, ContainerInput.SWAP, player);
            return true;
        }
        return false;
    }

    /**
     * 获取玩家副手的物品栈（全版本通用，极简实现）
     *
     * @param player 玩家实例
     * @return 副手物品栈
     */
    public static ItemStack getOffhandStack(Player player) {
        // 直接通过固定槽位40获取，无版本专属字段依赖
        return player.getInventory().getItem(OFFHAND_SLOT_INDEX);
    }

    /**
     * 将指定物品切换/设置到副手（核心方法，无选中格子逻辑）
     *
     * @param stack 要放到副手的物品栈
     * @param mc    Minecraft实例
     * @return 是否切换成功
     */
    public static boolean setItemToOffhand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;

        // 1. 检查副手已有该物品，直接返回成功（避免重复操作）
        boolean isAlreadyInOffhand = areStacksEqual(stack, getOffhandStack(player));
        if (isAlreadyInOffhand) {
            return true;
        }

        // 2. 创造模式：直接设置副手物品（无需交换）
        if (EntityUtils.isCreativeMode(player)) {
            player.getInventory().setItem(OFFHAND_SLOT_INDEX, stack.copy());
            client.gameMode.handleCreativeModeItemAdd(getOffhandStack(player), OFFHAND_SLOT_INDEX);
            return true;
        }

        // 3. 生存模式：找到物品所在槽位，交换到副手
        int sourceSlot = findSlotWithItem(player.inventoryMenu, stack, true);
        if (sourceSlot == -1) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }

        if (client.gameMode == null) {
            return false;
        }
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                sourceSlot,
                OFFHAND_SLOT_INDEX,
                ContainerInput.SWAP,
                player
        );

        return true;
    }


    // ========== 新增：副手核心方法（无选中格子逻辑） ==========

    private static void showMessageWithCooldown(Message.MessageType type, String messageKey) {
        long currentTime = System.currentTimeMillis();
        // 核心修改：通过消息Key获取最后发送时间，而非消息类型
        long lastSendTime = LAST_MESSAGE_SEND_TIME.getOrDefault(messageKey, 0L);

        // 未超过冷却时间，直接返回不发送
        if (currentTime - lastSendTime < MESSAGE_COOLDOWN_MS) {
            return;
        }

        // 超过冷却时间，发送消息并更新【该Key】的最后发送时间
        InfoUtils.showGuiOrInGameMessage(type, messageKey);
        LAST_MESSAGE_SEND_TIME.put(messageKey, currentTime);
    }

    public static boolean switchToBestTool(LocalPlayer player, BlockState blockState) {
        if (player == null || blockState == null || blockState.isAir()) {
            return false;
        }
        if (PlayerUtils.getAbilities(player).instabuild) {
            return false;
        }

        Inventory inventory = player.getInventory();
        ItemStack currentStack = player.getMainHandItem();
        float currentProgress = InteractionUtils.isToolAllowedByDurabilityProtection(currentStack)
                ? getDestroyProgress(player, blockState, currentStack)
                : 0.0F;
        float bestProgress = currentProgress;
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        NonNullList<ItemStack> stacks = getMainStacks(inventory);
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty() || !InteractionUtils.isToolAllowedByDurabilityProtection(stack)) {
                continue;
            }
            float progress = getDestroyProgress(player, blockState, stack);
            if (progress > bestProgress) {
                bestProgress = progress;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot == -1 || bestStack.isEmpty()) {
            return false;
        }
        return setPickedItemToHand(bestSlot, bestStack, client);
    }

    private static float getDestroyProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items) {
        if (InventorySwitchGuard.isWaiting()) {
            return false;
        }
        if (items == null || items.length == 0) {
            items = new Item[]{Items.AIR};
        }
        Inventory inventory = player.getInventory();
        ItemStack mainHandStack = player.getMainHandItem();
        for (Item item : items) {
            if (mainHandStack.getItem().equals(item)) {
<<<<<<< HEAD
=======
                orderlyStoreItem = mainHandStack;
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
                return true;
            }
        }
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        // 创造模式下主手不匹配时才执行 pick，避免高速放置时每个方块都重复同步物品。
        if (isCreativeMode) {
            ItemStack stack = new ItemStack(items[0]);
            if (InventoryUtils.setPickedItemToHand(stack, client)) {
                return true;
            }
            return false;
        }
<<<<<<< HEAD
        // 先检查全部可接受物品，避免首选物品缺失时提前发起取货，
        // 而背包里其实已经有后备物品（例如打火石/火焰弹）。
        for (Item item : items) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack itemStack = inventory.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    boolean needsInventoryConfirmation = !Inventory.isHotbarSlot(i);
                    if (InventoryUtils.setPickedItemToHand(i, itemStack, client)) {
                        return !needsInventoryConfirmation || !InventorySwitchGuard.markSwitchIfNeeded(item);
                    }
                    return false;
                }
            }
        }
        // 背包里所有后备物品都不存在后，再按优先级请求外部取货。
        for (Item item : items) {
            if (TakeItOutUtils.tryRequestItem(item)) {
                return false;
            }
        }
        for (Item item : items) {
=======
        // 找到背包中可用的物品
        for (Item item : items) {
            int slot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack itemStack = inventory.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    slot = i;
                    break;
                }
            }
            if (slot != -1) {
                ItemStack itemStack = inventory.getItem(slot);
                orderlyStoreItem = itemStack;
                boolean needsInventoryConfirmation = !Inventory.isHotbarSlot(slot);
                if (InventoryUtils.setPickedItemToHand(slot, itemStack, client)) {
                    return !needsInventoryConfirmation || !InventorySwitchGuard.markSwitchIfNeeded(item);
                }
                return false;
            }
            if (TakeItOutUtils.tryRequestItem(item)) {
                return false;
            }
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
            lastNeedItemList.add(item);
        }
        return false;
    }

<<<<<<< HEAD
    public static boolean playerHasAccessToMatchingStack(
            LocalPlayer playerEntity,
            ItemStack creativeFallback,
            Predicate<ItemStack> predicate
    ) {
        if (playerEntity == null || predicate == null) {
            return false;
        }
        if (PlayerUtils.getAbilities(playerEntity).instabuild) {
            return creativeFallback != null && predicate.test(creativeFallback);
        }
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) {
            return false;
        }
        Inventory inventory = playerEntity.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
=======
    public static void setOverlayMessageWithCooldown(Component message, String cooldownKey) {
        long currentTime = System.currentTimeMillis();
        long lastSendTime = LAST_MESSAGE_SEND_TIME.getOrDefault(cooldownKey, 0L);

        if (currentTime - lastSendTime < MESSAGE_COOLDOWN_MS) {
            return;
        }

        MessageUtils.setOverlayMessage(message);
        LAST_MESSAGE_SEND_TIME.put(cooldownKey, currentTime);
    }

    public static int getItemCount(LocalPlayer player, Item item) {
        if (player == null || item == null || item == Items.AIR) {
            return 0;
        }
        int count = 0;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem().equals(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static boolean hasEnoughItemsForReserve(LocalPlayer player, Item[] items, int reserveCount) {
        if (player == null || reserveCount <= 0 || items == null || items.length == 0) {
            return true;
        }
        if (PlayerUtils.getAbilities(player).instabuild) {
            return true;
        }
        for (Item item : items) {
            if (item == null || item == Items.AIR) {
                return true;
            }
            if (getItemCount(player, item) > reserveCount) {
                return true;
            }
        }
        return false;
    }

    public static boolean switchToItemsWithReserve(LocalPlayer player, Item[] items, int reserveCount) {
        if (reserveCount <= 0 || PlayerUtils.getAbilities(player).instabuild) {
            return switchToItems(player, items);
        }
        if (items == null || items.length == 0) {
            return switchToItems(player, items);
        }
        Item selectedItem = null;
        for (Item item : items) {
            if (item == null || item == Items.AIR) {
                selectedItem = item;
                break;
            }
            if (getItemCount(player, item) > reserveCount) {
                selectedItem = item;
                break;
            }
        }
        if (selectedItem != null) {
            return switchToItems(player, new Item[]{selectedItem});
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
        }
        return false;
    }

    public static boolean isHoldingAnyItem(LocalPlayer player, Item[] items) {
        if (items == null || items.length == 0) {
            return true;
        }
        Item heldItem = player.getMainHandItem().getItem();
        for (Item item : items) {
            if (heldItem.equals(item)) {
                return true;
            }
        }
        return false;
    }

<<<<<<< HEAD
    public static boolean switchToMatchingStack(
            LocalPlayer player,
            Predicate<ItemStack> predicate,
            ItemStack creativeFallback
    ) {
        if (player == null || predicate == null || InventorySwitchGuard.isWaiting()) {
            return false;
        }
        ItemStack mainHandStack = player.getMainHandItem();
        if (predicate.test(mainHandStack)) {
            return true;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !predicate.test(stack)) {
                continue;
            }
            boolean needsInventoryConfirmation = !Inventory.isHotbarSlot(slot);
            if (setPickedItemToHand(slot, stack, client)) {
                return !needsInventoryConfirmation
                        || !InventorySwitchGuard.markSwitchIfNeeded(stack.getItem());
            }
            return false;
        }

        if (PlayerUtils.getAbilities(player).instabuild
                && creativeFallback != null
                && predicate.test(creativeFallback)) {
            return setPickedItemToHand(creativeFallback.copy(), client);
        }
        return false;
    }

    public static ItemStack createWaterPotionStack() {
        //#if MC >= 12005
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
        //#else
        //$$ return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        //#endif
    }

    public static boolean isWaterPotion(ItemStack stack) {
        if (stack == null || !stack.is(Items.POTION)) {
            return false;
        }
        //#if MC >= 12005
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
        //#else
        //$$ return PotionUtils.getPotion(stack) == Potions.WATER;
        //#endif
    }

=======
>>>>>>> 766717f4 (feat: Add inventory reserve feature for block placement)
    /**
     * 检查是否能切换到目标物品（配合槽位检查，仅判断不执行切换）
     *
     * @param player 本地玩家实例
     * @param items  目标物品数组（null/空则视为AIR）
     * @return PickResult 检查结果
     */
    public PickResult checkCanSwitchToItems(LocalPlayer player, Item[] items) {
        if (player == null) {
            return PickResult.FAIL;
        }
        Item[] targetItems = items;
        if (targetItems == null || targetItems.length == 0) {
            targetItems = new Item[]{Items.AIR};
        }
        Inventory inv = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            return InventoryUtils.checkPickSlotAvailable(-1, client);
        }
        for (Item item : targetItems) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack itemStack = inv.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    return InventoryUtils.checkPickSlotAvailable(i, client);
                }
            }
        }
        return PickResult.FAIL;
    }

    public enum PickResult {
        SUCCESS,
        FAIL,
        FAIL_NO_PICK_SLOTS_CONFIGURED,
        FAIL_NO_SUITABLE_SLOT_FOUND;

        // 快捷判断：是否是「未配置可拾取槽位」
        public boolean isNoPickSlotsConfigured() {
            return this == FAIL_NO_PICK_SLOTS_CONFIGURED;
        }

        // 快捷判断：是否是「无可用槽位」
        public boolean isNoSuitableSlotFound() {
            return this == FAIL_NO_SUITABLE_SLOT_FOUND;
        }

        // 快捷方法：是否「无可用槽位」（包含两种精准失败类型）
        public boolean isNoAvailableSlot() {
            return isNoPickSlotsConfigured() || isNoSuitableSlotFound();
        }

        // 快捷方法：是否「有可用槽位」（仅SUCCESS表示有）
        public boolean isAvailable() {
            return this == SUCCESS;
        }
    }
}
