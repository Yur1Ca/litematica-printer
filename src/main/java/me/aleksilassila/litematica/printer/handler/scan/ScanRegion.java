package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Immutable geometry and distance limits for one player-centered scan request. */
record ScanRegion(
        int centerX,
        int centerY,
        int centerZ,
        int minSectionX,
        int minSectionY,
        int minSectionZ,
        int maxSectionX,
        int maxSectionY,
        int maxSectionZ,
        int maxDistanceBand
) {
    static ScanRegion from(PrinterBox box, LocalPlayer player) {
        Object shape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        BlockPos blockCenter = player != null && (shape == RadiusShapeType.CUBE || shape == RadiusShapeType.OCTAHEDRON)
                ? player.blockPosition() : null;
        int centerX = player == null ? (box.minX + box.maxX) >> 1
                : blockCenter == null ? (int) Math.floor(player.getX()) : blockCenter.getX();
        int centerY = player == null ? (box.minY + box.maxY) >> 1
                : blockCenter == null ? (int) Math.floor(player.getEyeY()) : blockCenter.getY();
        int centerZ = player == null ? (box.minZ + box.maxZ) >> 1
                : blockCenter == null ? (int) Math.floor(player.getZ()) : blockCenter.getZ();
        int maxDistanceBand = farthestDistanceBand(box, centerX, centerY, centerZ);
        if (player != null) {
            if (shape == RadiusShapeType.SPHERE || shape == RadiusShapeType.OCTAHEDRON) {
                maxDistanceBand = Math.min(maxDistanceBand, ConfigUtils.getWorkRange() + 2);
            }
            if (Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()) {
                double dx = player.getX() - centerX, dy = player.getEyeY() - centerY, dz = player.getZ() - centerZ;
                // Keep this envelope safe for direct sources as well as tracker-clipped boxes.
                int interactionBand = (int) Math.ceil(player.blockInteractionRange() + 3.0D
                        + Math.sqrt(dx * dx + dy * dy + dz * dz));
                maxDistanceBand = Math.min(maxDistanceBand, interactionBand);
            }
        }
        return new ScanRegion(
                centerX,
                centerY,
                centerZ,
                sectionCoord(box.minX),
                sectionCoord(box.minY),
                sectionCoord(box.minZ),
                sectionCoord(box.maxX),
                sectionCoord(box.maxY),
                sectionCoord(box.maxZ),
                maxDistanceBand
        );
    }

    boolean sameSectionWindow(ScanRegion other) {
        return this.minSectionX == other.minSectionX
                && this.minSectionY == other.minSectionY
                && this.minSectionZ == other.minSectionZ
                && this.maxSectionX == other.maxSectionX
                && this.maxSectionY == other.maxSectionY
                && this.maxSectionZ == other.maxSectionZ;
    }

    private static int sectionCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    private static int farthestDistanceBand(PrinterBox box, int centerX, int centerY, int centerZ) {
        long dx = Math.max(Math.abs((long) box.minX - centerX), Math.abs((long) box.maxX - centerX));
        long dy = Math.max(Math.abs((long) box.minY - centerY), Math.abs((long) box.maxY - centerY));
        long dz = Math.max(Math.abs((long) box.minZ - centerZ), Math.abs((long) box.maxZ - centerZ));
        return (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
