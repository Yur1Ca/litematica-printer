package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Environment(EnvType.CLIENT)
@Mixin(Connection.class)
public class MixinConnection {
    // Capture only the Packet argument: callback/listener parameters differ across supported versions.
    @ModifyVariable(method = "sendPacket", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> printer$trackRecoveryInput(Packet<?> packet) {
        me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.inputSent(this, packet);
        return packet;
    }

    @ModifyVariable(method = "doSendPacket", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> printer$trackRecoveryFence(Packet<?> packet) {
        me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.sent(this, packet);
        return packet;
    }

    @Inject(method = "genericsFtw", at = @At("HEAD"), require = 0)
    private static void hookGenericsFtw(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (ConfigUtils.isEnable()) {
            RuntimeAccess.get().modules().recordInboundPacket();   // 仅用于连接静默/延迟检测
        }
    }

    @Inject(method = "disconnect*", at = {@At("HEAD")})
    public void disconnect(CallbackInfo ci) {
        RuntimeAccess.get().onConnectionDisconnected(this);
    }
}
