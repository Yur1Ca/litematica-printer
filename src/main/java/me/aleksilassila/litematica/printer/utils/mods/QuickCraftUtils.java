package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.Reference;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Uses QuickCraft's own Easy Place camera policy when its optional API is present. */
public final class QuickCraftUtils {
    private static final Method TRACE_ENTITY = findTraceEntity();
    private static boolean failureLogged;

    private QuickCraftUtils() {}

    private static Method findTraceEntity() {
        try {
            var type = Class.forName("com.yiyihehe.quickcraft.QuickFreeCameraInteractions", false,
                    QuickCraftUtils.class.getClassLoader());
            var method = type.getMethod("getEasyPlaceTraceEntity", Minecraft.class, Entity.class);
            return Modifier.isStatic(method.getModifiers()) && Entity.class.isAssignableFrom(method.getReturnType())
                    ? method : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static Entity easyPlaceTraceEntity(Minecraft client) {
        var camera = client.getCameraEntity();
        if (camera == null || camera == client.player || TRACE_ENTITY == null) return client.player;
        try {
            var entity = TRACE_ENTITY.invoke(null, client, client.player);
            return entity instanceof Entity traceEntity ? traceEntity : client.player;
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!failureLogged) {
                failureLogged = true;
                Reference.LOGGER.warn("QuickCraft Easy Place camera lookup failed", exception);
            }
            return client.player;
        }
    }
}
