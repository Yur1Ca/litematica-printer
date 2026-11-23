package me.aleksilassila.litematica.printer.handler;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintConfirmationTrackerTest {
    private static final BlockPos POS = new BlockPos(1, 2, 3);

    private final PrintConfirmationTracker tracker = new PrintConfirmationTracker();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aRejectionOnlyReleasesTheExactPendingAction() {
        record Owner(long token) {}
        var first = new Owner(1);
        var second = new Owner(1);
        var ice = Blocks.ICE.defaultBlockState();
        this.tracker.track(POS, ice, 2L, 10, first);
        this.tracker.track(POS, ice, 2L, 10, second);
        this.tracker.discard(POS, first);
        assertTrue(this.tracker.isPending(POS));
        this.tracker.discard(POS, second);
        assertFalse(this.tracker.isPending(POS));
        assertEquals(PrintConfirmationTracker.Resolution.NOT_TRACKED, this.tracker.resolve(POS, ice));

        this.tracker.track(POS, ice, 4L, 10);
        this.tracker.discard(POS, second);
        this.tracker.discard(POS, null);
        assertTrue(this.tracker.isPending(POS));
    }

    @Test
    void localObservationCannotResolvePendingPlacement() {
        this.tracker.track(POS, Blocks.STONE.defaultBlockState(), 10L, 80);

        assertTrue(this.tracker.expire(20L, 8).isEmpty());
        assertTrue(this.tracker.isPending(POS));
    }

    @Test
    void authoritativeMismatchResolvesAsMismatch() {
        this.tracker.track(POS, Blocks.STONE.defaultBlockState(), 10L, 80);

        assertEquals(
                PrintConfirmationTracker.Resolution.MISMATCHED,
                this.tracker.resolve(POS, Blocks.DIRT.defaultBlockState())
        );
        assertFalse(this.tracker.isPending(POS));
    }

    @Test
    void timeoutIsReportedOnceAfterDeadline() {
        this.tracker.track(POS, Blocks.STONE.defaultBlockState(), 10L, 80);

        assertTrue(this.tracker.expire(90L, 8).isEmpty());
        assertEquals(List.of(POS), this.tracker.expire(91L, 8));
        assertTrue(this.tracker.expire(92L, 8).isEmpty());
    }

    @Test
    void duplicateAuthoritativeDeliveryDoesNotResolveTwice() {
        this.tracker.track(POS, Blocks.STONE.defaultBlockState(), 10L, 80);

        assertEquals(
                PrintConfirmationTracker.Resolution.MATCHED,
                this.tracker.resolve(POS, Blocks.STONE.defaultBlockState())
        );
        assertEquals(
                PrintConfirmationTracker.Resolution.NOT_TRACKED,
                this.tracker.resolve(POS, Blocks.STONE.defaultBlockState())
        );
    }
}
