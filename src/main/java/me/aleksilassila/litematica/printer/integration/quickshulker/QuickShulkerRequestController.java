package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaPickSlotAdapter;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerSupport;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
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
import java.util.Collection;

public final class QuickShulkerRequestController {

    private final Minecraft client;
    private final OrderedStorageController orderedStorage;
    private int shulkerCooldown = 0;
    private int openHandlerTimeout = 0;
    private static final int OPEN_HANDLER_TIMEOUT_TICKS = 40;

    private final HashSet<Item> lastNeedItemList = new LinkedHashSet<>();
    private boolean isOpenHandler;
    private boolean externalRequestAllowed;
    private int shulkerInventoryMenuSlot = -1;

    public QuickShulkerRequestController(Minecraft client) {
        this.client = client;
        this.orderedStorage = new OrderedStorageController(client, () -> this.lastNeedItemList);
    }

    OrderedStorageController orderedStorage() {
        return this.orderedStorage;
    }

    public static boolean isInventory(Level world, BlockPos pos) {
        return fi.dy.masa.malilib.util.InventoryUtils.getInventory(world, pos) != null;
    }

    public boolean canOpenInv(BlockPos pos) {
        if (client.level != null) {
            BlockState blockState = client.level.getBlockState(pos);
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            boolean isInventory = QuickShulkerRequestController.isInventory(client.level, pos);
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

    public void requestItem(Item item) {
        if (item != null) {
            lastNeedItemList.add(item);
        }
    }

    public void requestItems(Collection<Item> items) {
        if (items == null) return;
        for (Item item : items) {
            this.requestItem(item);
        }
    }

    public boolean isOpenHandler() {
        return this.isOpenHandler;
    }

    public boolean switchItem() {
        if (!this.lastNeedItemList.isEmpty() && !this.isOpenHandler) {
            LocalPlayer player = client.player;
            if (player == null) {
                clearSwitchRequest();
                return false;
            }
            AbstractContainerMenu sc = player.containerMenu;
            if (!sc.equals(player.inventoryMenu)) return true;
            if (Configs.Placement.STORE_ORDERLY.getBooleanValue()
                    && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                    && this.orderedStorage.tryRestoreForInventoryPressure()) {
                return true;
            }

            if (Configs.Placement.QUICK_SHULKER.getBooleanValue()) {
                if (shulkerCooldown > 0) {
                    return true;
                }
                if (this.openShulker(this.lastNeedItemList)) {
                    return true;
                }
            }
            clearSwitchRequest();
        }
        return false;
    }

    public boolean hasPendingSwitchRequest() {
        return this.isOpenHandler || !this.lastNeedItemList.isEmpty() || this.orderedStorage.hasPendingRestore();
    }

    public boolean shouldPauseForSwitchRequest() {
        return (Configs.Core.WORK_SWITCH.getBooleanValue() || this.externalRequestAllowed)
                && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                && hasPendingSwitchRequest();
    }

    public boolean shouldSuppressContainerScreen() {
        LocalPlayer player = client.player;
        return (Configs.Core.WORK_SWITCH.getBooleanValue() || this.externalRequestAllowed)
                && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                && player != null
                && !player.containerMenu.equals(player.inventoryMenu)
                && ModLoadUtils.closeScreen > 0
                && (this.isOpenHandler || this.orderedStorage.isWaitingForRestoreContainer());
    }

    public void setExternalRequestAllowed(boolean allowed) {
        this.externalRequestAllowed = allowed;
    }

    public void resetRuntime() {
        clearSwitchRequest();
        this.externalRequestAllowed = false;
        shulkerCooldown = 0;
        ModLoadUtils.closeScreen = 0;
    }

    public void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || client.gameMode == null) {
            clearSwitchRequest();
            return;
        }
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) {
            return;
        }
        NonNullList<Slot> slots = sc.slots;
        if (slots.isEmpty()) {
            clearSwitchRequest();
            player.closeContainer();
            return;
        }
        for (Item item : this.lastNeedItemList) {
            int containerSize = Math.min(slots.size(), slots.get(0).container.getContainerSize());
            for (int y = 0; y < containerSize; y++) {
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
                            int a = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory()) == -1 ?
                                    LitematicaPickSlotAdapter.selectNextAvailable(player) :
                                    InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(player.getInventory());
                            c = a == -1 ? c : a;
                            ItemStack retrievedStack = slots.get(y).getItem().copy();
                            ItemStack sourceShulker = player.inventoryMenu.slots
                                    .get(shulkerInventoryMenuSlot).getItem().copy();
                            int movedPlayerSlot = QuickShulkerSupport.switchPlayerInvToHotbarAir(c);
                            this.orderedStorage.moveTrackedItem(c, movedPlayerSlot);
                            fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
                            this.orderedStorage.newItem(
                                    retrievedStack,
                                    sourceShulker,
                                    y,
                                    shulkerInventoryMenuSlot,
                                    c
                            );
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(player.getInventory(), c);
                            me.aleksilassila.litematica.printer.utils.InventoryUtils.syncSelectedHotbarSlot();
                            RuntimeAccess.get().inventorySwitchGuard().markSwitchIfNeeded(item);
                            player.closeContainer();
                            clearSwitchRequest();
                            return;
                        } catch (Exception e) {
                            Reference.LOGGER.warn("Quick Shulker 物品切换失败", e);
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

    private boolean openShulker(HashSet<Item> items) {
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
                            shulkerInventoryMenuSlot = i;
                            if (!ShulkerUtils.openShulker(stack, shulkerInventoryMenuSlot)) {
                                shulkerInventoryMenuSlot = -1;
                                continue;
                            }
                            ModLoadUtils.closeScreen++;
                            this.isOpenHandler = true;
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

    public void tick() {
        this.orderedStorage.tick();
        if (ModLoadUtils.closeScreen > 0) {
            ModLoadUtils.closeScreen--;
        }
        if (this.isOpenHandler && this.openHandlerTimeout > 0 && --this.openHandlerTimeout <= 0) {
            clearSwitchRequest();
        }
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
        if (Configs.Placement.STORE_ORDERLY.getBooleanValue()
                && Configs.Placement.QUICK_SHULKER.getBooleanValue()
                && !this.externalRequestAllowed
                && !this.isOpenHandler
                && !RuntimeAccess.get().inventorySwitchGuard().isWaiting()
                && !TakeItOutUtils.isAwaitingStack()) {
            this.orderedStorage.maintainOrderlyStorage();
        }
    }

    private void clearSwitchRequest() {
        this.shulkerInventoryMenuSlot = -1;
        this.lastNeedItemList.clear();
        this.isOpenHandler = false;
        this.openHandlerTimeout = 0;
    }
}
