package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class MessageUtils {
    public static void setOverlayMessage(Component message, boolean bl) {
        //#if MC > 260100
        //$$ Minecraft.getInstance().gui.hud.setOverlayMessage(message, bl);
        //#else
        Minecraft.getInstance().gui.setOverlayMessage(message, bl);
        //#endif
    }

    public static void setOverlayMessage(Component message) {
        setOverlayMessage(message, false);
    }

    public static void setOverlayMessage(String message) {
        setOverlayMessage(StringUtils.literal(message));
    }

    public static void addMessage(Component message) {
        //#if MC > 260100
        //$$ Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
        //#else
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
        //#endif
    }

    public static void addMessage(String message) {
        addMessage(StringUtils.literal(message));
    }
}
