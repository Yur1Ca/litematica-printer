package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.ClientBlockUpdateRouter;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.PredictiveAction;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements NetworkUtils.SequenceExtension {

    @Final
    @Shadow
    private net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler blockStatePredictionHandler;

    @Shadow @Final private ClientPacketListener connection;

    @Inject(method = "handleBlockChangedAck", at = @At("RETURN"))
    private void acknowledgeBlockAction(int sequence, CallbackInfo ci) {
        var client = Minecraft.getInstance();
        // A dimension change can leave an old, higher acknowledgement on the same connection.
        if (client.level != (Object) this || client.getConnection() != this.connection
                || sequence > this.blockStatePredictionHandler.currentSequence()) return;
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime != null) runtime.events().publish(new RuntimeEvent.BlockActionAcknowledged(
                this.connection.getConnection(), sequence));
    }

    @Override
    public Packet<ServerGamePacketListener> litematica_printer3$predict(PredictiveAction action) {
        try (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler pendingUpdateManager = this.blockStatePredictionHandler.startPredicting()) {
            return action.predict(pendingUpdateManager.currentSequence());
        }
    }

    @Inject(method = "syncBlockState", at = @At("RETURN"))
    private void observePredictionReconciliation(
            BlockPos pos,
            BlockState authoritativeState,
            Vec3 playerPosition,
            CallbackInfo ci
    ) {
        ClientBlockUpdateRouter.accept(pos, authoritativeState);
    }
}
