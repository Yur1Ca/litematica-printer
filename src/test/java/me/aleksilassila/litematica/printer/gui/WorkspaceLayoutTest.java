package me.aleksilassila.litematica.printer.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLayoutTest {
    @ParameterizedTest @CsvSource({"320,240", "399,240", "400,240", "427,240", "480,270", "550,350", "640,360", "960,540", "1920,1080"})
    void controlsAndListStayInsideTheScaledScreen(int width, int height) {
        WorkspaceLayout layout = WorkspaceLayout.workspace(width, height);
        assertTrue(layout.listX() >= 8);
        assertTrue(layout.listX() + layout.listWidth() <= width - 8);
        assertTrue(layout.listY() + layout.listHeight() <= layout.footerY() - 4);
        assertTrue(layout.configWidth() >= 100, "Boolean + hotkey controls must remain usable");
        assertTrue(layout.configWidth() + 52 + 24 < layout.listWidth());
        assertTrue(layout.sidebarWidth() == 0 || layout.sidebarWidth() >= 88);
        assertTrue(layout.footerY() + 20 <= height);
    }
}
