package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.integration.quickshulker.HighlightBlockRenderer;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;

import static me.aleksilassila.litematica.printer.config.Configs.*;

public class InitHandler implements IInitializationHandler {
    private static void initModConfig() {
    }

    @Override
    public void registerModHandlers() {
        Configs.init();
        initModConfig();
        initConfigCallback();
        HighlightBlockRenderer.init();  // 高亮显示方块渲染器
    }

    private void initConfigCallback() {
        Hotkeys.CLOSE_ALL_MODE.getKeybind().setCallback((action, keybind) -> {
            if (keybind.isKeybindHeld()) {
                Core.MINE.setBooleanValue(false);
                Core.FLUID.setBooleanValue(false);
                Core.WORK_SWITCH.setBooleanValue(false);
                Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
                MessageUtils.setOverlayMessage(I18n.CLOSE_ALL_MODE_NOTICE.getName());
            }
            return true;
        });

        // 工作开关
        Core.WORK_SWITCH.setValueChangeCallback(b -> {
            if (!b.getBooleanValue()) {
                RuntimeAccess.get().reset("work_switch_off");
            }
        });

        // 切换模式时, 关闭破基岩
        Core.WORK_MODE_TYPE.setValueChangeCallback(b -> {
            if (!b.getOptionListValue().equals(PrintModeType.BEDROCK)) {
                BedrockController.reset();
            }
        });

        // 特殊设置时，自动刷新界面
        Core.WORK_MODE.setValueChangeCallback(b -> ConfigUi.refresh());
        Print.FILL_COMPOSTER.setValueChangeCallback(b -> ConfigUi.refresh());
        Print.PRINT_RESERVE_ITEMS.setValueChangeCallback(b -> ConfigUi.refresh());
        Break.BREAK_LIMITER.setValueChangeCallback(b -> ConfigUi.refresh());
        Break.BREAK_LIMIT.setValueChangeCallback(b -> ConfigUi.refresh());
        Mine.EXCAVATE_LIMITER.setValueChangeCallback(b -> ConfigUi.refresh());
        Mine.EXCAVATE_LIMIT.setValueChangeCallback(b -> ConfigUi.refresh());
        Fill.FILL_BLOCK_MODE.setValueChangeCallback(b -> ConfigUi.refresh());
        Core.LAG_CHECK.setValueChangeCallback(b -> ConfigUi.refresh());
        Core.RENDER_HUD.setValueChangeCallback(b -> ConfigUi.refresh());
        Core.MISSING_MATERIAL_HUD.setValueChangeCallback(b -> ConfigUi.refresh());
        Configs.Placement.RTT_ADAPTIVE_INTERVAL.setValueChangeCallback(b -> ConfigUi.refresh());
        Break.BREAK_USE_DELAYED_DESTROY.setValueChangeCallback(b -> ConfigUi.refresh());
        Special.REMOTE_TAKE.setValueChangeCallback(b -> {
            ConfigUi.refresh();
            if (!b.getBooleanValue()) {
                RuntimeAccess.get().chestTrackerAdapter().reset();
            }
        });
    }
}
