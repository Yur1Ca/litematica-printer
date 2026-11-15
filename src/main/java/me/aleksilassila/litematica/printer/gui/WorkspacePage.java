package me.aleksilassila.litematica.printer.gui;

import me.aleksilassila.litematica.printer.I18n;

public enum WorkspacePage {
    WORKBENCH("workbench"), PRINT("print"), MINE("mine"), FILL("fill"),
    COVER("cover"), FLUID("fluid"), BEDROCK("bedrock"), COMMON("common"),
    MATERIALS("materials"), HIGHLIGHT("highlight"), HOTKEYS("hotkeys");

    private final String key;

    WorkspacePage(String key) {
        this.key = key;
    }

    public String title() {
        return I18n.of("ui.page." + this.key).getName().getString();
    }

    public String description() {
        return I18n.of("ui.page." + this.key).getDesc().getString();
    }
}
