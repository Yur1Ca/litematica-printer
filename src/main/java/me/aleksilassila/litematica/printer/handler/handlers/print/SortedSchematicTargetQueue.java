package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public final class SortedSchematicTargetQueue implements ScanCandidateIterable {
    private static final int MAX_SORT_BUFFER = 4096;
    private final ScanEngine scanEngine;
    private final Long2ObjectLinkedOpenHashMap<BlockPos> queue = new Long2ObjectLinkedOpenHashMap<>();
    private boolean insertionOrdered = true;
    private List<PrinterBox> boxes = List.of();
    private boolean hasMoreSource;
    private ScanCandidateIterable sourceGuard;
    private long generation;
    private long replacementGeneration;
    private long lastFillTick = Long.MIN_VALUE;
    private long lastDirtyVersion = Long.MIN_VALUE;
    private long lastPrintSourceRevision = Long.MIN_VALUE;

    public SortedSchematicTargetQueue(ScanEngine scanEngine) {
        this.scanEngine = scanEngine;
    }

    public void clear() {
        this.queue.clear();
        this.insertionOrdered = true;
        this.generation++;
        this.replacementGeneration++;
        this.boxes = List.of();
        this.hasMoreSource = false;
        this.sourceGuard = null;
        this.lastFillTick = Long.MIN_VALUE;
        this.lastDirtyVersion = Long.MIN_VALUE;
        this.lastPrintSourceRevision = Long.MIN_VALUE;
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        return this.iterable(sourceBoxes, level, schematic, player, scanGuardLimit, true, pos -> true);
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level,
                                       WorldSchematic schematic, LocalPlayer player, int scanGuardLimit,
                                       boolean fillWhenBuffered, Predicate<BlockPos> candidateFilter) {
        if (!this.boxes.equals(sourceBoxes)) {
            // The source cursor already advanced past buffered entries. Moving the window
            // must retain its still-valid prefix instead of delaying it until a full follow-up.
            this.queue.values().removeIf(pos -> sourceBoxes.stream().noneMatch(box -> box.contains(pos)));
            if (this.queue.size() <= 1) this.insertionOrdered = true;
            this.generation++;
            this.hasMoreSource = true;
            this.lastFillTick = Long.MIN_VALUE;
        }
        this.boxes = List.copyOf(sourceBoxes);
        this.fill(sourceBoxes, level, schematic, player, scanGuardLimit, fillWhenBuffered, candidateFilter);
        return this.currentView();
    }

    private ScanCandidateIterable currentView() {
        long viewGeneration = this.generation;
        ScanCandidateIterable guard = this.sourceGuard;
        return new ScanCandidateIterable() {
            @Override public boolean isValid() {
                return viewGeneration == generation && (guard == null || guard.isValid());
            }
            @Override public ScanAvailability availability() {
                return this.isValid() ? SortedSchematicTargetQueue.this.availability() : ScanAvailability.COMPLETE;
            }
            @Override public boolean isBuffered() { return true; }
            @Override public Iterator<BlockPos> iterator() {
                Iterator<BlockPos> iterator = SortedSchematicTargetQueue.this.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return isValid() && iterator.hasNext(); }
                    @Override public BlockPos next() {
                        if (!this.hasNext()) throw new java.util.NoSuchElementException("sorted source was replaced");
                        return iterator.next();
                    }
                };
            }
        };
    }

    /** Captures already buffered work and its source guard without producing new candidates. */
    public ScanCandidateIterable bufferedPositions() { return this.currentView(); }

    long replacementGeneration() { return this.replacementGeneration; }

    /** Scope changes retain delivery ownership; cleared or replaced sources do not. */
    boolean canRetain(BlockPos pos, long replacementGeneration) {
        return this.replacementGeneration == replacementGeneration && this.isValid()
                && this.boxes.stream().anyMatch(box -> box.contains(pos));
    }

    /** Return a dropped delivery reference to its owner without discarding classification facts. */
    public void reoffer(String ownerKey, BlockPos pos) { this.scanEngine.reoffer(ownerKey, pos); }

    public boolean hasPendingWork() {
        return !this.queue.isEmpty() || this.hasMoreSource;
    }

    public int pendingTargetCount() { return this.queue.size(); }

    public Comparator<BlockPos> targetComparator() {
        return this.scanEngine.targetComparator("print_sorted");
    }

    @Override
    public boolean isValid() {
        return this.sourceGuard == null || this.sourceGuard.isValid();
    }

    @Override
    public ScanAvailability availability() {
        if (!this.isValid()) return ScanAvailability.COMPLETE;
        if (!this.queue.isEmpty()) {
            return ScanAvailability.READY;
        }
        return this.hasMoreSource ? ScanAvailability.PAUSED : ScanAvailability.COMPLETE;
    }

    @Override
    public boolean isBuffered() {
        return true;
    }

    public void requeue(BlockPos pos) {
        if (pos == null || this.queue.containsKey(ScanEngine.key(pos))) return;
        if (this.queue.isEmpty()) {
            this.queue.put(ScanEngine.key(pos), pos.immutable());
            this.insertionOrdered = true;
        } else {
            this.append(pos.immutable(), this.targetComparator());
        }
    }

    public void remove(BlockPos pos) {
        if (pos != null) {
            this.queue.remove(ScanEngine.key(pos));
            if (this.queue.size() <= 1) this.insertionOrdered = true;
        }
    }

    private boolean append(BlockPos pos, Comparator<BlockPos> order) {
        long key = ScanEngine.key(pos);
        if (this.queue.containsKey(key)) return false;
        if (this.insertionOrdered && !this.queue.isEmpty()
                && order.compare(this.queue.get(this.queue.lastLongKey()), pos) > 0) {
            this.insertionOrdered = false;
        }
        this.queue.put(key, pos);
        return true;
    }

    private void fill(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic,
                      LocalPlayer player, int scanGuardLimit, boolean fillWhenBuffered,
                      Predicate<BlockPos> candidateFilter) {
        if (!this.isValid()) {
            this.queue.clear();
            this.insertionOrdered = true;
            this.generation++;
            this.replacementGeneration++;
            this.hasMoreSource = true;
            this.lastFillTick = Long.MIN_VALUE;
        }
        // Observe schematic replacement even after a clean queue has exhausted its old source.
        // Creating the iterable does not consume a candidate or advance its cursor.
        Iterable<BlockPos> candidates = this.scanEngine.iterable(
                "print_sorted", sourceBoxes, level, schematic, player, scanGuardLimit,
                ScanIntent.PRINT, pos -> true, candidateFilter
        );
        this.sourceGuard = candidates instanceof ScanCandidateIterable scanSource ? scanSource : null;
        if (this.sourceGuard != null) {
            // Owner wakeups and due repair can reopen a completed source without changing the
            // world's dirty revision. Initial availability observes pending work without scanning.
            this.hasMoreSource = this.sourceGuard.availability() != ScanAvailability.COMPLETE;
        }
        long printSourceRevision = this.scanEngine.printSourceRevision();
        if (printSourceRevision != this.lastPrintSourceRevision) {
            this.queue.clear();
            this.insertionOrdered = true;
            this.generation++;
            this.replacementGeneration++;
            this.hasMoreSource = true;
            this.lastFillTick = Long.MIN_VALUE;
            this.lastPrintSourceRevision = printSourceRevision;
        }
        // Source identity changes are observed before returning old work, but ready work does
        // not wait for candidate production. The owner replenishes after its execution slice.
        if (!fillWhenBuffered && !this.queue.isEmpty()) return;
        long currentTick = level.getGameTime();
        long dirtyVersion = this.scanEngine.dirtyVersion();
        boolean dirtyChanged = dirtyVersion != this.lastDirtyVersion;
        int configuredThroughput = Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
        int targetBufferSize = configuredThroughput > 0
                ? Math.min(MAX_SORT_BUFFER, Math.max(256, configuredThroughput * 16))
                : MAX_SORT_BUFFER;
        int lowWater = Math.max(configuredThroughput * 4, 16);
        if (!shouldRefill(
                currentTick,
                this.lastFillTick,
                dirtyChanged,
                this.hasMoreSource,
                this.queue.size(),
                targetBufferSize,
                lowWater
        )) {
            return;
        }
        this.lastFillTick = currentTick;
        this.lastDirtyVersion = dirtyVersion;
        int remainingBuffer = Math.max(1, targetBufferSize - this.queue.size());
        if (!this.queue.isEmpty()) {
            int refillSlice = configuredThroughput > 0 ? Math.max(16, configuredThroughput * 2) : 256;
            remainingBuffer = Math.min(remainingBuffer, refillSlice);
        }
        int collectLimit = scanGuardLimit > 0
                ? Math.min(scanGuardLimit, remainingBuffer)
                : remainingBuffer;
        int collected = 0;
        Comparator<BlockPos> order = this.targetComparator();
        this.hasMoreSource = false;
        long collectionStart = this.scanEngine.timing().start();
        try {
            Iterator<BlockPos> iterator = candidates.iterator();
            // hasNext() can advance the scan session. Check capacity before even probing it,
            // so the first candidate of the next batch remains in the source cursor.
            while (collected < collectLimit && iterator.hasNext()) {
                BlockPos candidate = iterator.next();
                if (this.append(candidate.immutable(), order)) collected++;
            }
            this.hasMoreSource = collected >= collectLimit;
            if (candidates instanceof ScanCandidateIterable scanSource) {
                if (scanSource.availability() == ScanAvailability.PAUSED) this.hasMoreSource = true;
                else if (scanSource.availability() == ScanAvailability.COMPLETE) this.hasMoreSource = false;
            }
        } finally {
            this.scanEngine.timing().finish("print", Stage.PRINT_COLLECTION, collectionStart);
        }
        // The source already has construction order. The bounded iterator snapshot below also
        // merges newly discovered earlier ranks with positions retained from an older slice.
    }

    static boolean shouldRefill(
            long currentTick,
            long lastFillTick,
            boolean dirtyChanged,
            boolean hasMoreSource,
            int queueSize,
            int targetBufferSize,
            int lowWater
    ) {
        if (lastFillTick == currentTick || queueSize >= targetBufferSize) {
            return false;
        }
        if (!dirtyChanged && !hasMoreSource) {
            return false;
        }
        return dirtyChanged || queueSize < lowWater;
    }

    @Override
    public Iterator<BlockPos> iterator() {
        List<BlockPos> snapshot = new ArrayList<>(this.queue.values());
        ScanCandidateIterable guard = this.sourceGuard;
        long snapshotGeneration = this.generation;
        if (snapshot.size() > 1 && !this.insertionOrdered) {
            long sortStart = this.scanEngine.timing().start();
            try {
                snapshot.sort(this.targetComparator());
                // Reorder the same live membership once. Older iterators keep their own
                // finite snapshots; later monotone refills and removals need no repeat sort.
                this.queue.clear();
                for (BlockPos pos : snapshot) this.queue.put(ScanEngine.key(pos), pos);
                this.insertionOrdered = true;
            }
            finally { this.scanEngine.timing().finish("print", Stage.PRINT_SORT, sortStart); }
        }
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                if (snapshotGeneration != generation || guard != null && !guard.isValid()) return false;
                while (this.index < snapshot.size()
                        && !queue.containsKey(ScanEngine.key(snapshot.get(this.index)))) this.index++;
                return this.index < snapshot.size();
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new java.util.NoSuchElementException("sorted schematic target queue is exhausted");
                }
                BlockPos result = snapshot.get(this.index++);
                SortedSchematicTargetQueue.this.remove(result);
                return result;
            }
        };
    }
}
