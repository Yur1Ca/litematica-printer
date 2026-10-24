package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class DirtyScanBurstTest {
    private static final PrinterBox BOX = new PrinterBox(-16, -16, -16, 16, 16, 16);
    private static final Comparator<BlockPos> ORDER = Comparator
            .comparingLong((BlockPos p) -> (long) p.getX() * p.getX()
                    + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ())
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);
    private static final WorldObservationPort OBSERVATION = new WorldObservationPort() {
        public boolean hasChunk(int x, int z) { return true; }
        public byte classify(ScanIntent intent, BlockPos pos, boolean extra) { return ScanFlags.SCHEMATIC_NON_AIR; }
        public BlockState worldState(BlockPos pos) { throw new AssertionError(); }
        public BlockState schematicState(BlockPos pos) { throw new AssertionError(); }
        public boolean hasFillSupport(BlockPos pos) { throw new AssertionError(); }
    };
    private boolean previousExtra;

    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    @BeforeEach void disableExtra() {
        this.previousExtra = Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue();
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
    }
    @AfterEach void restoreExtra() { Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(this.previousExtra); }

    @Test void repeatedBurstsKeepEveryAffectedPositionInTheOriginalOrder() throws Exception {
        var session = session();
        var random = new Random(782691);
        for (int burst = 0; burst < 40; burst++) {
            var expected = new TreeSet<>(ORDER);
            for (int i = 0; i < 256; i++) {
                var pos = new BlockPos(random.nextInt(35) - 17, random.nextInt(35) - 17, random.nextInt(35) - 17);
                session.invalidate(pos);
                session.invalidate(pos);
                if (BOX.contains(pos)) expected.add(pos);
                for (Direction direction : Direction.values()) {
                    var neighbor = pos.relative(direction);
                    if (BOX.contains(neighbor)) expected.add(neighbor);
                }
            }
            assertEquals(List.copyOf(expected), drain(session, burst + 1L), "burst=" + burst);
        }
        session.close();
        assertFalse(session.canScan(1000));
    }

    @Test void aConsumedPositionCanReenterWhileTheRemainingBurstIsPending() throws Exception {
        var session = session();
        var target = new BlockPos(-10, 0, 0);
        session.invalidate(target);
        session.invalidate(new BlockPos(10, 0, 0));
        var first = session.next(OBSERVATION, 1, () -> false, pos -> true, true).pos();
        assertEquals(new BlockPos(-9, 0, 0), first);
        session.invalidate(target);
        assertEquals(first, session.next(OBSERVATION, 1, () -> false, pos -> true, true).pos());
        var rest = drain(session, 1);
        assertEquals(13, rest.size());
        assertEquals(13, new java.util.HashSet<>(rest).size());
        assertFalse(rest.contains(first));
        assertEquals(rest.stream().sorted(ORDER).toList(), rest);
        assertTrue(drain(session, 2).isEmpty(), "An idle restart must not replay drained updates");
        session.invalidate(target);
        assertEquals(7, drain(session, 3).size());
    }

    @Test void drainingABurstReusesTheDedupeTableUntilResetOrClose() throws Exception {
        var session = session();
        int initialCapacity = dedupeTable(session).length;
        var points = new ArrayList<BlockPos>();
        for (int x = -12; x <= 12; x += 3) for (int y = -12; y <= 12; y += 3) {
            points.add(new BlockPos(x, y, 0));
        }
        points.forEach(session::invalidate);
        long[] burstTable = dedupeTable(session);
        assertTrue(burstTable.length > initialCapacity, "The fixture must grow beyond the initial table");
        // Observe the table itself: whole-thread allocation also includes unrelated iterators
        // whose allocation depends on JIT warmup, escape analysis and the CI runtime.
        for (int burst = 0; burst < 4; burst++) {
            assertNull(drainWithoutCandidates(session));
            assertSame(burstTable, dedupeTable(session), "Draining must not shrink the next burst's table");
            points.forEach(session::invalidate);
            assertSame(burstTable, dedupeTable(session), "The next burst must reuse its existing table");
        }
        assertNull(drainWithoutCandidates(session));
        assertTrue(session.canScan(2), "An idle pass restart must remain available");
        assertEquals(initialCapacity, dedupeTable(session).length, "Restart must release peak capacity");
        points.forEach(session::invalidate);
        assertTrue(dedupeTable(session).length > initialCapacity);
        session.close();
        assertEquals(initialCapacity, dedupeTable(session).length, "Close must release peak capacity");
        assertFalse(session.canScan(3));
    }

    private static long[] dedupeTable(SectionScanSession session) throws Exception {
        var keys = SectionScanSession.class.getDeclaredField("dirtyPositionKeys");
        keys.setAccessible(true);
        var table = it.unimi.dsi.fastutil.longs.LongOpenHashSet.class.getDeclaredField("key");
        table.setAccessible(true);
        return (long[]) table.get(keys.get(session));
    }

    private static SectionScanSession.Candidate drainWithoutCandidates(SectionScanSession session) {
        return session.next(OBSERVATION, 1, () -> false, pos -> false, true);
    }

    private static SectionScanSession session() throws Exception {
        return new SectionScanSession(ScanRegion.from(BOX, null), List.of(BOX), ScanIntent.PRINT,
                new ScanMetricsAccumulator(), RuntimeEpoch.INITIAL, () -> 0L, () -> 0L,
                SparseScanLifecycleTest.readyIndex(List.of()));
    }

    private static List<BlockPos> drain(SectionScanSession session, long tick) {
        var positions = new ArrayList<BlockPos>();
        assertNull(session.next(OBSERVATION, tick, () -> false, pos -> {
            positions.add(pos.immutable());
            return false;
        }, true));
        return positions;
    }
}
