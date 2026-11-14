package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import me.aleksilassila.litematica.printer.printer.PrinterBox;

/** Observes only the requested schematic window, without building a whole-blueprint position index. */
final class LocalSchematicTracker {
    private static final int CHECKS_PER_REQUEST = 64;
    private static final int MAX_CHUNKS = 4096;
    private final Map<Long, Stamp> chunks = new LinkedHashMap<>(16, 0.75F, true);
    private List<PrinterBox> boxes = List.of();
    private int boxIndex;
    private long offset;

    void clear() {
        this.chunks.clear();
        this.boxes = List.of();
        this.boxIndex = 0;
        this.offset = 0L;
    }

    void observe(WorldSchematic schematic, List<PrinterBox> sourceBoxes, BiConsumer<Integer, Integer> replaced) {
        if (schematic == null || sourceBoxes.isEmpty()) return;
        if (!this.boxes.equals(sourceBoxes)) {
            this.boxes = List.copyOf(sourceBoxes);
            this.boxIndex = 0;
            this.offset = 0L;
        }
        int finishedBoxes = 0;
        for (int checks = 0; checks < CHECKS_PER_REQUEST && finishedBoxes < sourceBoxes.size(); checks++) {
            PrinterBox box = sourceBoxes.get(this.boxIndex);
            int minX = box.minX >> 4, minZ = box.minZ >> 4;
            long width = (long) (box.maxX >> 4) - minX + 1L;
            long depth = (long) (box.maxZ >> 4) - minZ + 1L;
            int x = (int) (minX + this.offset % width);
            int z = (int) (minZ + this.offset / width);
            ChunkSchematic chunk = schematic.getChunk(x, z);
            Stamp current = new Stamp(chunk, chunk != null && chunk.getState().atLeast(ChunkSchematicState.FILLED));
            long key = (long) x << 32 | z & 0xFFFFFFFFL;
            Stamp previous = this.chunks.put(key, current);
            if (previous != null && (previous.chunk() != current.chunk() || previous.filled() != current.filled())) {
                replaced.accept(x, z);
            }
            if (this.chunks.size() > MAX_CHUNKS) this.chunks.remove(this.chunks.keySet().iterator().next());
            if (++this.offset >= width * depth) {
                this.offset = 0L;
                this.boxIndex = (this.boxIndex + 1) % sourceBoxes.size();
                finishedBoxes++;
            }
        }
    }

    private record Stamp(ChunkSchematic chunk, boolean filled) { }
}
