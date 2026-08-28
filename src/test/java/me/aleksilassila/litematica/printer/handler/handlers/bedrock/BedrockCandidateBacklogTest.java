package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockCandidateBacklogTest {
    @Test
    void boundedBacklogRetainsUnselectedCandidatesUntilSubmission() {
        BedrockCandidateBacklog<String> backlog = new BedrockCandidateBacklog<>(4);
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        BlockPos third = new BlockPos(2, 0, 0);

        backlog.offer(first, "first");
        backlog.offer(second, "second");
        backlog.offer(third, "third");

        assertEquals(3, backlog.snapshot().size());
        assertTrue(backlog.contains(second));

        backlog.remove(first);

        assertEquals(2, backlog.size());
        assertTrue(backlog.contains(second));
        assertTrue(backlog.contains(third));
    }

    @Test
    void candidatesAreRetainedUntilSubmissionAndExistingCandidateCanBeRefreshed() {
        BedrockCandidateBacklog<String> backlog = new BedrockCandidateBacklog<>(2);
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);

        assertTrue(backlog.offer(first, "old"));
        assertTrue(backlog.offer(second, "second"));
        assertFalse(backlog.offer(new BlockPos(2, 0, 0), "third"));
        assertFalse(backlog.offer(first, "refreshed"));

        assertEquals(0, backlog.remainingCapacity());
        assertEquals("refreshed", backlog.snapshot().get(0));
    }

    @Test
    void pruningRemovesOnlyInvalidEntries() {
        BedrockCandidateBacklog<String> backlog = new BedrockCandidateBacklog<>(4);
        backlog.offer(new BlockPos(0, 0, 0), "keep");
        backlog.offer(new BlockPos(1, 0, 0), "remove");

        backlog.removeIf((pos, value) -> value.equals("remove"));

        assertEquals(1, backlog.size());
        assertEquals("keep", backlog.snapshot().get(0));
    }
}
