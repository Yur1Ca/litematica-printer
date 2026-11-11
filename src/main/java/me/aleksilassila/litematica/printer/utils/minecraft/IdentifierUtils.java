package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.resources.Identifier;

public class IdentifierUtils {

    public static Identifier of(String string) {
        return Identifier.parse(string);
    }

    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}

