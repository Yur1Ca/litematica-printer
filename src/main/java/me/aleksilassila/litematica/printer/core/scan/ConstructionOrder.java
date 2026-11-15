package me.aleksilassila.litematica.printer.core.scan;

/** Y/Z/serpentine-X construction order, optionally rotated around one fixed starting position. */
public final class ConstructionOrder {
    // Retained for callers storing the former anchor metadata; tiles do not affect block rank.
    public static final int TILE_SIZE = 8;

    private final int anchorTileX;
    private final int anchorTileZ;
    private final boolean descendingY;
    private final boolean hasStartPosition;
    private final long startPosition;

    /** Canonical order; the old horizontal anchor only supplies compatibility metadata. */
    public ConstructionOrder(int anchorX, int anchorZ, boolean descendingY) {
        this(anchorX, anchorZ, descendingY, false, 0L);
    }

    /** Preserve the canonical sequence, beginning at this point and wrapping once at its end. */
    public ConstructionOrder(int startX, int startY, int startZ, boolean descendingY) {
        this(startX, startZ, descendingY, true, ((long) startX & 0x3FFFFFFL) << 38
                | ((long) startZ & 0x3FFFFFFL) << 12 | ((long) startY & 0xFFFL));
    }

    private ConstructionOrder(int anchorX, int anchorZ, boolean descendingY, boolean hasStartPosition,
                              long startPosition) {
        this.anchorTileX = Math.floorDiv(anchorX, TILE_SIZE);
        this.anchorTileZ = Math.floorDiv(anchorZ, TILE_SIZE);
        this.descendingY = descendingY;
        this.hasStartPosition = hasStartPosition;
        this.startPosition = startPosition;
    }

    public int anchorTileX() { return this.anchorTileX; }
    public int anchorTileZ() { return this.anchorTileZ; }
    public boolean descendingY() { return this.descendingY; }
    public boolean hasStartPosition() { return this.hasStartPosition; }
    public long startPosition() { return this.startPosition; }

    public int compare(long packedA, long packedB) {
        if (this.hasStartPosition) {
            boolean firstWrapped = this.compareBase(packedA, this.startPosition) < 0;
            boolean secondWrapped = this.compareBase(packedB, this.startPosition) < 0;
            int result = Boolean.compare(firstWrapped, secondWrapped);
            if (result != 0) return result;
        }
        return this.compareBase(packedA, packedB);
    }

    private int compareBase(long packedA, long packedB) {
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
