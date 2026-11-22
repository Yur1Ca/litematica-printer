package me.aleksilassila.litematica.printer.integration.quickshulker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** An open-screen packet is only a candidate; initial contents must match the source box. */
final class QuickShulkerContainerSession {
    private List<ItemStack> expected;
    private boolean clickMode;
    private int candidateId = -1;
    private MenuType<?> candidateType;
    private int ownedId = -1;
    private MenuScope ownedMenu;
    private DeferredScreen deferred;

    private record MenuScope(Minecraft client, LocalPlayer player, ClientLevel level,
                             ClientPacketListener connection, AbstractContainerMenu menu) {
        MenuScope(Minecraft client) {
            this(client, client.player, client.level, client.getConnection(), client.player.containerMenu);
        }

        boolean current() {
            return client.player == player && client.level == level && client.getConnection() == connection
                    && player.containerMenu == menu;
        }
    }

    private record DeferredScreen(MenuScope scope, AbstractContainerScreen<?> screen) {
        void restore() {
            Minecraft client = scope.client();
            if (scope.current() && currentScreen(client) == null) {
                //#if MC > 260100
                //$$ client.gui.setScreen(screen);
                //#else
                client.setScreen(screen);
                //#endif
            }
        }
    }

    private static Screen currentScreen(Minecraft client) {
        //#if MC > 260100
        //$$ return client.gui.screen();
        //#else
        return client.screen;
        //#endif
    }

    void begin(ItemStack box, boolean clickMode) {
        reset();
        this.clickMode = clickMode;
        this.expected = new ArrayList<>();
        var stored = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(box, 27);
        for (int i = 0; i < 27; i++) {
            expected.add(i < stored.size() ? stored.get(i).copy() : ItemStack.EMPTY);
        }
    }

    void opened(int containerId, MenuType<?> type) {
        if (expected == null || ownedId >= 0) return;
        // Vanilla is replacing the menu. Its old screen can never be shown again.
        deferred = null;
        candidateType = type;
        candidateId = QuickShulkerSupport.acceptsMenu(type, clickMode) ? containerId : -1;
    }

    void screenChanging(Screen next) {
        if (deferred != null && deferred.screen() != next) {
            deferred = null;
            candidateId = -1;
        }
    }

    boolean defer(Minecraft client, AbstractContainerScreen<?> screen) {
        var menu = screen.getMenu();
        if (!isCandidate(menu.containerId) || ownedId >= 0 || client.getConnection() == null
                || client.player.containerMenu != menu || currentScreen(client) != null
                || menu.getType() != candidateType) return false;
        deferred = new DeferredScreen(new MenuScope(client), screen);
        return true;
    }

    boolean candidateStillCurrent() { return deferred == null || deferred.scope().current(); }
    void bindOwnedMenu(Minecraft client) { ownedMenu = new MenuScope(client); }
    boolean ownsCurrentMenu() { return ownedMenu != null && ownedMenu.current(); }

    boolean confirm(int containerId, List<ItemStack> items, ItemStack carried) {
        if (owns(containerId)) return true;
        if (expected == null || candidateId != containerId || items.size() < 63 || !carried.isEmpty()) return false;
        for (int slot = 0; slot < 27; slot++) {
            if (!ItemStack.matches(expected.get(slot), items.get(slot))) return false;
        }
        ownedId = containerId;
        deferred = null;
        return true;
    }

    boolean owns(int containerId) { return ownedId >= 0 && ownedId == containerId; }
    boolean isCandidate(int containerId) { return candidateId >= 0 && candidateId == containerId; }

    void reset() {
        DeferredScreen previous = deferred;
        discard();
        // Clear candidate ownership before re-entering the setScreen hook.
        if (previous != null) previous.restore();
    }

    void discard() {
        deferred = null;
        expected = null;
        candidateId = -1;
        candidateType = null;
        ownedId = -1;
        ownedMenu = null;
    }
}
