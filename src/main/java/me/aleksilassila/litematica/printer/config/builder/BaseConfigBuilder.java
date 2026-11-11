package me.aleksilassila.litematica.printer.config.builder;

import fi.dy.masa.malilib.config.options.ConfigBase;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigMetadata;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

@SuppressWarnings("unchecked")
public abstract class BaseConfigBuilder<T extends ConfigBase<?>, B extends BaseConfigBuilder<T, B>> {
    protected final I18n i18n;
    protected String nameKey;
    protected String descKey;
    protected @Nullable BooleanSupplier visible;

    public BaseConfigBuilder(I18n i18n) {
        this.i18n = i18n;
        this.nameKey = i18n.getConfigNameKey();
        this.descKey = i18n.getConfigDescKey();
        this.visible = null;
    }

    public BaseConfigBuilder(String translateKey) {
        this(I18n.of(translateKey));
    }

    public B setVisible(@Nullable BooleanSupplier visible) {
        this.visible = visible;
        return (B) this;
    }

    protected T buildExtension(T config) {
        buildMetadata(config);
        return config;
    }

    protected void buildMetadata(T config) {
        ConfigMetadata.register(config, this.nameKey, this.descKey, this.visible);
    }

    public abstract T build();
}
