package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

public class StringUtils {

    public static MutableComponent translatable(String key) {
        return Component.translatable(key);
    }

    public static MutableComponent translatable(String key, Object... objects) {
        return Component.translatable(key, objects);
    }

    public static MutableComponent literal(String text) {
        return Component.literal(text);
    }

    @Nullable
    public static String getTranslatedOrFallback(String key, @Nullable String fallback) {
        String translated = translatable(key).getString();
        if (!key.equals(translated)) {
            return translated;
        }
        return fallback;
    }

    @Nullable
    public static String getTranslatedOrFallback(String key, @Nullable String fallback, Object... objects) {
        String translated = translatable(key, objects).getString();
        if (!key.equals(translated)) {
            return translated;
        }
        return fallback;
    }

}
