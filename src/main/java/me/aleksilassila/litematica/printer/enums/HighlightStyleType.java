package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum HighlightStyleType implements ConfigOptionListEntry<HighlightStyleType> {
    OUTLINE("highlightStyleType.outline"), FILLED("highlightStyleType.filled"), BOTH("highlightStyleType.both");
    private final I18n i18n;
    HighlightStyleType(String key) { this.i18n = I18n.of(key); }
    @Override public I18n getI18n() { return this.i18n; }
}
