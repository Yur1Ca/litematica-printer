package me.aleksilassila.litematica.printer.handler.handlers.print;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortedSchematicTargetQueueTest {
    @Test
    void requeuedTargetIsDeferredToTheNextIterationPass() {
        SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(null);
        BlockPos missingTarget = new BlockPos(1, 2, 3);
        queue.requeue(missingTarget);

        var currentPass = queue.iterator();
        assertEquals(missingTarget, currentPass.next());
        queue.requeue(missingTarget);

        assertFalse(currentPass.hasNext());
        assertEquals(missingTarget, queue.iterator().next());
    }

    @Test
    void mixedFallingTargetsHaveATransitiveTotalOrder() {
        Random random = new Random(0x48_41_4E_41L);
        List<SortedSchematicTargetQueue.TargetScore> scores = new ArrayList<>(4096);
        for (int index = 0; index < 4096; index++) {
            BlockPos pos = new BlockPos(
                    random.nextInt(16 * 21),
                    random.nextInt(384) - 64,
                    random.nextInt(16 * 21)
            );
            scores.add(new SortedSchematicTargetQueue.TargetScore(
                    pos,
                    random.nextBoolean(),
                    random.nextBoolean(),
                    pos.getY(),
                    random.nextDouble() * 20000.0D,
                    random.nextDouble() * 2.0D - 1.0D
            ));
        }

        // This is the production failure mode: TimSort throws when the comparator contract is
        // violated by a sufficiently large mixed batch.
        scores.sort(SortedSchematicTargetQueue.TargetScore.COMPARATOR);
        for (int index = 1; index < scores.size(); index++) {
            assertTrue(SortedSchematicTargetQueue.TargetScore.COMPARATOR.compare(
                    scores.get(index - 1), scores.get(index)) <= 0);
        }

        // One million deterministic triples cover mixed falling/non-falling and material groups.
        for (int index = 0; index < 1_000_000; index++) {
            var left = scores.get(random.nextInt(scores.size()));
            var middle = scores.get(random.nextInt(scores.size()));
            var right = scores.get(random.nextInt(scores.size()));
            int leftMiddle = SortedSchematicTargetQueue.TargetScore.COMPARATOR.compare(left, middle);
            int middleRight = SortedSchematicTargetQueue.TargetScore.COMPARATOR.compare(middle, right);
            if (leftMiddle <= 0 && middleRight <= 0) {
                assertTrue(SortedSchematicTargetQueue.TargetScore.COMPARATOR.compare(left, right) <= 0);
            }
        }
    }

    @Test
    void fallingTargetsAreBottomUpBeforeHeldItemPreference() {
        var lowerSand = new SortedSchematicTargetQueue.TargetScore(
                new BlockPos(0, 10, 0), true, true, 10, 100.0D, 1.0D);
        var upperAnvil = new SortedSchematicTargetQueue.TargetScore(
                new BlockPos(0, 11, 0), false, true, 11, 1.0D, 0.0D);
        List<SortedSchematicTargetQueue.TargetScore> scores = new ArrayList<>(List.of(upperAnvil, lowerSand));

        scores.sort(SortedSchematicTargetQueue.TargetScore.COMPARATOR);

        assertEquals(lowerSand, scores.get(0));
        assertEquals(upperAnvil, scores.get(1));
    }
}
