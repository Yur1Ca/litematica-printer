package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ModuleSelectionScopeTest {
    @Test
    void switchingRenderLayersInvalidatesTheCachedScanScope() {
        PrinterBox interactionBox = new PrinterBox(0, 0, 0, 15, 15, 15);
        PrinterBox firstLayer = new PrinterBox(0, 3, 0, 15, 3, 15);
        PrinterBox secondLayer = new PrinterBox(0, 4, 0, 15, 4, 15);

        assertNotEquals(
                ModuleSelectionScope.cacheKey(
                        interactionBox, SelectionType.LITEMATICA_RENDER_LAYER, firstLayer),
                ModuleSelectionScope.cacheKey(
                        interactionBox, SelectionType.LITEMATICA_RENDER_LAYER, secondLayer)
        );
    }
}
