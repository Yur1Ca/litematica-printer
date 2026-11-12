package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps distant ownership while selecting local work without traversing the historical trail. */
final class BedrockCleanupQueue {
    private final Map<BlockPos, Long> positions = new LinkedHashMap<>();
    private final Map<BlockPos, Set<BlockPos>> sections = new HashMap<>();
    private long nextOrder;

    void clear() {
        this.positions.clear();
        this.sections.clear();
        this.nextOrder = 0;
    }

    boolean isEmpty() { return this.positions.isEmpty(); }
    int size() { return this.positions.size(); }
    boolean contains(BlockPos pos) { return this.positions.containsKey(pos); }
    long order(BlockPos pos) { return this.positions.getOrDefault(pos, Long.MAX_VALUE); }

    void add(BlockPos pos) {
        BlockPos stable = pos.immutable();
        if (this.positions.putIfAbsent(stable, this.nextOrder) != null) return;
        this.nextOrder++;
        this.sections.computeIfAbsent(section(stable), ignored -> new LinkedHashSet<>()).add(stable);
    }

    void remove(BlockPos pos) {
        if (this.positions.remove(pos) == null) return;
        BlockPos key = section(pos);
        Set<BlockPos> bucket = this.sections.get(key);
        bucket.remove(pos);
        if (bucket.isEmpty()) this.sections.remove(key);
    }

    List<BlockPos> nearby(Minecraft client) {
        if (client.player == null || this.positions.isEmpty()) return List.of();
        Bounds bounds = bounds(client);
        var result = new ArrayList<BlockPos>();
        if (bounds == null) {
            // A custom unrestricted shape with the interaction check disabled has no local window.
            this.collect(this.positions.keySet(), result);
            return result;
        }
        if (bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY() || bounds.minZ() > bounds.maxZ()) return result;
        int minX = sectionCoordinate(bounds.minX());
        int minY = sectionCoordinate(bounds.minY());
        int minZ = sectionCoordinate(bounds.minZ());
        int maxX = sectionCoordinate(bounds.maxX());
        int maxY = sectionCoordinate(bounds.maxY());
        int maxZ = sectionCoordinate(bounds.maxZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > this.sections.size()) {
            // A large configured range with sparse residue should not probe thousands of empty buckets.
            for (var entry : this.sections.entrySet()) {
                BlockPos section = entry.getKey();
                if (section.getX() >= minX && section.getX() <= maxX
                        && section.getY() >= minY && section.getY() <= maxY
                        && section.getZ() >= minZ && section.getZ() <= maxZ) this.collect(entry.getValue(), result);
            }
            return result;
        }
        var key = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Set<BlockPos> bucket = this.sections.get(key.set(x, y, z));
                    if (bucket != null) this.collect(bucket, result);
                }
            }
        }
        return result;
    }

    private void collect(Iterable<BlockPos> source, List<BlockPos> result) {
        for (BlockPos pos : source) {
            // Bucket bounds are deliberately conservative; the existing policy owns exact reach.
            if (BedrockEnvironment.canInteract(pos)) result.add(pos);
        }
    }

    private static Bounds bounds(Minecraft client) {
        var player = client.player;
        var eye = player.getEyePosition();
        Bounds bounds = null;
        if (Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType shape) {
            double range = ConfigUtils.getWorkRange();
            if (shape == RadiusShapeType.SPHERE) {
                bounds = Bounds.around(eye.x - 0.5D, eye.y - 0.5D, eye.z - 0.5D, range);
            } else {
                BlockPos center = player.blockPosition();
                bounds = Bounds.around(center.getX(), center.getY(), center.getZ(), range);
            }
        }
        if (Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()) {
            double range = PlayerUtils.getPlayerBlockInteractionRange(5.0D) + 1.0D;
            // Vanilla reach measures the nearest point of the unit block, not its center.
            var interaction = new Bounds(eye.x - range - 1, eye.y - range - 1, eye.z - range - 1,
                    eye.x + range, eye.y + range, eye.z + range);
            bounds = bounds == null ? interaction : bounds.intersect(interaction);
        }
        return bounds;
    }

    private static BlockPos section(BlockPos pos) {
        return new BlockPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private static int sectionCoordinate(double coordinate) { return (int) Math.floor(coordinate) >> 4; }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private static Bounds around(double x, double y, double z, double range) {
            return new Bounds(x - range, y - range, z - range, x + range, y + range, z + range);
        }

        private Bounds intersect(Bounds other) {
            return new Bounds(Math.max(this.minX, other.minX), Math.max(this.minY, other.minY), Math.max(this.minZ, other.minZ),
                    Math.min(this.maxX, other.maxX), Math.min(this.maxY, other.maxY), Math.min(this.maxZ, other.maxZ));
        }
    }
}
