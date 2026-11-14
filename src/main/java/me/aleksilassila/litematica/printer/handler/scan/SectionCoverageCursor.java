package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Resumable coarse coverage of a scope, independent of cached section facts and action order. */
final class SectionCoverageCursor {
    enum PollResult { AVAILABLE, PAUSED, COMPLETE }

    private final List<SectionBox> boxes;
    private int boxIndex;
    private int x, y, z;

    SectionCoverageCursor(List<PrinterBox> boxes) {
        this.boxes = new ArrayList<>(boxes.size());
        for (PrinterBox box : boxes) {
            int minX = Math.max(box.minX, -33_554_432), maxX = Math.min(box.maxX, 33_554_431);
            int minY = Math.max(box.minY, -2048), maxY = Math.min(box.maxY, 2047);
            int minZ = Math.max(box.minZ, -33_554_432), maxZ = Math.min(box.maxZ, 33_554_431);
            if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
                this.boxes.add(new SectionBox(minX >> 4, minY >> 4, minZ >> 4,
                        maxX >> 4, maxY >> 4, maxZ >> 4));
            }
        }
        this.reset();
    }

    /** Each candidate, including an overlap duplicate, consumes one check from this call. */
    PollResult poll(BlockPos.MutableBlockPos sectionCoordinates, int maxChecks) {
        for (int checks = 0; checks < maxChecks && this.boxIndex < this.boxes.size(); checks++) {
            boolean owned = true;
            for (int earlier = 0; earlier < this.boxIndex; earlier++) {
                if (this.boxes.get(earlier).contains(this.x, this.y, this.z)) {
                    owned = false;
                    break;
                }
            }
            if (owned) sectionCoordinates.set(this.x, this.y, this.z);
            this.advance();
            if (owned) return PollResult.AVAILABLE;
        }
        return this.boxIndex >= this.boxes.size() ? PollResult.COMPLETE : PollResult.PAUSED;
    }

    void reset() {
        this.boxIndex = 0;
        this.startBox();
    }

    private void advance() {
        SectionBox box = this.boxes.get(this.boxIndex);
        if (this.x < box.maxX()) {
            this.x++;
            return;
        }
        this.x = box.minX();
        if (this.z < box.maxZ()) {
            this.z++;
            return;
        }
        this.z = box.minZ();
        if (this.y < box.maxY()) {
            this.y++;
            return;
        }
        this.boxIndex++;
        this.startBox();
    }

    private void startBox() {
        if (this.boxIndex >= this.boxes.size()) return;
        SectionBox box = this.boxes.get(this.boxIndex);
        this.x = box.minX();
        this.y = box.minY();
        this.z = box.minZ();
    }

    private record SectionBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY
                    && z >= this.minZ && z <= this.maxZ;
        }
    }
}
