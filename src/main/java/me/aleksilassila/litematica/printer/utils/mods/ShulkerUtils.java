package me.aleksilassila.litematica.printer.utils.mods;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.QuickShulkerModeType;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.kyrptonaught.quickshulker.client.ClientUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerClicks;
import me.aleksilassila.litematica.printer.integration.quickshulker.ShulkerCursorRecovery;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;

@SuppressWarnings({"DataFlowIssue", "SpellCheckingInspection"})
public class ShulkerUtils {
    static final Minecraft client = Minecraft.getInstance();
    private static boolean apiFailureLogged;
    private static final ShulkerCursorRecovery recovery = new ShulkerCursorRecovery();
    private static net.minecraft.world.inventory.AbstractContainerMenu recoveryMenu;
    private static Object recoveryConnection;
    private static net.minecraft.world.inventory.AbstractContainerMenu requestedMenu;
    private static InventoryRecoveryFence.Ticket recoveryFence;

    public static boolean openShulker(ItemStack stack, int shulkerBoxSlot) {
        if (client.player == null || client.gameMode == null || recovery.active()
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()) {
            return false;
        }
        IConfigOptionListEntry openMode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        if (openMode == QuickShulkerModeType.CLICK_SLOT) {
            recovery.begin(shulkerBoxSlot, stack);
            recoveryMenu = client.player.inventoryMenu;
            recoveryConnection = client.getConnection();
            requestedMenu = null;
            recoveryFence = null;
            QuickShulkerClicks.send(client.player.containerMenu, shulkerBoxSlot, 1, ContainerInput.PICKUP);
            return true;
        } else if (openMode == QuickShulkerModeType.INVOKE) {
            if (ModLoadUtils.isQuickShulkerLoaded()) {
                try {
                    return ClientUtil.CheckAndSend(stack, shulkerBoxSlot);
                } catch (Exception ignored) {
                    return false;
                } catch (LinkageError error) {
                    if (!apiFailureLogged) {
                        apiFailureLogged = true;
                        Reference.LOGGER.warn("Quick Shulker API 不兼容，已停用调用模组路径", error);
                    }
                    return false;
                }
            } else {
                MessageUtils.addMessage(I18n.SHULKER_MOD_NOT_LOADED.getName());
            }
        }
        return false;
    }

    public static boolean isRecovering() { return recovery.active(); }

    public static void onSlotUpdate(int containerId, int slot, ItemStack stack) {
        recovery.observe(containerId, slot, stack);
    }

    /** Keep the attempt alive after timeout: a delayed server pickup still needs returning. */
    public static void recoverFailedOpen() {
        if (recovery.active()) return;
        recovery.fail();
    }

    public static void clearOpenAttempt() {
        recovery.reset();
        recoveryMenu = null;
        recoveryConnection = null;
        requestedMenu = null;
        recoveryFence = null;
    }

    public static void onRuntimeReset(String reason) {
        // Turning WORK off resets the runtime too, but its in-flight clicks still belong to this connection.
        if ("client_scope_changed".equals(reason)) clearOpenAttempt();
    }

    public static void tickRecovery() {
        if (!recovery.active()) return;
        if (client == null || client.player == null || client.getConnection() != recoveryConnection
                || client.player.inventoryMenu != recoveryMenu) { clearOpenAttempt(); return; }
        var menu = client.player.containerMenu;
        if (menu != recoveryMenu) {
            // A late or manually opened container does not cancel the earlier pickup. Do not
            // click that container; synchronize our inventory after the player returns to it.
            requestedMenu = null;
            recoveryFence = null;
            return;
        }
        if (requestedMenu != menu || recoveryFence == null || recoveryFence.invalidated()) {
            requestedMenu = menu;
            QuickShulkerClicks.requestFullSync(menu);
            requestRecoveryFence();
            return;
        }
        if (!recoveryFence.complete()) return;
        if (menu.getCarried().isEmpty()) {
            QuickShulkerDiagnostics.log("cursor-recovered slot=" + recovery.slot());
            clearOpenAttempt();
        } else if (recovery.shouldReturn(menu.getCarried(), menu.slots.get(recovery.slot()).getItem())) {
            QuickShulkerDiagnostics.log("cursor-recovery slot=" + recovery.slot());
            recovery.markReturnSent();
            QuickShulkerClicks.sendWithFullSync(menu, recovery.slot(), 0, ContainerInput.PICKUP);
            requestRecoveryFence();
        }
    }

    private static void requestRecoveryFence() {
        var connection = client.getConnection();
        recoveryFence = InventoryRecoveryFence.prepare(connection.getConnection());
        connection.send(recoveryFence.packet());
    }
}
