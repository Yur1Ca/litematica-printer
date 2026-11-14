package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Small geometry operations shared by scan sessions and cursors. */
final class ScanGeometry {
    private ScanGeometry() {
    }

    static boolean containsAny(List<PrinterBox> boxes, BlockPos pos) {
        for (PrinterBox box : boxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    static int sectionCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | ((long) sectionY & 0xFFFFFL);
    }

    static int sectionX(long key) { return (int) (key >> 42); }
    static int sectionY(long key) { return (int) (key << 44 >> 44); }
    static int sectionZ(long key) { return (int) (key << 22 >> 42); }

    static long distanceSqr(BlockPos pos, int centerX, int centerY, int centerZ) {
        long dx = pos.getX() - (long) centerX;
        long dy = pos.getY() - (long) centerY;
        long dz = pos.getZ() - (long) centerZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
