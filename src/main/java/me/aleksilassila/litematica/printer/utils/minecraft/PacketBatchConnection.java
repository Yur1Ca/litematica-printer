package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.network.protocol.Packet;

import java.util.List;

/** A prepared critical sequence written in order before its single transport flush. */
public interface PacketBatchConnection {
    boolean litematica_printer$sendBatch(List<Packet<?>> packets);
}
