package me.aleksilassila.litematica.printer.handler.scan;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;

/** Bounded temporary delivery ownership; eviction does not complete cached world work. */
public final class BoundedTargetSet extends AbstractSet<BlockPos> {
    public static final int DEFAULT_CAPACITY = 1024;
    private final int capacity;
    private final LinkedHashSet<BlockPos> targets = new LinkedHashSet<>();
    private final Consumer<BlockPos> onRemoval;
    private final Consumer<BlockPos> onEviction;

    public BoundedTargetSet() { this(DEFAULT_CAPACITY); }

    public BoundedTargetSet(int capacity) { this(capacity, null); }

    public BoundedTargetSet(int capacity, Consumer<BlockPos> onRemoval) {
        this(capacity, onRemoval, null);
    }

    public BoundedTargetSet(int capacity, Consumer<BlockPos> onRemoval, Consumer<BlockPos> onEviction) {
        if (capacity < 1) throw new IllegalArgumentException("target capacity must be positive");
        this.capacity = capacity;
        this.onRemoval = onRemoval;
        this.onEviction = onEviction;
    }

    @Override public boolean add(BlockPos pos) {
        if (!this.targets.add(Objects.requireNonNull(pos).immutable())) return false;
        if (this.targets.size() > this.capacity) {
            Iterator<BlockPos> oldest = this.targets.iterator();
            BlockPos removed = oldest.next();
            oldest.remove();
            if (this.onRemoval != null) this.onRemoval.accept(removed);
            if (this.onEviction != null) this.onEviction.accept(removed);
        }
        return true;
    }

    @Override public boolean contains(Object pos) { return this.targets.contains(pos); }
    @Override public boolean remove(Object pos) {
        if (!this.targets.remove(pos)) return false;
        if (this.onRemoval != null) this.onRemoval.accept((BlockPos) pos);
        return true;
    }
    @Override public Iterator<BlockPos> iterator() {
        Iterator<BlockPos> iterator = this.targets.iterator();
        if (this.onRemoval == null) return iterator;
        return new Iterator<>() {
            private BlockPos current;
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public BlockPos next() { return this.current = iterator.next(); }
            @Override public void remove() {
                iterator.remove();
                onRemoval.accept(this.current);
            }
        };
    }
    @Override public int size() { return this.targets.size(); }
    @Override public void clear() {
        if (this.onRemoval != null) this.targets.forEach(this.onRemoval);
        this.targets.clear();
    }
}
