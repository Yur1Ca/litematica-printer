package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class SortedSchematicTargetQueue implements ScanCandidateIterable {
    private final ScanEngine scanEngine;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final LongSet queuedKeys = new LongOpenHashSet();
    private List<PrinterBox> boxes = List.of();
    private boolean hasMoreSource;
    private long lastFillTick = Long.MIN_VALUE;
    private long lastDirtyVersion = Long.MIN_VALUE;

    public SortedSchematicTargetQueue(ScanEngine scanEngine) {
        this.scanEngine = scanEngine;
    }

    public void clear() {
        this.queue.clear();
        this.queuedKeys.clear();
        this.boxes = List.of();
        this.hasMoreSource = false;
        this.lastFillTick = Long.MIN_VALUE;
        this.lastDirtyVersion = Long.MIN_VALUE;
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        if (!this.boxes.equals(sourceBoxes)) {
            this.queue.clear();
            this.queuedKeys.clear();
            this.hasMoreSource = true;
            this.lastFillTick = Long.MIN_VALUE;
        }
        this.boxes = List.copyOf(sourceBoxes);
        this.fill(sourceBoxes, level, schematic, player, scanGuardLimit);
        return this;
    }

    public boolean hasPendingWork() {
        return !this.queue.isEmpty() || this.hasMoreSource;
    }

    @Override
    public ScanAvailability availability() {
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
        if (pos == null || this.queuedKeys.add(ScanEngine.key(pos))) {
            if (pos != null) {
                this.queue.addLast(pos.immutable());
            }
        }
    }

    public void remove(BlockPos pos) {
        if (pos == null) return;
        long key = ScanEngine.key(pos);
        this.queuedKeys.remove(key);
        this.queue.removeIf(candidate -> ScanEngine.key(candidate) == key);
    }

    private void fill(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        // Do not refill while a batch is still available. The scan budget is shared with the
        // iteration runner; scanning again every tick while the queue is non-empty consumes the
        // whole budget before placement starts, which turns the sorted path into a low-throughput
        // printer. Refill only after the current batch has been consumed.
        if (!this.queue.isEmpty()) {
            return;
        }
        long currentTick = level.getGameTime();
        long dirtyVersion = this.scanEngine.dirtyVersion();
        boolean dirtyChanged = dirtyVersion != this.lastDirtyVersion;
        int configuredThroughput = Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
        int targetBufferSize = configuredThroughput > 0
                ? Math.max(256, configuredThroughput * 16)
                : Integer.MAX_VALUE;
        if (this.lastFillTick == currentTick
                || !dirtyChanged && !this.hasMoreSource && this.queue.isEmpty()
                || !dirtyChanged && this.queue.size() >= targetBufferSize) {
            return;
        }
        this.lastFillTick = currentTick;
        this.lastDirtyVersion = dirtyVersion;
        int remainingBuffer = Math.max(1, targetBufferSize - this.queue.size());
        int collectLimit = scanGuardLimit > 0
                ? Math.min(scanGuardLimit, remainingBuffer)
                : remainingBuffer;
        Item heldItem = player.getMainHandItem().getItem();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        List<TargetScore> targets = new ArrayList<>();
        this.hasMoreSource = false;
        Iterable<BlockPos> candidates = this.scanEngine.iterable(
                "print_sorted",
                sourceBoxes,
                level,
                schematic,
                player,
                scanGuardLimit,
                ScanIntent.PRINT,
                pos -> true
        );
        for (BlockPos candidate : candidates) {
            if (targets.size() >= collectLimit) {
                this.hasMoreSource = true;
                break;
            }
            if (this.queuedKeys.add(ScanEngine.key(candidate))) {
                targets.add(scoreTarget(schematic, heldItem, eye, view, candidate));
            }
        }
        if (candidates instanceof ScanCandidateIterable scanSource
                && scanSource.availability() == ScanAvailability.PAUSED) {
            this.hasMoreSource = true;
        }
        targets.sort(TargetScore.COMPARATOR);
        for (TargetScore target : targets) {
            this.queue.addLast(target.pos());
        }
    }

    @Override
    public Iterator<BlockPos> iterator() {
        return new Iterator<>() {
            // Retry insertions belong to the next iteration pass. Reading the live queue until it
            // becomes empty lets a failed target remove and requeue itself forever, especially
            // when placeBlocksPerTick=0 disables the effective-execution limit.
            private int remaining = queue.size();

            @Override
            public boolean hasNext() {
                return this.remaining > 0 && !queue.isEmpty();
            }

            @Override
            public BlockPos next() {
                if (this.remaining > 0 && !queue.isEmpty()) {
                    this.remaining--;
                    BlockPos result = queue.removeFirst();
                    queuedKeys.remove(ScanEngine.key(result));
                    return result;
                }
                throw new java.util.NoSuchElementException("sorted schematic target queue is exhausted");
            }
        };
    }

    private static TargetScore scoreTarget(
            WorldSchematic schematic,
            Item heldItem,
            Vec3 eye,
            Vec3 view,
            BlockPos pos
    ) {
        double dx = pos.getX() + 0.5D - eye.x;
        double dy = pos.getY() + 0.5D - eye.y;
        double dz = pos.getZ() + 0.5D - eye.z;
        double distanceSqr = dx * dx + dy * dy + dz * dz;
        double viewAngleScore = distanceSqr < 1.0E-6D
                ? 0.0D
                : -(view.x * dx + view.y * dy + view.z * dz) / Math.sqrt(distanceSqr);
        BlockState requiredState = schematic.getBlockState(pos);
        return new TargetScore(
                pos,
                requiredState.getBlock().asItem() != heldItem,
                requiredState.getBlock() instanceof FallingBlock,
                pos.getY(),
                distanceSqr,
                viewAngleScore
        );
    }

    static record TargetScore(
            BlockPos pos,
            boolean heldItemMismatch,
            boolean fallingBlock,
            int y,
            double distanceSqr,
            double viewAngleScore
    ) {
        /**
         * A strict total order is required here: TimSort is allowed to reject a comparator whose
         * ordering changes depending on the pair being compared.  In particular, comparing two
         * falling blocks by Y but a falling/non-falling pair by distance is not transitive.
         *
         * <p>Keep material locality first, let ordinary placements make progress before a falling
         * dependency in the same material group, and order falling blocks bottom-up. Coordinates
         * are the deterministic final tie-breaker so incremental batches produce the same order.</p>
         */
        static final Comparator<TargetScore> COMPARATOR = Comparator
                .comparing(TargetScore::heldItemMismatch)
                .thenComparing(TargetScore::fallingBlock)
                .thenComparingInt(score -> score.fallingBlock ? score.y : 0)
                .thenComparingDouble(TargetScore::distanceSqr)
                .thenComparingDouble(TargetScore::viewAngleScore)
                .thenComparingInt(score -> score.pos.getY())
                .thenComparingInt(score -> score.pos.getX())
                .thenComparingInt(score -> score.pos.getZ());
    }
}
