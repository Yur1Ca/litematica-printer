package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Candidate stream with an explicit lifecycle status.
 *
 * <p>A budget pause is distinct from completion. Consumers can finish the currently
 * available candidates and inspect {@link #availability()} without rebuilding the
 * resumable cursor.</p>
 */
public interface ScanCandidateIterable extends Iterable<BlockPos> {
    ScanAvailability availability();

    /** False after the owner, source, chunk or runtime generation has been closed. */
    default boolean isValid() {
        return true;
    }

    /** Identify a still-owned source whose only invalidations were local chunk changes. */
    default boolean isScopeCurrent() { return this.isValid(); }

    /** Check a previously buffered coordinate against chunk replacements since this view was created. */
    default boolean isChunkCurrent(BlockPos pos) { return true; }

    /** True when the iterable is an already-built action queue, not a live world scan. */
    default boolean isBuffered() {
        return false;
    }

    /** Keeps ready work ahead of a lazy source without losing its budget or lifecycle contract. */
    static Iterable<BlockPos> prepend(List<BlockPos> prefix, Iterable<BlockPos> tail) {
        if (prefix.isEmpty()) return tail;
        List<BlockPos> priority = new ArrayList<>(new LinkedHashSet<>(prefix));
        return prepend(priority, priority.size(), tail);
    }

    /** Preserve a resumable ready round without probing its cold tail before the first action. */
    static ScanCandidateIterable prepend(ScanCandidateIterable ready, Iterable<BlockPos> tail) {
        return prepend(ready, 0, tail);
    }

    private static ScanCandidateIterable prepend(Iterable<BlockPos> priority, int prefixSize, Iterable<BlockPos> tail) {
        return new ScanCandidateIterable() {
            private int remaining = prefixSize;
            private boolean preparedTail;
            private boolean exhausted;

            @Override public boolean isValid() {
                return (!(priority instanceof ScanCandidateIterable ready) || ready.isValid())
                        && (!(tail instanceof ScanCandidateIterable source) || source.isValid());
            }

            @Override public ScanAvailability availability() {
                if (!this.isValid()) return ScanAvailability.COMPLETE;
                if (this.remaining > 0 || this.preparedTail
                        || priority instanceof ScanCandidateIterable ready && ready.availability() == ScanAvailability.READY)
                    return ScanAvailability.READY;
                return tail instanceof ScanCandidateIterable source ? source.availability()
                        : this.exhausted ? ScanAvailability.COMPLETE : ScanAvailability.READY;
            }

            @Override public boolean isBuffered() {
                return tail instanceof ScanCandidateIterable source && source.isBuffered();
            }

            @Override public Iterator<BlockPos> iterator() {
                this.remaining = prefixSize;
                this.preparedTail = false;
                this.exhausted = false;
                return new Iterator<>() {
                    private final Iterator<BlockPos> ready = priority.iterator();
                    private final LongOpenHashSet emitted = new LongOpenHashSet();
                    private Iterator<BlockPos> source;
                    private BlockPos next;

                    @Override public boolean hasNext() {
                        if (!isValid()) {
                            this.next = null;
                            remaining = 0;
                            exhausted = true;
                            return false;
                        }
                        if (this.ready.hasNext() || this.next != null) return true;
                        if (this.source == null) this.source = tail.iterator();
                        while (this.source.hasNext()) {
                            BlockPos pos = this.source.next();
                            if (this.emitted.add(pos.asLong())) {
                                this.next = pos;
                                preparedTail = true;
                                return true;
                            }
                        }
                        exhausted = true;
                        return false;
                    }

                    @Override public BlockPos next() {
                        if (!isValid()) throw new NoSuchElementException("candidate source was invalidated");
                        if (this.ready.hasNext()) {
                            BlockPos pos = this.ready.next();
                            this.emitted.add(pos.asLong());
                            if (remaining > 0) remaining--;
                            return pos;
                        }
                        if (!this.hasNext()) throw new NoSuchElementException("candidate stream is exhausted");
                        BlockPos pos = this.next;
                        this.next = null;
                        preparedTail = false;
                        return pos;
                    }
                };
            }
        };
    }
}
