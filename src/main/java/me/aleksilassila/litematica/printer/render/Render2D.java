package me.aleksilassila.litematica.printer.render;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEngine;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.render.Render2DUtils;
import net.minecraft.client.Minecraft;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一的 2D 渲染管理器，负责 HUD 的绘制。
 * 由 MixinGui 在每帧调用 render() 方法触发。
 */
public class Render2D {
    public static final Render2D INSTANCE = new Render2D();

    private static final int HUD_PADDING = 6;
    private static final int HUD_LINE_HEIGHT = 12;

    private long cachedHudTick = Long.MIN_VALUE;
    private float cachedHudWidth = Float.NaN;
    private float cachedHudHeight = Float.NaN;
    private int cachedHudX = Integer.MIN_VALUE;
    private int cachedHudY = Integer.MIN_VALUE;
    private int cachedHudScale = Integer.MIN_VALUE;
    private HudLayouts cachedHudLayouts;

    private Render2D() {
    }

    /**
     * 主渲染入口，由 Mixin 每帧调用。
     * 注意：调用前必须已通过 Render2DUtils.initGuiGraphics 或 initMatrix 设置好渲染上下文。
     */
    public void render(float scaledWidth, float scaledHeight) {
        // 确保底层渲染工具已初始化
        Render2DUtils.ensureInitialized();

//        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
//        sword.setDamageValue(100);
//        sword.setCount(64);

//        int y = 50;
//        // 绘制物品图标 + 装饰
//        Render2DUtils.drawItemWithDecorations(sword, 100, y);
//        y += 24;
//        // 如果你只想绘制物品图标本身（不显示数量、耐久条）
//        Render2DUtils.drawItem(sword, 100, y);
//        y += 24;
//        // 绘制方块图标本身
//        Render2DUtils.drawBlock(Blocks.DIAMOND_BLOCK, 100, y);
//        y += 24;
//        // 绘制方块图标，并自动显示数量、耐久条等装饰
//        Render2DUtils.drawBlockWithDecorations(Blocks.CHEST, 100, y);
//        y += 24;
//        // 组合方法
//        Render2DUtils.drawItemWithLabel(sword, 100, y, sword.getItemName().getString(), Color.WHITE, true);

        if (Configs.Core.RENDER_HUD.getBooleanValue()) {
            drawHudInfo(scaledWidth, scaledHeight);
        }
        if (Configs.Core.MISSING_MATERIAL_HUD.getBooleanValue()) {
            int materialHudX = Configs.Core.RENDER_HUD_X.getIntegerValue();
            int materialHudY = Configs.Core.RENDER_HUD_Y.getIntegerValue();
            if (Configs.Core.RENDER_HUD.getBooleanValue()) {
                HudBounds bounds = this.getHudBounds(scaledWidth, scaledHeight);
                materialHudX = bounds.x();
                materialHudY = bounds.y() + bounds.height();
                MissingMaterialHudRenderer.INSTANCE.render(
                        scaledWidth,
                        scaledHeight,
                        materialHudX,
                        materialHudY,
                        getHudScale(),
                        bounds.width()
                );
            } else {
                MissingMaterialHudRenderer.INSTANCE.render(
                        scaledWidth,
                        scaledHeight,
                        materialHudX,
                        materialHudY,
                        getHudScale(),
                        0
                );
            }
        }
    }

    public void renderHudPreview(float scaledWidth, float scaledHeight) {
        Render2DUtils.ensureInitialized();
        drawHudInfo(scaledWidth, scaledHeight, true);
    }

    // ==================== HUD 进度条等信息绘制 ====================

    private void drawHudInfo(float scaledWidth, float scaledHeight) {
        this.drawHudInfo(scaledWidth, scaledHeight, false);
    }

    private void drawHudInfo(float scaledWidth, float scaledHeight, boolean forceRefresh) {
        int centerX = (int) (scaledWidth / 2);
        int centerY = (int) (scaledHeight / 2);

        // 延迟过大警告
        if (Configs.Core.LAG_CHECK.getBooleanValue() &&
                RuntimeAccess.get().modules().packetTick() > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            Render2DUtils.drawString("延迟过大，已暂停运行", centerX, centerY - 22, Color.ORANGE, true, true);
        }

        HudLayouts layouts = this.getHudLayouts(scaledWidth, scaledHeight, forceRefresh);
        drawHudPanel(layouts.summary());
        drawHudPanel(layouts.modes());
    }

    private int drawHudPanel(PanelLayout layout) {
        if (layout.lines().isEmpty()) {
            return layout.drawY();
        }

        //#if MC >= 12111
        int padding = Math.max(1, Math.round(HUD_PADDING * layout.scale()));
        int lineStep = Math.max(1, Math.round(HUD_LINE_HEIGHT * layout.scale()));
        int panelHeight = Math.max(1, Math.round(layout.baseHeight() * layout.scale()));
        Render2DUtils.fill(
                layout.drawX(),
                layout.drawY(),
                layout.drawX() + layout.scaledWidth(),
                layout.drawY() + panelHeight,
                new Color(0, 0, 0, 110)
        );

        int textX = layout.drawX() + padding;
        int lineY = layout.drawY() + padding;
        for (HudLine line : layout.lines()) {
            Render2DUtils.drawStringScaled(line.text(), textX, lineY, line.color(), true, layout.scale());
            lineY += lineStep;
        }
        return layout.drawY() + panelHeight;
        //#else
        //$$ Render2DUtils.pushPose();
        //$$ Render2DUtils.translate(layout.drawX(), layout.drawY(), 0.0D);
        //$$ Render2DUtils.scale(layout.scale(), layout.scale(), 1.0F);
        //$$ Render2DUtils.fill(0, 0, layout.baseWidth(), layout.baseHeight(), new Color(0, 0, 0, 110));
        //$$
        //$$ int lineY = HUD_PADDING;
        //$$ for (HudLine line : layout.lines()) {
        //$$     Render2DUtils.drawString(line.text(), HUD_PADDING, lineY, line.color(), true);
        //$$     lineY += HUD_LINE_HEIGHT;
        //$$ }
        //$$ Render2DUtils.popPose();
        //$$ return layout.bottom();
        //#endif
    }

    private PanelLayout computeHudPanelLayout(int x, int y, List<HudLine> lines, float scaledWidth, float scaledHeight, float scale) {
        if (lines.isEmpty()) {
            return new PanelLayout(lines, x, y, 0, 0, 0, 0, scale);
        }

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = 0;
        for (HudLine line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line.text()));
        }

        int baseWidth = maxWidth + HUD_PADDING * 2;
        int baseHeight = lines.size() * HUD_LINE_HEIGHT + HUD_PADDING * 2;
        //#if MC >= 12111
        int scaledWidthPixels = Math.max(1, Math.round(baseWidth * scale));
        int scaledHeightPixels = Math.max(1, Math.round(baseHeight * scale));
        //#else
        //$$ int scaledWidthPixels = Math.max(1, Math.round(baseWidth * scale));
        //$$ int scaledHeightPixels = Math.max(1, Math.round(baseHeight * scale));
        //#endif
        int drawX = Math.max(0, Math.min(x, (int) scaledWidth - scaledWidthPixels));
        int drawY = Math.max(0, Math.min(y, (int) scaledHeight - scaledHeightPixels));
        return new PanelLayout(lines, drawX, drawY, baseWidth, baseHeight, scaledWidthPixels, scaledHeightPixels, scale);
    }

    public HudBounds getHudBounds(float scaledWidth, float scaledHeight) {
        HudLayouts layouts = this.getHudLayouts(scaledWidth, scaledHeight, false);
        PanelLayout summaryLayout = layouts.summary();
        PanelLayout modeLayout = layouts.modes();

        if (summaryLayout.lines().isEmpty()) {
            return new HudBounds(modeLayout.drawX(), modeLayout.drawY(), modeLayout.scaledWidth(), modeLayout.scaledHeight());
        }
        if (modeLayout.lines().isEmpty()) {
            return new HudBounds(summaryLayout.drawX(), summaryLayout.drawY(), summaryLayout.scaledWidth(), summaryLayout.scaledHeight());
        }

        int minX = Math.min(summaryLayout.drawX(), modeLayout.drawX());
        int minY = Math.min(summaryLayout.drawY(), modeLayout.drawY());
        int maxX = Math.max(summaryLayout.right(), modeLayout.right());
        int maxY = Math.max(summaryLayout.bottom(), modeLayout.bottom());
        return new HudBounds(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
    }

    private HudLayouts getHudLayouts(float scaledWidth, float scaledHeight, boolean forceRefresh) {
        int baseX = Configs.Core.RENDER_HUD_X.getIntegerValue();
        int baseY = Configs.Core.RENDER_HUD_Y.getIntegerValue();
        int scaleConfig = Configs.Core.RENDER_HUD_SCALE.getIntegerValue();
        long tick = RuntimeAccess.get().currentTick();
        if (!forceRefresh
                && this.cachedHudLayouts != null
                && this.cachedHudTick == tick
                && Float.compare(this.cachedHudWidth, scaledWidth) == 0
                && Float.compare(this.cachedHudHeight, scaledHeight) == 0
                && this.cachedHudX == baseX
                && this.cachedHudY == baseY
                && this.cachedHudScale == scaleConfig) {
            return this.cachedHudLayouts;
        }

        float hudScale = getHudScale();
        PanelLayout summary = computeHudPanelLayout(
                baseX,
                baseY,
                buildHudSummaryLines(),
                scaledWidth,
                scaledHeight,
                hudScale
        );
        PanelLayout modes = computeHudPanelLayout(
                baseX,
                summary.bottom() + Math.max(4, Math.round(6 * hudScale)),
                buildHudModeLines(),
                scaledWidth,
                scaledHeight,
                hudScale
        );
        this.cachedHudTick = tick;
        this.cachedHudWidth = scaledWidth;
        this.cachedHudHeight = scaledHeight;
        this.cachedHudX = baseX;
        this.cachedHudY = baseY;
        this.cachedHudScale = scaleConfig;
        this.cachedHudLayouts = new HudLayouts(summary, modes);
        return this.cachedHudLayouts;
    }

    private List<HudLine> buildHudSummaryLines() {
        List<HudLine> lines = new ArrayList<>();
        boolean enabled = ConfigUtils.isEnable();
        String workMode = ((WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue()).equals(WorkingModeType.SINGLE) ? "单模" : "多模";
        lines.add(new HudLine("工作: " + (enabled ? "运行中" : "已关闭") + " | 模式: " + workMode + " | 功能: " + getActiveModeSummary(), new Color(255, 255, 255, 255)));

        String pauseReason = RuntimeAccess.get().modules().lastPauseReason();
        if (!enabled) {
            lines.add(new HudLine("调度: 已关闭", new Color(255, 204, 102, 255)));
        } else if (pauseReason != null) {
            lines.add(new HudLine("调度: 暂停 | 原因: " + humanizeSchedulerReason(pauseReason), new Color(255, 180, 90, 255)));
        } else {
            lines.add(new HudLine("调度: 运行中 | Tick: " + RuntimeAccess.get().currentTick(), new Color(180, 255, 180, 255)));
        }
        return lines;
    }

    private List<HudLine> buildHudModeLines() {
        List<HudLine> lines = new ArrayList<>();
        appendCommonModeLines(lines, HudStatsManager.Mode.PRINT, getModeDisplayName(HudStatsManager.Mode.PRINT), ConfigUtils.isPrintMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.MINE, getModeDisplayName(HudStatsManager.Mode.MINE), ConfigUtils.isMineMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.FILL, getModeDisplayName(HudStatsManager.Mode.FILL), ConfigUtils.isFillMode());
        appendCommonModeLines(lines, HudStatsManager.Mode.FLUID, getModeDisplayName(HudStatsManager.Mode.FLUID), ConfigUtils.isFluidMode());
        appendBedrockLines(lines, ConfigUtils.isBedrockMode());
        return lines;
    }

    private void appendCommonModeLines(List<HudLine> lines, HudStatsManager.Mode mode, String label, boolean active) {
        if (!active) {
            return;
        }
        HudStatsManager.Snapshot snapshot = HudStatsManager.getRuntime().snapshot(mode);
        double actualRate = getDisplayedModeRate(mode, snapshot);
        String status = humanizeCommonModeReason(mode, snapshot, actualRate);
        StringBuilder text = new StringBuilder("[").append(label).append("] ");
        if (shouldDisplayModeRate(mode)) {
            text.append(getModeRateLabel(mode)).append(' ').append(formatRate(actualRate)).append("/s | ");
        }
        FeatureModuleBase module = getModule(mode);
        text.append("设置 ").append(formatModeSettings(mode));
        if (module != null) {
            text.append(" | 扫描 ").append(formatScanState(module));
        }
        text.append(" | 状态 ").append(status);
        lines.add(new HudLine(text.toString(), new Color(120, 220, 255, 255)));
    }

    private void appendBedrockLines(List<HudLine> lines, boolean active) {
        if (!active) {
            return;
        }
        HudStatsManager.Snapshot snapshot = HudStatsManager.getRuntime().snapshot(HudStatsManager.Mode.BEDROCK);
        BedrockEngine.HudSnapshot bedrock = BedrockController.getHudSnapshot();
        String progressText = formatProgress(
                bedrock.confirmedSuccesses(),
                bedrock.submittedTargets(),
                bedrock.submittedTargets() > 0
                        ? (double) bedrock.confirmedSuccesses() / (double) bedrock.submittedTargets()
                        : 0.0D
        );
        int totalFailures = bedrock.failedTargets() + bedrock.stuckTargets();
        String status = humanizeBedrockReason(bedrock.lastReason());
        if (bedrock.totalTargets() <= 0 && bedrock.submittedTargets() <= 0 && "运行中".equals(status)) {
            status = "无目标";
        }

        lines.add(new HudLine("[破基岩] 进度 " + progressText
                + " | 成功率 " + formatPercent(bedrock.successRate())
                + " | 成功速率 " + formatRate(snapshot.ratePerSecond()) + "/s", new Color(120, 255, 170, 255)));
        lines.add(new HudLine("成功 " + bedrock.confirmedSuccesses()
                + " | 失败 " + totalFailures
                + " | 垂直 " + bedrock.verticalActiveTargets() + "/" + bedrock.verticalActiveCap()
                + " | 水平 " + bedrock.sideTargets() + "/" + bedrock.sideCap()
                + " | 清理 " + bedrock.cleanupQueueSize()
                + " | 压力 " + bedrock.cleanupPressure(), new Color(255, 255, 255, 255)));
        lines.add(new HudLine("吞吐 " + bedrock.configuredThroughput()
                + "/窗口 间隔 " + Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue()
                + " | 提交 " + bedrock.acceptedThisTick() + "/" + bedrock.submitCap()
                + " | 阻塞 " + bedrock.rejectedThisTick()
                + " | 扫描 " + formatScanState(RuntimeAccess.get().modules().bedrock())
                + " | 状态 " + status, new Color(255, 255, 255, 255)));
    }

    private void drawProgressBar(int x, int y, int barWidth, int barHeight, double progress,
                                 Color bgColor, Color fgColor) {
        double clampedProgress = clamp(progress, 0.0, 1.0);
        int barXStart = x - (barWidth / 2);
        int barXEnd = x + (barWidth / 2);
        int barYEnd = y + barHeight;
        int filledWidth = (int) (clampedProgress * barWidth);

        Render2DUtils.fill(barXStart, y, barXEnd, barYEnd, bgColor);
        if (filledWidth > 0) {
            Render2DUtils.fill(barXStart, y, barXStart + filledWidth, barYEnd, fgColor);
        }
    }

    private String getActiveModeSummary() {
        if (!ConfigUtils.isEnable()) {
            return "无";
        }
        List<String> names = new ArrayList<>();
        if (ConfigUtils.isPrintMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.PRINT));
        }
        if (ConfigUtils.isMineMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.MINE));
        }
        if (ConfigUtils.isFillMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.FILL));
        }
        if (ConfigUtils.isFluidMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.FLUID));
        }
        if (ConfigUtils.isBedrockMode()) {
            names.add(getModeDisplayName(HudStatsManager.Mode.BEDROCK));
        }
        return names.isEmpty() ? "无" : String.join(", ", names);
    }

    private String getModeDisplayName(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT -> "打印";
            case MINE -> "挖掘";
            case FILL -> "填充";
            case FLUID -> "排流体";
            case BEDROCK -> "破基岩";
            case TOTAL -> "总计";
        };
    }

    private String formatProgress(long finished, long total, double progress) {
        if (total <= 0) {
            return "--";
        }
        return formatPercent(progress) + " (" + finished + "/" + total + ")";
    }

    private String formatRate(double rate) {
        long tenths = Math.max(0L, Math.round(rate * 10.0D));
        return tenths / 10L + "." + tenths % 10L;
    }

    private String formatPercent(double value) {
        return (int) Math.round(clamp(value, 0.0D, 1.0D) * 100.0D) + "%";
    }

    private float getHudScale() {
        return (float) clamp(Configs.Core.RENDER_HUD_SCALE.getIntegerValue() / 100.0D, 0.5D, 2.0D);
    }

    private String humanizeSchedulerReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "运行中";
        }
        if (reason.startsWith("shared_precheck")) {
            return "容器打开或切物品中";
        }
        if (reason.startsWith("handler_precheck")) {
            return "共享前置阻塞";
        }
        if (reason.startsWith("send_queue_wait_modify_look") || reason.startsWith("action_wait_modify_look")) {
            return "等待转头";
        }
        if (reason.startsWith("lag_check")) {
            return "延迟过大";
        }
        return reason;
    }

    private boolean shouldDisplayModeRate(HudStatsManager.Mode mode) {
        return mode == HudStatsManager.Mode.PRINT
                || mode == HudStatsManager.Mode.MINE
                || mode == HudStatsManager.Mode.FILL
                || mode == HudStatsManager.Mode.FLUID;
    }

    private double getDisplayedModeRate(HudStatsManager.Mode mode, HudStatsManager.Snapshot snapshot) {
        return switch (mode) {
            case PRINT -> snapshot.completedRatePerSecond();
            case MINE, FILL, FLUID -> snapshot.completedRatePerSecond();
            default -> 0.0D;
        };
    }

    private String getModeRateLabel(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT, FILL, FLUID -> "放置";
            case MINE -> "破坏";
            case BEDROCK -> "成功";
            case TOTAL -> "速率";
        };
    }

    private FeatureModuleBase getModule(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT -> RuntimeAccess.get().modules().print();
            case MINE -> RuntimeAccess.get().modules().mine();
            case FILL -> RuntimeAccess.get().modules().fill();
            case FLUID -> RuntimeAccess.get().modules().fluid();
            case BEDROCK -> RuntimeAccess.get().modules().bedrock();
            case TOTAL -> null;
        };
    }

    private String formatScanState(FeatureModuleBase module) {
        ScanState state = module.getScanState();
        String text = switch (state) {
            case FULL -> "全量";
            case PARTIAL -> "局部";
            case LAZY -> module.getPendingIterationWorkCount() > 0 ? "前沿" : "惰性";
        };
        int dirtyRegions = module.getPendingDirtyRegionCount();
        if (dirtyRegions > 0) {
            text += "(" + dirtyRegions + ")";
        }
        var metrics = RuntimeAccess.get().scanEngine().metricsFor(module.getId());
        if (metrics.hasActivity()) {
            text += " " + formatScanMillis(metrics.scanNanos())
                    + "ms " + metrics.scannedBlocks()
                    + "块/" + metrics.scannedSections()
                    + "区 " + metrics.acceptedTargets() + "目标";
            if (metrics.budgetPauses() > 0) {
                text += " 切片" + metrics.budgetPauses();
            }
        }
        return text;
    }

    private String formatScanMillis(long scanNanos) {
        long hundredths = Math.max(0L, (scanNanos + 5_000L) / 10_000L);
        long whole = hundredths / 100L;
        long fraction = hundredths % 100L;
        return whole + "." + (fraction < 10L ? "0" : "") + fraction;
    }

    private String humanizeCommonModeReason(HudStatsManager.Mode mode, HudStatsManager.Snapshot snapshot, double actualRate) {
        String reason = snapshot.lastReason();
        if (reason == null || reason.isBlank() || "空闲".equals(reason)) {
            return actualRate > 0.0D ? "工作中" : "无目标";
        }
        if (snapshot.total() <= 0
                && actualRate <= 0.0D
                && !reason.contains("缺少")
                && !reason.contains("配置")
                && !reason.contains("列表为空")
                && !reason.contains("列表无匹配")
                && !reason.contains("失败")) {
            return "无目标";
        }
        if (reason.contains("失败")) {
            return "失败";
        }
        if (reason.contains("配置") || reason.contains("列表为空") || reason.contains("列表无匹配")) {
            return "未配置";
        }
        if (reason.contains("缺少") || reason.contains("主手无可填充方块")) {
            return "缺少方块";
        }
        if ("运行中".equals(reason) && snapshot.total() <= 0 && actualRate <= 0.0D) {
            return "无目标";
        }
        return "工作中";
    }

    private String formatModeSettings(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT, FILL, FLUID -> Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue()
                    + "/t 间隔" + Configs.Placement.PLACE_INTERVAL.getIntegerValue()
                    + formatRttSettings(mode);
            case MINE -> (Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue() == 0
                    ? "不限速"
                    : Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue() + "/t")
                    + " 间隔" + Configs.Break.BREAK_INTERVAL.getIntegerValue();
            case BEDROCK -> Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue()
                    + "/t 间隔" + Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue();
            case TOTAL -> "--";
        };
    }

    private String formatRttSettings(HudStatsManager.Mode mode) {
        if (mode != HudStatsManager.Mode.PRINT
                || !Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()) {
            return "";
        }
        var rate = RuntimeAccess.get().placementRateController();
        return " RTT" + RuntimeAccess.get().rttReplayController().getEstimatedRttMillis()
                + "ms/" + rate.effectiveIntervalTicks() + "t";
    }

    private String humanizeBedrockReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "运行中";
        }
        return switch (reason) {
            case "idle" -> "空闲";
            case "running", "accepted" -> "运行中";
            case "startup_serial" -> "启动串行";
            case "accept_backpressure" -> "接受背压";
            case "submit_cap" -> "提交上限";
            case "active_cap" -> "活跃上限";
            case "side_disabled" -> "水平通道关闭";
            case "side_lane_busy" -> "水平通道占用";
            case "retry_cooldown" -> "重试冷却";
            case "reserved_by_active_target" -> "被活跃任务占位";
            case "out_of_range_bedrock", "out_of_range_machine", "out_of_range" -> "超出交互范围";
            case "await_target_exposure" -> "等待目标暴露";
            case "duplicate_active_target" -> "重复目标";
            case "occupied_by_active_piston" -> "活塞占位";
            case "pending_cleanup" -> "等待清理";
            case "machine_overlap" -> "机器重叠";
            case "target_failed_on_create", "failed" -> "任务失败";
            case "stuck" -> "任务卡死";
            default -> reason;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HudLine(String text, Color color) {
    }

    private record PanelLayout(
            List<HudLine> lines,
            int drawX,
            int drawY,
            int baseWidth,
            int baseHeight,
            int scaledWidth,
            int scaledHeight,
            float scale
    ) {
        private int right() {
            return this.drawX + this.scaledWidth;
        }

        private int bottom() {
            return this.drawY + this.scaledHeight;
        }
    }

    private record HudLayouts(PanelLayout summary, PanelLayout modes) {
    }

    public record HudBounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width
                    && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }
}
