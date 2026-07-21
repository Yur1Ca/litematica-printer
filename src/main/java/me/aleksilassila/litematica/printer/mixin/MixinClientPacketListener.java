package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import net.minecraft.client.multiplayer.ClientPacketListener;
<<<<<<< HEAD
import net.minecraft.client.Minecraft;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
<<<<<<< HEAD
=======
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem.reSwitchItem;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(at = @At("TAIL"), method = "handleContainerContent")
    public void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
<<<<<<< HEAD
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || client.player.containerMenu == client.player.inventoryMenu
                ||
                //#if MC >= 12105
                packet.containerId()
                //#else
                //$$ packet.getContainerId()
                //#endif
                != client.player.containerMenu.containerId) {
            return;
        }
        if (isOpenHandler) {
            InventoryUtils.switchInv();
        }
        if (SwitchItem.isWaitingForRestoreContainer()) {
            SwitchItem.restorePendingItem();
=======
        if (isOpenHandler) {
            InventoryUtils.switchInv();
        }
        if (reSwitchItem != null) {
            SwitchItem.reSwitchItem();
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }
    }
}
