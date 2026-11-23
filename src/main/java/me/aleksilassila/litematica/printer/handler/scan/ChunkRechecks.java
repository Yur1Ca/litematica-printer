package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

/** Local chunk revisions fence old deliveries; repeat notifications wait behind one finite traversal. */
final class ChunkRechecks {
    private final Long2LongLinkedOpenHashMap versions = new Long2LongLinkedOpenHashMap();
    private final LongOpenHashSet admitted = new LongOpenHashSet();
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private long version, historyFloor;

    long version() { return version; }

    /** True when new coverage joined this cursor and its previously prepared head needs reordering. */
    boolean record(int x, int z, List<PrinterBox> boxes, ConstructionPositionCursor cursor) {
        long key = key(x, z);
        versions.putAndMoveToLast(key, ++version);
        if (versions.size() > 4096) historyFloor = versions.removeFirstLong();
        if (admitted.add(key)) {
            addCoverage(x, z, boxes, cursor);
            return true;
        }
        pending.add(key);
        return false;
    }

    boolean finishPass(List<PrinterBox> boxes, ConstructionPositionCursor cursor) {
        admitted.clear();
        if (pending.isEmpty()) return false;
        for (long key : pending) {
            addCoverage((int) (key >> 32), (int) key, boxes, cursor);
            admitted.add(key);
        }
        pending.clear();
        return !cursor.isComplete();
    }

    void resetPass() {
        admitted.clear();
        pending.clear();
    }

    boolean current(BlockPos pos, long since) {
        if (since == version) return true;
        int x = pos.getX(), z = pos.getZ(), cx = x >> 4, cz = z >> 4;
        return since >= historyFloor && versions.get(key(cx, cz)) <= since
                && ((x & 15) != 0 || versions.get(key(cx - 1, cz)) <= since)
                && ((x & 15) != 15 || versions.get(key(cx + 1, cz)) <= since)
                && ((z & 15) != 0 || versions.get(key(cx, cz - 1)) <= since)
                && ((z & 15) != 15 || versions.get(key(cx, cz + 1)) <= since);
    }

    private static long key(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }

    private static void addCoverage(int x, int z, List<PrinterBox> boxes, ConstructionPositionCursor cursor) {
        int minX = x << 4, minZ = z << 4;
        for (PrinterBox box : boxes) {
            // The full column and its four touching faces. Diagonal columns cannot supply face neighbours.
            addIntersection(box, minX - 1, minZ, minX + 16, minZ + 15, cursor);
            addIntersection(box, minX, minZ - 1, minX + 15, minZ - 1, cursor);
            addIntersection(box, minX, minZ + 16, minX + 15, minZ + 16, cursor);
        }
    }

    private static void addIntersection(PrinterBox box, int x1, int z1, int x2, int z2,
                                        ConstructionPositionCursor cursor) {
        int minX = Math.max(x1, box.minX), minZ = Math.max(z1, box.minZ);
        int maxX = Math.min(x2, box.maxX), maxZ = Math.min(z2, box.maxZ);
        if (minX <= maxX && minZ <= maxZ) cursor.addBox(new PrinterBox(minX, box.minY, minZ, maxX, box.maxY, maxZ));
    }
}
