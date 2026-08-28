package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiPredicate;

/** Retains modeled bedrock candidates until the controller accepts or invalidates them. */
final class BedrockCandidateBacklog<T> {
    private static final int DEFAULT_CAPACITY = 16;
    private final LinkedHashMap<BlockPos, T> entries = new LinkedHashMap<>();
    private int capacity;

    BedrockCandidateBacklog() {
        this(DEFAULT_CAPACITY);
    }

    BedrockCandidateBacklog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        while (this.entries.size() > this.capacity) {
            this.entries.remove(this.entries.keySet().iterator().next());
        }
    }

    boolean offer(BlockPos pos, T candidate) {
        if (pos == null || candidate == null) {
            return false;
        }
        BlockPos stablePos = pos.immutable();
        if (this.entries.containsKey(stablePos)) {
            this.entries.put(stablePos, candidate);
            return false;
        }
        if (this.entries.size() >= this.capacity) {
            return false;
        }
        this.entries.put(stablePos, candidate);
        return true;
    }

    boolean contains(BlockPos pos) {
        return pos != null && this.entries.containsKey(pos);
    }

    void remove(BlockPos pos) {
        if (pos != null) {
            this.entries.remove(pos);
        }
    }

    void removeIf(BiPredicate<BlockPos, T> predicate) {
        this.entries.entrySet().removeIf(entry -> predicate.test(entry.getKey(), entry.getValue()));
    }

    List<T> snapshot() {
        return List.copyOf(this.entries.values());
    }

    int remainingCapacity() {
        return Math.max(0, this.capacity - this.entries.size());
    }

    int size() {
        return this.entries.size();
    }

    boolean isEmpty() {
        return this.entries.isEmpty();
    }

    void clear() {
        this.entries.clear();
    }
}
