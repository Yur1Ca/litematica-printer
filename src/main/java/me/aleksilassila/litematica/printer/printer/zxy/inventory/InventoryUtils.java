package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import me.aleksilassila.litematica.printer.I18n;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.Reference;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashSet;

public class InventoryUtils {
    private static int shulkerCooldown = 0;
    private static int openHandlerTimeout = 0;
    private static final int OPEN_HANDLER_TIMEOUT_TICKS = 40;

    private static final Minecraft client = Minecraft.getInstance();

    public static boolean isInventory(Level world, BlockPos pos) {
        return fi.dy.masa.malilib.util.InventoryUtils.getInventory(world, pos) != null;
    }

    public static boolean canOpenInv(BlockPos pos) {
        if (client.level != null) {
            BlockState blockState = client.level.getBlockState(pos);
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            boolean isInventory = InventoryUtils.isInventory(client.level, pos);
            try {
                if ((isInventory && blockState.getMenuProvider(client.level, pos) == null) ||
                        (blockEntity instanceof ShulkerBoxBlockEntity entity &&
                                //#if MC > 260100
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, Vec3.atBottomCenterOf(pos)).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC > 12103
                                !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, pos.getBottomCenter()).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12103 && MC > 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(blockState.getValue(BlockStateProperties.FACING), 0.0f, 0.5f).move(pos).deflate(1.0E-6)) &&
                                //#endif
                                entity.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED)) {
                    return false;
                } else if (!isInventory) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    public static HashSet<Item> lastNeedItemList = new LinkedHashSet<>();
    public static boolean isOpenHandler = false;

    public static boolean switchItem() {
        if (!lastNeedItemList.isEmpty() && !isOpenHandler) {
            LocalPlayer player = client.player;
            if (player == null) {
                clearSwitchRequest();
                return false;
            }
            AbstractContainerMenu sc = player.containerMenu;
            if (!player.containerMenu.equals(player.inventoryMenu)) return true;
            //排除合成栏 装备栏 副手
            if (Configs.Placement.STORE_ORDERLY.getBooleanValue() && sc.slots.stream().skip(9).limit(sc.slots.size() - 10).noneMatch(slot -> slot.getItem().isEmpty())
                    && Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                SwitchItem.checkItems();
                return true;
            }

            if (Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                if (shulkerCooldown > 0) {
                    return true;
                }
                if (openShulker(lastNeedItemList)) {
                    return true;
                }
            }
            clearSwitchRequest();
        }
        return false;
    }

    public static boolean hasPendingSwitchRequest() {
        return isOpenHandler || !lastNeedItemList.isEmpty();
    }

    public static boolean shouldPauseForSwitchRequest() {
        return Configs.Placement.QUICK_SHULKER.getBooleanValue() && hasPendingSwitchRequest();
    }

    public static void resetRuntime() {
        clearSwitchRequest();
        shulkerCooldown = 0;
        ModLoadUtils.closeScreen = 0;
    }

<<<<<<< HEAD
    static int shulkerInventoryMenuSlot = -1;

    public static void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || client.gameMode == null) {
            clearSwitchRequest();
            return;
        }
=======
    static int shulkerBoxSlot = -1;

    public static void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) {
            return;
        }
        NonNullList<Slot> slots = sc.slots;
<<<<<<< HEAD
        if (slots.isEmpty()) {
            clearSwitchRequest();
            player.closeContainer();
            return;
        }
        for (Item item : lastNeedItemList) {
            int containerSize = Math.min(slots.size(), slots.get(0).container.getContainerSize());
            for (int y = 0; y < containerSize; y++) {
=======
        for (Item item : lastNeedItemList) {
            for (int y = 0; y < slots.get(0).container.getContainerSize(); y++) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                if (slots.get(y).getItem().getItem().equals(item)) {
                    String[] str = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue().split(",");
                    if (str.length == 0) return;
                    for (String s : str) {
                        if (s == null) break;
                        try {
                            int c = Integer.parseInt(s) - 1;
                            if (BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(c).getItem()).toString().contains("shulker_box") &&
                                    Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                                MessageUtils.setOverlayMessage(I18n.INVENTORY_SHULKER_OCCUPIED.getName(), false);
                                continue;
                            }
<<<<<<< HEAD
=======
                            SwitchItem.newItem(slots.get(y).getItem(), y, shulkerBoxSlot);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                            int a = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory()) == -1 ?
                                    InventoryUtilsAccessor.getPickBlockTargetSlot(player) :
                                    InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory());
                            c = a == -1 ? c : a;
<<<<<<< HEAD
                            ItemStack retrievedStack = slots.get(y).getItem().copy();
                            ItemStack sourceShulker = player.inventoryMenu.slots
                                    .get(shulkerInventoryMenuSlot).getItem().copy();
                            int movedPlayerSlot = ZxyUtils.switchPlayerInvToHotbarAir(c);
                            SwitchItem.moveTrackedItem(c, movedPlayerSlot);
                            fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
                            SwitchItem.newItem(
                                    retrievedStack,
                                    sourceShulker,
                                    y,
                                    shulkerInventoryMenuSlot,
                                    c
                            );
=======
                            ZxyUtils.switchPlayerInvToHotbarAir(c);
                            fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(player.getInventory(), c);
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.syncSelectedHotbarSlot();
                            me.aleksilassila.litematica.printer.utils.InventorySwitchGuard.markSwitchIfNeeded(item);
                            player.closeContainer();
<<<<<<< HEAD
                            clearSwitchRequest();
                            return;
                        } catch (Exception e) {
                            Reference.LOGGER.warn("Quick Shulker 物品切换失败", e);
=======
                            //刷新濳影盒
                            if (shulkerBoxSlot != -1) {
                                client.gameMode.handleContainerInput(sc.containerId, shulkerBoxSlot, 0, ContainerInput.PICKUP, client.player);
                                client.gameMode.handleContainerInput(sc.containerId, shulkerBoxSlot, 0, ContainerInput.PICKUP, client.player);
                            }
                            clearSwitchRequest();
                            return;
                        } catch (Exception e) {
                            System.out.println("Item switch error");
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                        }
                    }
                }
            }
        }
        clearSwitchRequest();
        AbstractContainerMenu sc2 = player.containerMenu;
        if (!sc2.equals(player.inventoryMenu)) {
            player.closeContainer();
        }
    }

    private static boolean openShulker(HashSet<Item> items) {
        if (shulkerCooldown > 0) {
            return false;
        }
        for (Item item : items) {
            AbstractContainerMenu sc = Minecraft.getInstance().player.inventoryMenu;
            for (int i = 9; i < sc.slots.size(); i++) {
                ItemStack stack = sc.slots.get(i).getItem();
                String itemid = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (itemid.contains("shulker_box") && stack.getCount() == 1) {
                    NonNullList<ItemStack> items1 = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1);
                    if (items1.stream().anyMatch(s1 -> s1.getItem().equals(item))) {
                        try {
<<<<<<< HEAD
                            shulkerInventoryMenuSlot = i;
                            if (!ShulkerUtils.openShulker(stack, shulkerInventoryMenuSlot)) {
                                shulkerInventoryMenuSlot = -1;
=======
                            shulkerBoxSlot = i;
                            if (!ShulkerUtils.openShulker(stack, shulkerBoxSlot)) {
                                shulkerBoxSlot = -1;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                                continue;
                            }
                            ModLoadUtils.closeScreen++;
                            isOpenHandler = true;
                            openHandlerTimeout = OPEN_HANDLER_TIMEOUT_TICKS;
                            shulkerCooldown = Configs.Placement.QUICK_SHULKER_COOLDOWN.getIntegerValue();
                            return true;
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }
        return false;
    }

    public static void tick() {
<<<<<<< HEAD
        SwitchItem.tick();
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (ModLoadUtils.closeScreen > 0) {
            ModLoadUtils.closeScreen--;
        }
        if (isOpenHandler && openHandlerTimeout > 0 && --openHandlerTimeout <= 0) {
            clearSwitchRequest();
        }
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
    }

    private static void clearSwitchRequest() {
<<<<<<< HEAD
        shulkerInventoryMenuSlot = -1;
=======
        shulkerBoxSlot = -1;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        lastNeedItemList = new LinkedHashSet<>();
        isOpenHandler = false;
        openHandlerTimeout = 0;
    }
}
