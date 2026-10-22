package me.aleksilassila.litematica.printer.handler.scan;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SparseScanLifecycleTest {
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

    @Test void pausedPassKeepsItsPrefixAndActiveCenterUntilTheNextPass() throws Exception {
        var input = new ArrayList<BlockPos>();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) input.add(new BlockPos(x, 0, z));
        var boxes = List.of(new PrinterBox(-5, 0, -5, 5, 0, 5));
        var original = region(0, 0, 0, 8);
        var moved = region(1, 0, 0, 8);
        var metrics = new ScanMetricsAccumulator();
        var session = session(readyIndex(input), ScanIntent.PRINT, original, boxes, metrics);
        var observation = new Observation();
        observation.flags = 0;
        assertNull(session.next(observation, 10, () -> true, pos -> true, false));
        assertTrue(session.wasPaused());
        assertEquals(8, metrics.scannedBlocks);
        session.updateRegion(moved, boxes);
        observation.flags = ScanFlags.SCHEMATIC_NON_AIR;
        var expected = reference(input, boxes, original);
        assertEquals(expected.subList(8, expected.size()), drain(session, observation, 10));
        assertEquals(reference(input, boxes, moved), drain(session, observation, 11));
    }

    @Test void completedPassRechecksEligibilityWithoutAnyBlockUpdate() throws Exception {
        var boxes = List.of(new PrinterBox(BlockPos.ZERO));
        var session = session(readyIndex(List.of(BlockPos.ZERO)), ScanIntent.PRINT,
                region(0, 0, 0, 1), boxes, new ScanMetricsAccumulator());
        var observation = new Observation();
        assertNull(session.next(observation, 20, () -> false, pos -> false, true));
        assertFalse(session.canScan(20));
        assertEquals(List.of(BlockPos.ZERO), drain(session, observation, 21));
        session.close();
        session.invalidate(BlockPos.ZERO);
        assertNull(session.next(observation, 1000, () -> false, pos -> true, true));
        assertFalse(session.belongsTo(RuntimeEpoch.INITIAL));
    }

    @Test void dirtyPositionWinsAnEqualDistanceBeforeAnExistingSparseCandidate() throws Exception {
        var boxes = List.of(new PrinterBox(-2, 0, -2, 2, 0, 2));
        var session = session(readyIndex(List.of(new BlockPos(-1, 0, 0))), ScanIntent.PRINT,
                region(0, 0, 0, 2), boxes, new ScanMetricsAccumulator());
        session.invalidate(new BlockPos(1, 0, 0));
        var observation = new Observation();
        // Invalidation also includes support neighbors. The center precedes both distance-one targets.
        assertEquals(BlockPos.ZERO, session.next(observation, 1, () -> false, pos -> true, true).pos());
        assertEquals(new BlockPos(1, 0, 0), session.next(observation, 1, () -> false, pos -> true, true).pos());
        assertEquals(new BlockPos(-1, 0, 0), session.next(observation, 1, () -> false, pos -> true, true).pos());
    }

    @Test void nonPrintExtraBlockAndWaitingPathsStillInspectOutsideTheSparseIndex() throws Exception {
        var index = readyIndex(List.of(BlockPos.ZERO));
        var boxes = List.of(new PrinterBox(-1, -1, -1, 1, 1, 1));
        for (ScanIntent intent : ScanIntent.values()) {
            var session = session(index, intent, region(0, 0, 0, 2), boxes, new ScanMetricsAccumulator());
            assertEquals(intent == ScanIntent.PRINT ? 1 : 27, drain(session, new Observation(), 1).size());
        }
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(true);
        assertEquals(27, drain(session(index, ScanIntent.PRINT, region(0, 0, 0, 2), boxes,
                new ScanMetricsAccumulator()), new Observation(), 1).size());
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        index.clear();
        assertFalse(index.isReady());
        assertEquals(27, drain(session(index, ScanIntent.PRINT, region(0, 0, 0, 2), boxes,
                new ScanMetricsAccumulator()), new Observation(), 1).size());
    }

    @Test void ownerDisableChunkReloadAndConnectionCloseCannotReviveOldSparseWork() throws Exception {
        var index = readyIndex(List.of(BlockPos.ZERO, new BlockPos(64, 0, 64)));
        var store = new ScanSessionStore(index);
        var near = new PrinterBox(-1, -1, -1, 1, 1, 1);
        var far = new PrinterBox(63, -1, 63, 65, 1, 65);
        var print = stored(store, "print", ScanIntent.PRINT, near);
        var fill = stored(store, "fill", ScanIntent.FILL, far);
        store.resetOwner("print");
        assertFalse(print.canScan(10));
        assertTrue(fill.canScan(10));
        var reenabled = stored(store, "print", ScanIntent.PRINT, near);
        store.invalidateChunk(0, 0);
        assertFalse(reenabled.canScan(10));
        assertTrue(fill.canScan(10));
        index.clear();
        assertEquals(0, index.forEachInRange(List.of(near, far), region(0, 0, 0, 100), p -> fail()));
        var reloaded = stored(store, "print", ScanIntent.PRINT, near);
        store.close();
        store.invalidate(BlockPos.ZERO);
        assertFalse(reloaded.canScan(1000));
        assertFalse(fill.canScan(1000));
        assertFalse(reloaded.belongsTo(RuntimeEpoch.INITIAL.next()));
    }

    private static SectionScanSession stored(ScanSessionStore store, String owner, ScanIntent intent, PrinterBox box) {
        return store.getOrCreate(owner, store.metrics(owner), intent, box, List.of(box), null,
                RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
    }

    static SchematicBlockIndex readyIndex(List<BlockPos> positions) throws Exception {
        var index = new SchematicBlockIndex();
        Field storage = SchematicBlockIndex.class.getDeclaredField("nonAirPositions");
        storage.setAccessible(true);
        for (BlockPos pos : positions) ((SectionPositionIndex) storage.get(index)).add(pos.asLong());
        Field state = SchematicBlockIndex.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(index, SchematicBlockIndex.State.READY);
        return index;
    }

    private static SectionScanSession session(SchematicBlockIndex index, ScanIntent intent, ScanRegion region,
                                              List<PrinterBox> boxes, ScanMetricsAccumulator metrics) {
        return new SectionScanSession(region, boxes, intent, metrics, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L, index);
    }

    private static List<BlockPos> drain(SectionScanSession session, Observation observation, long tick) {
        var result = new ArrayList<BlockPos>();
        SectionScanSession.Candidate candidate;
        while ((candidate = session.next(observation, tick, () -> false, pos -> true, true)) != null) {
            result.add(candidate.pos());
            assertTrue(result.size() < 1000, "pass failed to finish");
        }
        return result;
    }

    private static final class Observation implements WorldObservationPort {
        byte flags = ScanFlags.SCHEMATIC_NON_AIR;
        public boolean hasChunk(int x, int z) { return true; }
        public byte classify(ScanIntent intent, BlockPos pos, boolean extra) { return this.flags; }
        public BlockState worldState(BlockPos pos) { throw new AssertionError(); }
        public BlockState schematicState(BlockPos pos) { throw new AssertionError(); }
        public boolean hasFillSupport(BlockPos pos) { throw new AssertionError(); }
    }
}
