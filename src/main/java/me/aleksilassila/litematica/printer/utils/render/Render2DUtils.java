package me.aleksilassila.litematica.printer.utils.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.client.renderer.RenderPipelines;


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
        translate(x, y, 0.0D);
        scale(scale, scale, 1.0F);
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

    public static void translate(double x, double y, double z) {
        ensureInitialized();
        guiGraphics.pose().translate((float) x, (float) y);
    }

    public static void scale(float x, float y, float z) {
        ensureInitialized();
        guiGraphics.pose().scale(x, y);
    }

    // ==================== 纹理绘制 ====================

    /**
     * 绘制完整纹理（纹理文件整体宽高与绘制区域一致）
     */
    public static void drawTexture(Identifier texture, int x, int y, int width, int height) {
        drawTexture(texture, x, y, 0, 0, width, height, width, height);
    }

    /**
     * 绘制纹理的指定区域（支持 UV 裁剪）
     */
    public static void drawTexture(Identifier texture, int x, int y,
                                   int u, int v, int regionWidth, int regionHeight,
                                   int textureWidth, int textureHeight) {
        ensureInitialized();

        guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                x, y,
                (float) u, (float) v,
                regionWidth, regionHeight,
                regionWidth, regionHeight,
                textureWidth, textureHeight
        );
    }

    // ==================== 物品绘制 ====================

    /**
     * 绘制物品图标（不依赖玩家实体，纯图标渲染）
     */
    public static void drawItem(ItemStack stack, int x, int y) {
        ensureInitialized();
        guiGraphics.fakeItem(stack, x, y);
    }

    /**
     * 绘制物品图标及其装饰（数量、耐久条等）
     */
    public static void drawItemWithDecorations(ItemStack stack, int x, int y) {
        drawItem(stack, x, y);
        guiGraphics.itemDecorations(client.font, stack, x, y);
    }

    // ==================== 组合绘制 ====================

    /**
     * 物品图标 + 右侧文字
     */
    public static void drawItemWithLabel(ItemStack stack, int x, int y, String text, Color color, boolean shadow) {
        drawItem(stack, x, y);
        drawString(text, x + 20, y + 5, color, shadow);
    }

    /**
     * 纹理图标 + 右侧文字（可指定图标尺寸）
     */
    public static void drawIconWithLabel(Identifier texture, int x, int y,
                                         int iconWidth, int iconHeight,
                                         String text, Color color, boolean shadow) {
        drawTexture(texture, x, y, iconWidth, iconHeight);
        int textY = y + (iconHeight - client.font.lineHeight) / 2;
        drawString(text, x + iconWidth + 4, textY, color, shadow);
    }

    public static void drawBlock(Block block, int x, int y) {
        drawItem(block.asItem().getDefaultInstance(), x, y);
    }

    public static void drawBlockWithDecorations(Block block, int x, int y) {
        drawItemWithDecorations(block.asItem().getDefaultInstance(), x, y);
    }

    public static void drawBlockState(BlockState state, int x, int y) {
        drawBlock(state.getBlock(), x, y);
    }
}
