package me.aleksilassila.litematica.printer.config.builder;

import fi.dy.masa.malilib.config.options.ConfigColor;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.PrinterConfigOptions;

public final class ColorConfigBuilder extends BaseConfigBuilder<ConfigColor, ColorConfigBuilder> {
    private String value = "#FFFFFFFF";
    public ColorConfigBuilder(String key) { super(I18n.of(key)); }
    public ColorConfigBuilder defaultValue(String value) { this.value = value; return this; }
    @Override public ConfigColor build() {
        return buildExtension(new PrinterConfigOptions.ColorOption(i18n.getNameKey(), this.value, descKey));
    }
}
