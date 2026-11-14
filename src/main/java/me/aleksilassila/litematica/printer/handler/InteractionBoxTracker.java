package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class InteractionBoxTracker {
    @Nullable
    private final AtomicReference<PrinterBox> boxReference;
    @Nullable
    private PrinterBox lastBox;
    @Nullable
    private TrackingKey lastKey;

    InteractionBoxTracker(boolean enabled) {
        this.boxReference = enabled ? new AtomicReference<>() : null;
    }

    @Nullable
    AtomicReference<PrinterBox> getBoxReference() {
        return this.boxReference;
    }

    void resetPlayerTracking() {
        this.lastKey = null;
    }

    void update(LocalPlayer player) {
        if (this.boxReference == null) {
            return;
        }
        TrackingKey key = trackingKey(player);
        @Nullable PrinterBox box = this.boxReference.get();
        if (!key.equals(this.lastKey) || !Objects.equals(box, this.lastBox)) {
            this.lastKey = key;
            box = this.createBox(key);
            this.lastBox = box;
            this.boxReference.set(box);
        }
    }

    private @Nullable PrinterBox createBox(TrackingKey key) {
        int minX = (int) Math.floor(key.centerX() - key.range());
        int maxX = (int) Math.ceil(key.centerX() + key.range());
        int minY = (int) Math.floor(key.centerY() - key.range());
        int maxY = (int) Math.ceil(key.centerY() + key.range());
        int minZ = (int) Math.floor(key.centerZ() - key.range());
        int maxZ = (int) Math.ceil(key.centerZ() + key.range());
        if (!Double.isNaN(key.physicalRadius())) {
            // The exact reach rule measures eye-to-block-AABB distance. A block's origin
            // may lie one extra unit on the negative side of an eye-centered reach bound.
            minX = Math.max(minX, (int) Math.floor(key.eyeX() - key.physicalRadius() - 1.0D));
            maxX = Math.min(maxX, (int) Math.ceil(key.eyeX() + key.physicalRadius()));
            minY = Math.max(minY, (int) Math.floor(key.eyeY() - key.physicalRadius() - 1.0D));
            maxY = Math.min(maxY, (int) Math.ceil(key.eyeY() + key.physicalRadius()));
            minZ = Math.max(minZ, (int) Math.floor(key.eyeZ() - key.physicalRadius() - 1.0D));
            maxZ = Math.min(maxZ, (int) Math.ceil(key.eyeZ() + key.physicalRadius()));
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) return null;
        PrinterBox result = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
        return result.minY > result.maxY ? null : result;
    }

    private static TrackingKey trackingKey(LocalPlayer player) {
        RadiusShapeType shape = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType value
                ? value : null;
        double eyeX = player.getX(), eyeY = player.getEyeY(), eyeZ = player.getZ();
        double centerX = eyeX, centerY = eyeY, centerZ = eyeZ;
        if (shape == RadiusShapeType.CUBE || shape == RadiusShapeType.OCTAHEDRON) {
            BlockPos block = player.blockPosition();
            centerX = block.getX();
            centerY = block.getY();
            centerZ = block.getZ();
        }
        boolean physical = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        return new TrackingKey(shape, centerX, centerY, centerZ, ConfigUtils.getWorkRange(),
                physical ? eyeX : centerX, physical ? eyeY : centerY, physical ? eyeZ : centerZ,
                physical ? player.blockInteractionRange() + 1.0D : Double.NaN);
    }

    private record TrackingKey(@Nullable RadiusShapeType shape, double centerX, double centerY, double centerZ,
                               double range, double eyeX, double eyeY, double eyeZ, double physicalRadius) { }
}
