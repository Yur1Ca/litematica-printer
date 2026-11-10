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
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
            assertTrue(send.wasSent());
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
            }
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
            assertTrue(send.wasSent());
        }
    }

    @Test
    void anotherConnectionOrThreadCannotCompleteThisCall() {
        Object connection = new Object();
        var packet = use(InteractionHand.MAIN_HAND, hit());
        try (var send = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            NetworkUtils.observeUseItemOn(new Object(), packet);
            assertFalse(send.wasSent());
            CompletableFuture.runAsync(() -> NetworkUtils.observeUseItemOn(connection, packet)).join();
            assertFalse(send.wasSent(), "Transport work on another thread must not inherit the interaction scope");
            NetworkUtils.observeUseItemOn(connection, packet);
            assertTrue(send.wasSent());
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
                assertFalse(outer.wasSent(), "A nested interaction must not complete its caller's receipt");
            }
            NetworkUtils.observeUseItemOn(connection, packet);
            assertTrue(outer.wasSent());
        }
    }

    @Test
    void exceptionClosesInnerScopeAndRestoresOuterScope() {
        Object connection = new Object();
        try (var outer = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
                    assertFalse(inner.wasSent());
                    throw new IllegalStateException("interaction failed");
                }
            });
            NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
            assertTrue(outer.wasSent(), "The caller must recover its scope even when a nested interaction throws");
        }
    }

    @Test
    void closingScopeStopsObservationAndNextCallStartsWithoutAReceipt() {
        Object connection = new Object();
        var previous = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit());
        try (previous) {
            assertFalse(previous.wasSent());
        }
        NetworkUtils.observeUseItemOn(connection, use(InteractionHand.MAIN_HAND, hit()));
        assertFalse(previous.wasSent(), "A closed scope must not observe later sends");
        try (var next = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit())) {
            assertFalse(next.wasSent(), "A previous call's send must not be reused by the next interaction");
        }
    }

    private static BlockHitResult hit() {
        return new BlockHitResult(POINT, Direction.UP, POS, false);
    }

    private static ServerboundUseItemOnPacket use(InteractionHand hand, BlockHitResult hit) {
        //#if MC > 11802
        return new ServerboundUseItemOnPacket(hand, hit, 1);
        //#else
        //$$ return new ServerboundUseItemOnPacket(hand, hit);
        //#endif
    }
}
