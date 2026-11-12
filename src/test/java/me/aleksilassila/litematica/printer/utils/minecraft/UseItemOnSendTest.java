package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseItemOnSendTest {
    private static final BlockPos POS = new BlockPos(1, 64, 0);
    private static final Vec3 POINT = new Vec3(1.25, 65.0, 0.75);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void matchesHitContentsRatherThanObjectIdentity() {
        Object connection = new Object();
        try (var send = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            assertFalse(send.wasSent());
            assertEquals(-1, send.sequence());
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit(), 37));
            assertTrue(send.wasSent());
            assertEquals(expectedSequence(37), send.sequence());
        }
    }

    @Test
    void ignoresSlotNoiseAndEveryMismatchedUseField() {
        Object connection = new Object();
        List<Packet<?>> unrelated = List.of(
                new ServerboundSetCarriedItemPacket(2),
                use(InteractionHand.OFF_HAND, hit()),
                use(InteractionHand.MAIN_HAND, new BlockHitResult(POINT, Direction.UP, POS.east(), false)),
                use(InteractionHand.MAIN_HAND, new BlockHitResult(POINT, Direction.DOWN, POS, false)),
                use(InteractionHand.MAIN_HAND, new BlockHitResult(POINT.add(0.125, 0.0, 0.0), Direction.UP, POS, false)),
                use(InteractionHand.MAIN_HAND, new BlockHitResult(POINT, Direction.UP, POS, true)));
        try (var send = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            for (Packet<?> packet : unrelated) {
                NetworkUtils.observeUseItemOn(connection, packet);
                assertFalse(send.wasSent(), "Only the matching hand, position, face, point and inside flag are a receipt");
                assertEquals(-1, send.sequence());
            }
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
            assertTrue(send.wasSent());
            assertEquals(expectedSequence(1), send.sequence());
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.OFF_HAND, hit(), 99));
            assertEquals(expectedSequence(1), send.sequence(), "A later unrelated USE cannot overwrite the matched sequence");
        }
    }

    @Test
    void anotherConnectionOrThreadCannotCompleteThisCall() {
        Object connection = new Object();
        var packet = use(InteractionHand.MAIN_HAND, hit());
        try (var send = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            NetworkUtils.observeUseItemOn(new Object(), packet);
            assertFalse(send.wasSent());
            assertEquals(-1, send.sequence());
            CompletableFuture.runAsync(() -> NetworkUtils.observeUseItemOn(connection, packet)).join();
            assertFalse(send.wasSent(), "Transport work on another thread must not inherit the interaction scope");
            assertEquals(-1, send.sequence());
            NetworkUtils.observeUseItemOn(connection, packet);
            assertTrue(send.wasSent());
            assertEquals(expectedSequence(1), send.sequence());
        }
    }

    @Test
    void innerScopeReceivesItsOwnUseAndClosingItRestoresOuterScope() {
        Object connection = new Object();
        var packet = use(InteractionHand.MAIN_HAND, hit());
        try (var outer = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            try (var inner = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
                NetworkUtils.observeUseItemOn(connection, packet);
                assertTrue(inner.wasSent());
                assertEquals(expectedSequence(1), inner.sequence());
                assertFalse(outer.wasSent(), "A nested interaction must not complete its caller's receipt");
                assertEquals(-1, outer.sequence());
            }
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit(), 9));
            assertTrue(outer.wasSent());
            assertEquals(expectedSequence(9), outer.sequence());
        }
    }

    @Test
    void exceptionClosesInnerScopeAndRestoresOuterScope() {
        Object connection = new Object();
        try (var outer = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
                    assertFalse(inner.wasSent());
                    assertEquals(-1, inner.sequence());
                    throw new IllegalStateException("interaction failed");
                }
            });
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
            assertTrue(outer.wasSent(), "The caller must recover its scope even when a nested interaction throws");
            assertEquals(expectedSequence(1), outer.sequence());
        }
    }

    @Test
    void closingScopeStopsObservationAndNextCallStartsWithoutAReceipt() {
        Object connection = new Object();
        var previous = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit());
        try (previous) {
            assertFalse(previous.wasSent());
            assertEquals(-1, previous.sequence());
        }
        NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
        assertFalse(previous.wasSent(), "A closed scope must not observe later sends");
        assertEquals(-1, previous.sequence());
        try (var next = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            assertFalse(next.wasSent(), "A previous call's send must not be reused by the next interaction");
            assertEquals(-1, next.sequence());
        }
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(POINT, Direction.UP, POS, false);
    }

    private static ServerboundUseItemOnPacket use(InteractionHand hand, BlockHitResult hit) {
        return use(hand, hit, 1);
    }

    private static ServerboundUseItemOnPacket use(InteractionHand hand, BlockHitResult hit, int sequence) {
        //#if MC > 11802
        return new ServerboundUseItemOnPacket(hand, hit, sequence);
        //#else
        //$$ return new ServerboundUseItemOnPacket(hand, hit);
        //#endif
    }

    private static int expectedSequence(int sequence) {
        //#if MC > 11802
        return sequence;
        //#else
        //$$ return -1;
        //#endif
    }
}
