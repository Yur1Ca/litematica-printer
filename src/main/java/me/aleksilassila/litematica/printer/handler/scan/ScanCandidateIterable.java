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

    /** True when the iterable is an already-built action queue, not a live world scan. */
    default boolean isBuffered() {
        return false;
    }

    /** Keeps ready work ahead of a lazy source without losing its budget or lifecycle contract. */
    static Iterable<BlockPos> prepend(List<BlockPos> prefix, Iterable<BlockPos> tail) {
        if (prefix.isEmpty()) return tail;
        List<BlockPos> priority = new ArrayList<>(new LinkedHashSet<>(prefix));
        return new ScanCandidateIterable() {
            private int remaining = priority.size();
            private boolean preparedTail;
            private boolean exhausted;

            @Override public ScanAvailability availability() {
                if (this.remaining > 0 || this.preparedTail) return ScanAvailability.READY;
                return tail instanceof ScanCandidateIterable source ? source.availability()
                        : this.exhausted ? ScanAvailability.COMPLETE : ScanAvailability.READY;
            }

            @Override public boolean isBuffered() {
                return tail instanceof ScanCandidateIterable source && source.isBuffered();
            }

            @Override public Iterator<BlockPos> iterator() {
                this.remaining = priority.size();
                this.preparedTail = false;
                this.exhausted = false;
                return new Iterator<>() {
                    private final Iterator<BlockPos> ready = priority.iterator();
                    private final LongOpenHashSet emitted = new LongOpenHashSet();
                    private Iterator<BlockPos> source;
                    private BlockPos next;

                    @Override public boolean hasNext() {
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
                        if (this.ready.hasNext()) {
                            BlockPos pos = this.ready.next();
                            this.emitted.add(pos.asLong());
                            remaining--;
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
