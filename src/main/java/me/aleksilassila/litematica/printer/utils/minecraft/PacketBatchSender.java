package me.aleksilassila.litematica.printer.utils.minecraft;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import net.minecraft.network.protocol.Packet;

import java.util.List;
import java.util.function.Consumer;

/** Retains Connection.send hooks while withholding its per-packet flushes for one short batch. */
public final class PacketBatchSender {
    private PacketBatchSender() { }

    public static boolean send(Channel channel, List<Packet<?>> packets, Consumer<Packet<?>> sender) {
        if (channel == null || !channel.isActive() || packets.isEmpty()) return false;
        List<Packet<?>> prepared = List.copyOf(packets);
        Runnable write = () -> {
            if (!channel.isActive()) return;
            var gate = new ChannelOutboundHandlerAdapter() {
                @Override public void flush(ChannelHandlerContext context) { }
            };
            // Run the entire batch on the transport event loop. No timer flush can
            // interleave with this gate, and each packet still traverses vanilla send.
            channel.pipeline().addFirst(gate);
            try {
                for (Packet<?> packet : prepared) sender.accept(packet);
            } finally {
                channel.pipeline().remove(gate);
                channel.flush();
            }
        };
        if (channel.eventLoop().inEventLoop()) write.run();
        else channel.eventLoop().execute(write);
        return true;
    }
}
