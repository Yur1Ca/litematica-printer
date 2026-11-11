package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.StringJoiner;

public class StringUtils {

    public final static MutableComponent EMPTY = literal("");

    public static MutableComponent translatable(String key) {
        return Component.translatable(key);
    }

    public static MutableComponent translatable(String key, Object... objects) {
        return Component.translatable(key, objects);
    }

    public static MutableComponent literal(String text) {
        return Component.literal(text);
    }

    public static MutableComponent nullToEmpty(@Nullable String string) {
        return string != null ? literal(string) : EMPTY;
    }

    public static String mergeComments(String delimiter, Component... customComments) {
        StringJoiner joiner = new StringJoiner(delimiter);
        for (Component comment : customComments) {
            if (comment != null) {
                joiner.add(comment.getString());
            }
        }
        return joiner.toString();
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
