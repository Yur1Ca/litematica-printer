package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.*;

class SectionCoverageCursorTest {
    @Test void coversEverySectionBeyondThePrevious4096SectionLimit() {
        int sectionCount = 5001;
        var cursor = new SectionCoverageCursor(List.of(
                rawBox(0, 0, 0, sectionCount * 16 - 1, 0, 0)));
        List<BlockPos> actual = drain(cursor, 7);
        assertEquals(sectionCount, actual.size());
        assertEquals(sectionCount, new HashSet<>(actual).size());
        for (int x = 0; x < sectionCount; x++) assertEquals(new BlockPos(x, 0, 0), actual.get(x));
        assertEquals(SectionCoverageCursor.PollResult.COMPLETE, cursor.poll(new BlockPos.MutableBlockPos(), 100));
    }

    @Test void negativeCoordinatesAdvanceXThenZThenY() {
        var cursor = new SectionCoverageCursor(List.of(rawBox(-1, -1, -1, 0, 0, 0)));
        assertEquals(List.of(
                new BlockPos(-1, -1, -1), new BlockPos(0, -1, -1),
                new BlockPos(-1, -1, 0), new BlockPos(0, -1, 0),
                new BlockPos(-1, 0, -1), new BlockPos(0, 0, -1),
                new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0)), drain(cursor, 1));
    }

    @Test void overlappingBoxesBelongToTheEarliestCoarseSectionRange() {
        var cursor = new SectionCoverageCursor(List.of(
                rawBox(0, 0, 0, 31, 0, 15),
                rawBox(16, 0, 0, 47, 0, 15),
                rawBox(0, 0, 0, 47, 0, 31),
                rawBox(1, 1, 1, 1, 1, 1)));
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(2, 0, 1)), drain(cursor, 2));
    }

    @Test void duplicateChecksPauseAtTheExactBudgetAndResumeWithoutLosingTheUncoveredTail() {
        int overlap = 8192;
        var cursor = new SectionCoverageCursor(List.of(
                rawBox(0, 0, 0, overlap * 16 - 1, 0, 0),
                rawBox(0, 0, 0, overlap * 16 + 15, 0, 0)));
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        for (int x = 0; x < overlap; x++) {
            assertEquals(SectionCoverageCursor.PollResult.AVAILABLE, cursor.poll(target, 1));
            assertEquals(new BlockPos(x, 0, 0), target);
        }
        target.set(-123, -45, -67);
        for (int batch = 0; batch < overlap / 64; batch++) {
            assertEquals(SectionCoverageCursor.PollResult.PAUSED, cursor.poll(target, 64),
                    "A poll must not walk the entire duplicate prefix outside its check budget");
            assertEquals(new BlockPos(-123, -45, -67), target, "A paused poll must not replace the caller's target");
        }
        assertEquals(SectionCoverageCursor.PollResult.AVAILABLE, cursor.poll(target, 1));
        assertEquals(new BlockPos(overlap, 0, 0), target);
        assertEquals(SectionCoverageCursor.PollResult.COMPLETE, cursor.poll(target, 1));
    }

    @Test void clipsSourceBoundsBeforeConvertingToPackedWorldSections() {
        var cursor = new SectionCoverageCursor(List.of(
                rawBox(-40_000_000, -3000, -40_000_000, -33_554_432, -2048, -33_554_432),
                rawBox(33_554_431, 2047, 33_554_431, 40_000_000, 3000, 40_000_000),
                rawBox(-40_000_000, 0, 0, -35_000_000, 0, 0),
                rawBox(0, 2048, 0, 0, 4095, 0),
                rawBox(0, 0, 35_000_000, 0, 0, 40_000_000)));
        assertEquals(List.of(new BlockPos(-2_097_152, -128, -2_097_152),
                new BlockPos(2_097_151, 127, 2_097_151)), drain(cursor, 1));
    }

    @Test void hugeWorldScopeDoesNotMaterializeItsAxesOrVolume() {
        var cursor = new SectionCoverageCursor(List.of(rawBox(
                -33_554_432, -2048, -33_554_432, 33_554_431, 2047, 33_554_431)));
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        assertEquals(SectionCoverageCursor.PollResult.AVAILABLE, cursor.poll(target, 1));
        assertEquals(new BlockPos(-2_097_152, -128, -2_097_152), target);
        assertEquals(SectionCoverageCursor.PollResult.AVAILABLE, cursor.poll(target, 1));
        assertEquals(new BlockPos(-2_097_151, -128, -2_097_152), target);
    }

    @Test void resetExplicitlyRestartsACompleteOrPartialPassAndZeroBudgetDoesNotAdvance() {
        var cursor = new SectionCoverageCursor(List.of(rawBox(0, 0, 0, 16, 0, 0)));
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos(99, 99, 99);
        assertEquals(SectionCoverageCursor.PollResult.PAUSED, cursor.poll(target, 0));
        assertEquals(new BlockPos(99, 99, 99), target);
        assertEquals(SectionCoverageCursor.PollResult.AVAILABLE, cursor.poll(target, 1));
        assertEquals(new BlockPos(0, 0, 0), target);
        cursor.reset();
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)), drain(cursor, 1));
        assertEquals(SectionCoverageCursor.PollResult.COMPLETE, cursor.poll(target, 0));
        assertEquals(SectionCoverageCursor.PollResult.COMPLETE, cursor.poll(target, 100));
        cursor.reset();
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)), drain(cursor, 1));
    }

    @Test void emptyOrEntirelyUnrepresentableBoxesCompleteEvenWithNoCheckBudget() {
        List<List<PrinterBox>> scopes = List.of(List.of(),
                List.of(rawBox(40_000_000, 0, 0, 50_000_000, 0, 0)),
                List.of(rawBox(0, -4096, 0, 0, -2049, 0)));
        for (List<PrinterBox> scope : scopes) {
            var cursor = new SectionCoverageCursor(scope);
            assertEquals(SectionCoverageCursor.PollResult.COMPLETE, cursor.poll(new BlockPos.MutableBlockPos(), 0));
            cursor.reset();
            assertTrue(drain(cursor, 1).isEmpty());
        }
    }

    private static List<BlockPos> drain(SectionCoverageCursor cursor, int maxChecks) {
        List<BlockPos> actual = new ArrayList<>();
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        SectionCoverageCursor.PollResult result;
        while ((result = cursor.poll(target, maxChecks)) != SectionCoverageCursor.PollResult.COMPLETE) {
            if (result == SectionCoverageCursor.PollResult.AVAILABLE) actual.add(target.immutable());
        }
        return actual;
    }
}
