package me.aleksilassila.litematica.printer.render;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightStyleType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockHighlightRendererTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private final boolean oldOperations = Configs.Highlight.ENABLED.getBooleanValue();
    private final boolean oldPreview = Configs.Highlight.NEXT_ENABLED.getBooleanValue();
    private final OperationHighlights trace = new OperationHighlights(256, () -> 0L);

    @AfterEach void restore() {
        Configs.Highlight.ENABLED.setBooleanValue(oldOperations);
        Configs.Highlight.NEXT_ENABLED.setBooleanValue(oldPreview);
    }

    @Test void previewAndOperationsCanBeDisplayedSeparately() {
        Configs.Highlight.NEXT_ENABLED.setBooleanValue(true);
        BlockPos next = BlockPos.ZERO.above();
        trace.record(BlockPos.ZERO, HighlightType.BREAK);
        trace.setPreviews(List.of(new OperationHighlights.Preview(next, true)));
        Configs.Highlight.ENABLED.setBooleanValue(false);
        var previewOnly = frame();
        assertEquals(1, previewOnly.size());
        assertEquals(next, previewOnly.getFirst().pos());
        assertEquals(HighlightStyleType.BOTH, previewOnly.getFirst().style());
        Configs.Highlight.NEXT_ENABLED.setBooleanValue(false);
        assertTrue(frame().isEmpty());
        Configs.Highlight.ENABLED.setBooleanValue(true);
        var operationsOnly = frame();
        assertEquals(1, operationsOnly.size());
        assertEquals(BlockPos.ZERO, operationsOnly.getFirst().pos());
        Configs.Highlight.NEXT_ENABLED.setBooleanValue(true);
        assertEquals(2, frame().size());
        var samePosition = new OperationHighlights.Preview(BlockPos.ZERO, true);
        trace.setPreviews(List.of(samePosition, samePosition));
        assertEquals(1, frame().size());
        assertTrue(frame().getFirst().preview());
    }

    private List<BlockHighlightRenderer.HighlightEntry> frame() {
        return BlockHighlightRenderer.frameEntries(trace, Vec3.ZERO, trace.now());
    }
}
