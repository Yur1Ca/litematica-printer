package me.aleksilassila.litematica.printer.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

/** MaLiLib option subclasses whose presentation behavior is scoped to this mod only. */
public final class PrinterConfigOptions {
    private PrinterConfigOptions() {
    }

    private static String name(fi.dy.masa.malilib.config.IConfigBase config, String fallback) {
        return ConfigMetadata.translatedName(config, fallback);
    }

    private static String comment(fi.dy.masa.malilib.config.IConfigBase config, String fallback) {
        return ConfigMetadata.translatedComment(config, fallback);
    }

    public static final class BooleanOption extends ConfigBoolean {
        public BooleanOption(String name, boolean value, String comment) { super(name, value, comment); }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }

    public static final class IntegerOption extends ConfigInteger {
        public IntegerOption(String name, int value, int min, int max, boolean slider, String comment) {
            super(name, value, min, max, slider, comment);
        }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }

    public static final class OptionListOption extends ConfigOptionList {
        public OptionListOption(String name, IConfigOptionListEntry value, String comment) { super(name, value, comment); }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }

    public static final class StringListOption extends ConfigStringList {
        public StringListOption(String name, ImmutableList<String> value, String comment) { super(name, value, comment); }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }

    public static final class HotkeyOption extends ConfigHotkey {
        public HotkeyOption(String name, String value, KeybindSettings settings, String comment) {
            super(name, value, settings, comment);
        }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }

    public static final class BooleanHotkeyOption extends ConfigBooleanHotkeyed {
        public BooleanHotkeyOption(String name, boolean value, String hotkey, KeybindSettings settings,
                                   String comment, String prettyName) {
            super(name, value, hotkey, settings, comment, prettyName);
        }
        @Override public String getPrettyName() { return name(this, super.getPrettyName()); }
        @Override public String getConfigGuiDisplayName() { return getPrettyName(); }
        @Override public String getComment() { return comment(this, super.getComment()); }
    }
}
