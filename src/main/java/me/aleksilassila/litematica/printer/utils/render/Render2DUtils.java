package me.aleksilassila.litematica.printer.utils.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.awt.*;

/**
 * 封装 26.x 原版 GUI 渲染的底层工具类。
 */
public class Render2DUtils {
    public static final Minecraft client = Minecraft.getInstance();
    private static GuiGraphicsExtractor guiGraphics;

    // ==================== 初始化 ====================

    public static void initGuiGraphics(GuiGraphicsExtractor guiGraphics) {
        Render2DUtils.guiGraphics = guiGraphics;
    }

    public static void ensureInitialized() {
        if (guiGraphics == null) {
            throw new NullPointerException("GuiGraphics is null! Call initGuiGraphics first.");
        }
    }

    // ==================== 文字与矩形 ====================

    public static void drawString(String text, int x, int y, Color color, boolean withShadow) {
        drawString(text, x, y, color, withShadow, false);
    }

    public static void drawString(String text, int x, int y, Color color, boolean withShadow, boolean centered) {
        if (centered) {
            x -= client.font.width(text) / 2;
        }
        ensureInitialized();

        guiGraphics.text(client.font, text, x, y, color.getRGB(), withShadow);
    }

    public static void fill(int x1, int y1, int x2, int y2, Color color) {
        ensureInitialized();
        guiGraphics.fill(x1, y1, x2, y2, color.getRGB());
    }

    public static void drawStringScaled(String text, int x, int y, Color color, boolean withShadow, float scale) {
        ensureInitialized();
        if (Math.abs(scale - 1.0F) < 0.001F) {
            drawString(text, x, y, color, withShadow);
            return;
        }
        pushPose();
        translate(x, y);
        scale(scale, scale);
        drawString(text, 0, 0, color, withShadow);
        popPose();
    }

    public static void pushPose() {
        ensureInitialized();
        guiGraphics.pose().pushMatrix();
    }

    public static void popPose() {
        ensureInitialized();
        guiGraphics.pose().popMatrix();
    }

    public static void translate(double x, double y) {
        ensureInitialized();
        guiGraphics.pose().translate((float) x, (float) y);
    }

    public static void scale(float x, float y) {
        ensureInitialized();
        guiGraphics.pose().scale(x, y);
    }

    // ==================== 物品绘制 ====================

    /**
     * 绘制物品图标（不依赖玩家实体，纯图标渲染）
     */
    public static void drawItem(ItemStack stack, int x, int y) {
        ensureInitialized();
        guiGraphics.fakeItem(stack, x, y);
    }
}
