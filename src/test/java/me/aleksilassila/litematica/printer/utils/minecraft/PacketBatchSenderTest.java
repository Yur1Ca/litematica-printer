package me.aleksilassila.litematica.printer.utils.minecraft;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** A critical sequence must reach the transport as one flush, preserving ordinary send hooks. */
class PacketBatchSenderTest {
    static class Audit extends EmbeddedChannel {
        int flushes;
        @Override protected void doWrite(ChannelOutboundBuffer buffer) throws Exception {
            flushes++;
            super.doWrite(buffer);
        }
    }

    private static boolean send(Channel channel, List<Packet<?>> packets, Consumer<Packet<?>> sender) throws Exception {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(
                "me.aleksilassila.litematica.printer.utils.minecraft.PacketBatchSender"),
                "The transport needs an explicit critical-batch sender");
        return (boolean) type.getMethod("send", Channel.class, List.class, Consumer.class).invoke(null, channel, packets, sender);
    }

    @Test void ordinaryWriteAndFlushCallsAreHeldUntilTheWholeBatchHasBeenWritten() throws Exception {
        var channel = new Audit();
        try {
            Packet<?> first = new ServerboundSetCarriedItemPacket(1);
            Packet<?> second = new ServerboundSetCarriedItemPacket(2);
            var calls = new ArrayList<Packet<?>>();
            assertTrue(send(channel, List.of(first, second), packet -> {
                calls.add(packet);
                channel.writeAndFlush(packet);
                assertEquals(0, channel.flushes, "An intermediate packet must not escape on its own flush");
            }));
            channel.runPendingTasks();
            assertEquals(List.of(first, second), calls, "Keep each normal connection-send callback");
            assertEquals(1, channel.flushes);
            assertSame(first, channel.readOutbound());
            assertSame(second, channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void finishingABatchRestoresNormalFlushes() throws Exception {
        var channel = new Audit();
        try {
            Packet<?> packet = new ServerboundSetCarriedItemPacket(1);
            assertTrue(send(channel, List.of(packet), channel::writeAndFlush));
            channel.runPendingTasks();
            channel.writeAndFlush(packet);
            assertEquals(2, channel.flushes, "The temporary gate must not retain unrelated later traffic");
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void aClosedConnectionDoesNotBeginAnyOfThePreparedActions() throws Exception {
        var channel = new EmbeddedChannel();
        channel.close();
        var calls = new ArrayList<Packet<?>>();
        assertFalse(send(channel, List.of(new ServerboundSetCarriedItemPacket(1)), calls::add));
        channel.runPendingTasks();
        assertTrue(calls.isEmpty());
        channel.finishAndReleaseAll();
    }
}
