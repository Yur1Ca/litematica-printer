package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class SortedSchematicTargetQueue {
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private List<PrinterBox> boxes = List.of();
    private boolean hasMoreSource;

    public void clear() {
        this.queue.clear();
        this.boxes = List.of();
        this.hasMoreSource = false;
    }

    public Iterable<BlockPos> iterable(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
        if (!this.boxes.equals(sourceBoxes)) {
            this.queue.clear();
        }
        this.boxes = List.copyOf(sourceBoxes);
        this.fill(sourceBoxes, level, schematic, player, scanGuardLimit);
        return this::iterator;
    }

    private void fill(List<PrinterBox> sourceBoxes, ClientLevel level, WorldSchematic schematic, LocalPlayer player, int scanGuardLimit) {
<<<<<<< HEAD
        if (!this.queue.isEmpty()) {
            return;
        }
        int collectLimit = scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
        List<BlockPos> positions = new ArrayList<>();
        Set<Long> queuedKeys = new HashSet<>();
        this.hasMoreSource = false;
        Iterable<BlockPos> candidates = ScanCache.INSTANCE.iterable(
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
            if (candidate == null || positions.size() >= collectLimit) {
                this.hasMoreSource = true;
                break;
            }
            if (queuedKeys.add(ScanCache.key(candidate))) {
                positions.add(candidate);
=======
        int collectLimit = scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
        boolean previousHasMoreSource = this.hasMoreSource;
        List<BlockPos> positions = new ArrayList<>();
        Set<Long> queuedKeys = new HashSet<>();
        while (!this.queue.isEmpty()) {
            BlockPos queued = this.queue.removeFirst();
            if (!containsAny(sourceBoxes, queued)) {
                continue;
            }
            positions.add(queued);
            queuedKeys.add(ScanCache.key(queued));
        }
        this.hasMoreSource = positions.size() >= collectLimit && previousHasMoreSource;
        if (positions.size() < collectLimit) {
            Iterable<BlockPos> candidates = ScanCache.INSTANCE.iterable(
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
                if (candidate == null) {
                    this.hasMoreSource = true;
                    break;
                }
                if (positions.size() >= collectLimit) {
                    this.hasMoreSource = true;
                    break;
                }
                if (queuedKeys.add(ScanCache.key(candidate))) {
                    positions.add(candidate);
                }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
        }
        positions.sort(createComparator(schematic, player));
        this.queue.addAll(positions);
    }

    private static boolean containsAny(List<PrinterBox> boxes, BlockPos pos) {
        for (PrinterBox box : boxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private Iterator<BlockPos> iterator() {
        return new Iterator<>() {
            private boolean returnedSentinel;

            @Override
            public boolean hasNext() {
                return !queue.isEmpty() || hasMoreSource && !this.returnedSentinel;
            }

            @Override
            public BlockPos next() {
                if (!queue.isEmpty()) {
                    return queue.removeFirst();
                }
                this.returnedSentinel = true;
                return null;
            }
        };
    }

    private static Comparator<BlockPos> createComparator(WorldSchematic schematic, LocalPlayer player) {
        Item heldItem = player.getMainHandItem().getItem();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        return Comparator
                .comparing((BlockPos pos) -> !isHoldingRequiredItem(schematic, heldItem, pos))
                .thenComparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye))
                .thenComparingDouble(pos -> getViewAngleScore(eye, view, pos));
    }

    private static boolean isHoldingRequiredItem(WorldSchematic schematic, Item heldItem, BlockPos pos) {
        return schematic.getBlockState(pos).getBlock().asItem() == heldItem;
    }

    private static double getViewAngleScore(Vec3 eye, Vec3 view, BlockPos pos) {
        Vec3 toTarget = Vec3.atCenterOf(pos).subtract(eye);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        return -view.dot(toTarget.normalize());
    }
}
