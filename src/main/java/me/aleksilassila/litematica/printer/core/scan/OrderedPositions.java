package me.aleksilassila.litematica.printer.core.scan;

import it.unimi.dsi.fastutil.Arrays;

/** Immutable, world-free distance/Y/X/Z ordering of packed block coordinates. */
public final class OrderedPositions {
    private final long[] positions;
    private final long[] distances;

    public OrderedPositions(long[] input, int centerX, int centerY, int centerZ) {
        this.positions = input.clone();
        this.distances = new long[input.length];
        for (int i = 0; i < input.length; i++) {
            long position = this.positions[i];
            long dx = x(position) - (long) centerX;
            long dy = y(position) - (long) centerY;
            long dz = z(position) - (long) centerZ;
            this.distances[i] = dx * dx + dy * dy + dz * dz;
        }
        // Only array indices are compared; the distance is never recalculated by the sort.
        Arrays.quickSort(0, input.length, this::compare, (a, b) -> {
            long position = this.positions[a];
            this.positions[a] = this.positions[b];
            this.positions[b] = position;
            long distance = this.distances[a];
            this.distances[a] = this.distances[b];
            this.distances[b] = distance;
        });
    }

    public int size() { return this.positions.length; }
    public long positionAt(int index) { return this.positions[index]; }
    public long distanceAt(int index) { return this.distances[index]; }
    public long[] copyPositions() { return this.positions.clone(); }

    private int compare(int a, int b) {
        int order = Long.compare(this.distances[a], this.distances[b]);
        if (order != 0) return order;
        long first = this.positions[a], second = this.positions[b];
        order = Integer.compare(y(first), y(second));
        if (order != 0) return order;
        order = Integer.compare(x(first), x(second));
        return order != 0 ? order : Integer.compare(z(first), z(second));
    }

    // BlockPos.asLong layout, shared by every supported version: signed X:26/Z:26/Y:12.
    // Keep the worker independent of Minecraft objects and their initialization/lifecycle.
    private static int x(long packed) { return (int) (packed >> 38); }
    private static int y(long packed) { return (int) (packed << 52 >> 52); }
    private static int z(long packed) { return (int) (packed << 26 >> 38); }
}
