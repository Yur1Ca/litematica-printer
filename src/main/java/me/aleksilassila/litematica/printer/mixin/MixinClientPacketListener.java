package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import me.aleksilassila.litematica.printer.utils.mods.ChestTrackerBridge;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerDiagnostics;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"), method = "handleOpenScreen")
    private void printer$trackQuickShulkerContainerOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.invalidate(
                ((ClientPacketListener) (Object) this).getConnection());
        QuickShulkerBridge.onContainerOpen(packet.getContainerId(), packet.getType());
        RuntimeAccess.get().containerSync().opened(packet.getContainerId(), packet.getType());
    }

    @Inject(
            method = "handleOpenScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"
            ),
            cancellable = true
    )
    private void suppressTaskAnvilScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (packet.getType() != MenuType.ANVIL
                || RuntimeAccess.get().actionBroker().consumeManualAnvilScreenAllowance()) {
            return;
        }
        if (RuntimeAccess.get().actionBroker().consumeTaskAnvilScreenSuppression()) {
            NetworkUtils.sendPacket(new ServerboundContainerClosePacket(packet.getContainerId()));
            ci.cancel();
        }
    }

    @Inject(at = @At("TAIL"), method = "handleOpenScreen")
    private void printer$trackContainerOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        ChestTrackerBridge.onContainerOpen(packet.getContainerId());
    }

    @Inject(at = @At("TAIL"), method = "handleContainerContent")
    public void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        int containerId = packet.containerId();
        var items = packet.items();
        var carried = packet.carriedItem();
        if ((Object) this != client.getConnection()) return;
        ChestTrackerBridge.onContainerContent(containerId, packet.stateId(), items, carried);
        boolean currentContainer = client.player != null && containerId == client.player.containerMenu.containerId;
        QuickShulkerBridge.onInventoryContent(containerId, items, carried);
        if (!currentContainer) return;
        RuntimeAccess.get().containerSync().content(containerId, packet.stateId(), items, carried);
    }
    @Inject(at = @At("TAIL"), method = "handleContainerSetSlot")
    private void printer$confirmQuickShulkerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        QuickShulkerBridge.onSlotUpdate(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(at = @At("TAIL"), method = "handleContainerSetData")
    private void printer$trackContainerSyncData(net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        RuntimeAccess.get().containerSync().data(packet.getContainerId(), packet.getId());
    }

    @Inject(at = @At("TAIL"), method = "handleContainerClose")
    private void printer$invalidateRecoveryMenu(net.minecraft.network.protocol.game.ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.invalidate(
                ((ClientPacketListener) (Object) this).getConnection());
    }

    @Inject(at = @At("TAIL"), method = "handleAwardStats")
    private void printer$confirmRecoveryFence(net.minecraft.network.protocol.game.ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        var connection = ((ClientPacketListener) (Object) this).getConnection();
        me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.received(
                connection);
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime != null) runtime.events().publish(
                new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.InventorySynchronized(connection));
    }
    @Inject(at = @At("TAIL"), method = "handleSetCursorItem")
    private void printer$trackCursor(ClientboundSetCursorItemPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        QuickShulkerBridge.onSlotUpdate(-1, -1, packet.contents());
    }
    @Inject(at = @At("TAIL"), method = "handleBlockUpdate")
    private void printer$logBlockReply(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        QuickShulkerDiagnostics.blockReply(packet.getPos(), packet.getBlockState());
    }

    @Inject(at = @At("TAIL"), method = "handleChunkBlocksUpdate")
    private void printer$logSectionReply(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (QuickShulkerDiagnostics.enabled()) packet.runUpdates(QuickShulkerDiagnostics::blockReply);
    }
}
