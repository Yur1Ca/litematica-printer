package me.aleksilassila.litematica.printer.gui;

/** Coordinates are Minecraft GUI pixels, after the user's GUI scale is applied. */
record WorkspaceLayout(int sidebarWidth, int listX, int listY, int listWidth,
                       int listHeight, int footerY, int configWidth) {
    static WorkspaceLayout workspace(int width, int height) {
        int sidebar = width >= 480 && height >= 360 ? Math.min(112, Math.max(88, width / 5)) : 0;
        int x = sidebar == 0 ? 8 : sidebar + 16;
        int y = sidebar == 0 ? 94 : 78;
        int listWidth = Math.max(100, width - x - 8);
        int footer = height - 28;
        int controlWidth = Math.max(148, Math.min(180, (listWidth - 64) / 2));
        return new WorkspaceLayout(sidebar, x, y, listWidth, Math.max(24, footer - y - 4), footer, controlWidth);
    }
}
