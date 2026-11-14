package me.aleksilassila.litematica.printer.core.scan;

/** Global Y/Z/serpentine-X construction order, independent of player position and scan scope. */
public final class ConstructionOrder {
    // Retained for callers storing the former anchor metadata; tiles do not affect block rank.
    public static final int TILE_SIZE = 8;

    private final int anchorTileX;
    private final int anchorTileZ;
    private final boolean descendingY;

    public ConstructionOrder(int anchorX, int anchorZ, boolean descendingY) {
        this.anchorTileX = Math.floorDiv(anchorX, TILE_SIZE);
        this.anchorTileZ = Math.floorDiv(anchorZ, TILE_SIZE);
        this.descendingY = descendingY;
    }

    public int anchorTileX() { return this.anchorTileX; }
    public int anchorTileZ() { return this.anchorTileZ; }
    public boolean descendingY() { return this.descendingY; }

    public int compare(long packedA, long packedB) {
        int ax = x(packedA), az = z(packedA);
        int bx = x(packedB), bz = z(packedB);
        int result = this.descendingY ? Integer.compare(y(packedB), y(packedA))
                : Integer.compare(y(packedA), y(packedB));
        if (result != 0) return result;
        result = Integer.compare(az, bz);
        if (result != 0) return result;
        return (az & 1) == 0 ? Integer.compare(ax, bx) : Integer.compare(bx, ax);
    }

    /** Compatibility order for tile metadata; actual construction rank compares whole block rows. */
    public int compareTiles(int tileAX, int tileAZ, int tileBX, int tileBZ) {
        int result = Integer.compare(tileAZ, tileBZ);
        return result != 0 ? result : Integer.compare(tileAX, tileBX);
    }

    // BlockPos.asLong uses signed X:26/Z:26/Y:12 in every supported version.
    private static int x(long packed) { return (int) (packed >> 38); }
    private static int y(long packed) { return (int) (packed << 52 >> 52); }
    private static int z(long packed) { return (int) (packed << 26 >> 38); }
}
