package me.aleksilassila.litematica.printer.render;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.render.Render2DUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.util.List;

/**
 * Renders missing materials through the printer's own HUD entry point.
 * This keeps it independent from Litematica's optional InfoHud lifecycle.
 */
public final class MissingMaterialHudRenderer {
    public static final MissingMaterialHudRenderer INSTANCE = new MissingMaterialHudRenderer();

    private static final int MAX_DISPLAY_ITEMS = 10;
    private static final int PADDING = 3;
    private static final int TITLE_HEIGHT = 14;
    private static final int ITEM_ROW_HEIGHT = 18;
    private static final int OVERFLOW_HEIGHT = 12;
    private static final int ICON_TEXT_GAP = 20;
    private static final Color BACKGROUND = new Color(0, 0, 0, 110);
    private static final Color TITLE_COLOR = new Color(255, 120, 120, 255);
    private static final Color TEXT_COLOR = new Color(255, 255, 255, 255);
    private static final Color MUTED_TEXT_COLOR = new Color(160, 160, 160, 255);

    private MissingMaterialHudRenderer() {
    }

    public void render(
            float screenWidth,
            float screenHeight,
            int preferredX,
            int preferredY,
            float scale,
            int minimumWidth
    ) {
        List<MissingMaterialTracker.Entry> entries = MissingMaterialTracker.getRuntime().snapshot();
        if (entries.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int displayCount = Math.min(entries.size(), MAX_DISPLAY_ITEMS);
        boolean hasOverflow = entries.size() > displayCount;
        String title = I18n.MISSING_MATERIAL_TITLE.getName(entries.size()).getString();
        String overflow = hasOverflow
                ? I18n.MISSING_MATERIAL_OVERFLOW.getName(entries.size() - displayCount).getString()
                : "";

        int contentWidth = mc.font.width(title);
        for (int index = 0; index < displayCount; index++) {
            contentWidth = Math.max(contentWidth,
                    ICON_TEXT_GAP + mc.font.width(entries.get(index).displayName().getString()));
        }
        if (hasOverflow) {
            contentWidth = Math.max(contentWidth, mc.font.width(overflow));
        }

        int panelWidth = contentWidth + PADDING * 2;
        int panelHeight = PADDING * 2 + TITLE_HEIGHT + displayCount * ITEM_ROW_HEIGHT
                + (hasOverflow ? OVERFLOW_HEIGHT : 0);
        int scaledPanelWidth = Math.max(1, Math.round(panelWidth * scale));
        int scaledPanelHeight = Math.max(1, Math.round(panelHeight * scale));
        int renderedWidth = Math.max(minimumWidth, scaledPanelWidth);
        int drawX = clampToScreen(preferredX, renderedWidth, (int) screenWidth);
        int drawY = clampToScreen(preferredY, scaledPanelHeight, (int) screenHeight);

        Render2DUtils.fill(drawX, drawY, drawX + renderedWidth, drawY + scaledPanelHeight, BACKGROUND);
        Render2DUtils.drawStringScaled(
                title,
                drawX + Math.round(PADDING * scale),
                drawY + Math.round((PADDING + 1) * scale),
                TITLE_COLOR,
                true,
                scale
        );

        int rowY = PADDING + TITLE_HEIGHT;
        for (int index = 0; index < displayCount; index++) {
            MissingMaterialTracker.Entry entry = entries.get(index);
            drawItemScaled(
                    entry.iconStack(),
                    drawX + Math.round(PADDING * scale),
                    drawY + Math.round(rowY * scale),
                    scale
            );
            Render2DUtils.drawStringScaled(
                    entry.displayName().getString(),
                    drawX + Math.round((PADDING + ICON_TEXT_GAP) * scale),
                    drawY + Math.round((rowY + 4) * scale),
                    TEXT_COLOR,
                    true,
                    scale
            );
            rowY += ITEM_ROW_HEIGHT;
        }
        if (hasOverflow) {
            Render2DUtils.drawStringScaled(
                    overflow,
                    drawX + Math.round(PADDING * scale),
                    drawY + Math.round((rowY + 1) * scale),
                    MUTED_TEXT_COLOR,
                    true,
                    scale
            );
        }
    }

    private static void drawItemScaled(ItemStack stack, int x, int y, float scale) {
        if (Math.abs(scale - 1.0F) < 0.001F) {
            Render2DUtils.drawItem(stack, x, y);
            return;
        }
        Render2DUtils.pushPose();
        Render2DUtils.translate(x, y);
        Render2DUtils.scale(scale, scale);
        Render2DUtils.drawItem(stack, 0, 0);
        Render2DUtils.popPose();
    }

    private static int clampToScreen(int preferred, int size, int screenSize) {
        return Math.max(0, Math.min(preferred, Math.max(0, screenSize - size)));
    }
}
