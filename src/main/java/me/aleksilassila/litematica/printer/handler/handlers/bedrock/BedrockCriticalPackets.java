package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Builds every packet before the first destructive action can reach the connection. */
final class BedrockCriticalPackets {
    private BedrockCriticalPackets() { }

    static List<Packet<?>> prepareBody(ClientLevel level, LocalPlayer player, BlockPos pistonPos, Direction facing,
                                       Set<BlockPos> torches, BedrockPlacer.PreparedPistonPlacement placement) {
        var packets = new ArrayList<Packet<?>>();
        packets.add(new ServerboundSetCarriedItemPacket(InventoryUtils.getSelectedSlot(player.getInventory())));
        packets.add(NetworkUtils.createLookPacketIgnoringQueuedLook(player, placement.look()));
        for (BlockPos torch : torches) {
            if (!appendBreak(packets, level, player, torch, Direction.DOWN)) return List.of();
        }
        if (!appendBreak(packets, level, player, pistonPos, facing)) return List.of();
        var use = NetworkUtils.createPacket(sequence -> {
            return new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, placement.hit(), sequence);
        });
        if (use == null) return List.of();
        packets.add(use);
        return List.copyOf(packets);
    }

    private static boolean appendBreak(List<Packet<?>> packets, ClientLevel level, LocalPlayer player,
                                       BlockPos pos, Direction face) {
        float progress = PlayerUtils.getDestroyProgress(player, level.getBlockState(pos), player.getMainHandItem());
        if (!(progress >= 0.7F)) return false;
        var start = action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
        if (start == null) return false;
        packets.add(start);
        if (progress < 1.0F) {
            var stop = action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
            if (stop == null) return false;
            packets.add(stop);
        }
        return true;
    }

    private static Packet<?> action(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        return NetworkUtils.createPacket(sequence -> {
            return new ServerboundPlayerActionPacket(action, pos, face, sequence);
        });
    }
}
