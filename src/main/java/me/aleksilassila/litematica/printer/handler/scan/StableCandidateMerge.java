package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/** Merges a finite retry snapshot with an already ordered, resumable candidate source. */
public final class StableCandidateMerge {
    private StableCandidateMerge() { }

    public static ScanCandidateIterable merge(Collection<BlockPos> retries, Iterable<BlockPos> source,
                                               Comparator<BlockPos> order) {
        return merge(retries, source, order, pos -> { });
    }

    public static ScanCandidateIterable merge(Collection<BlockPos> retries, Iterable<BlockPos> source,
                                               Comparator<BlockPos> order, Consumer<BlockPos> retainFresh) {
        List<BlockPos> snapshot = new ArrayList<>(new LinkedHashSet<>(retries));
        snapshot.sort(order);
        LongOpenHashSet retryKeys = new LongOpenHashSet();
        snapshot.forEach(pos -> retryKeys.add(pos.asLong()));
        return new ScanCandidateIterable() {
            private int remaining = snapshot.size();
            private boolean preparedFresh;
            private boolean exhausted;

            @Override public boolean isValid() {
                return !(source instanceof ScanCandidateIterable candidates) || candidates.isValid();
            }

            @Override public ScanAvailability availability() {
                if (!this.isValid()) return ScanAvailability.COMPLETE;
                if (this.remaining > 0 || this.preparedFresh) return ScanAvailability.READY;
                return source instanceof ScanCandidateIterable candidates ? candidates.availability()
                        : this.exhausted ? ScanAvailability.COMPLETE : ScanAvailability.READY;
            }

            @Override public boolean isBuffered() {
                return source instanceof ScanCandidateIterable candidates && candidates.isBuffered();
            }

            @Override public Iterator<BlockPos> iterator() {
                this.remaining = snapshot.size();
                this.preparedFresh = false;
                this.exhausted = false;
                return new Iterator<>() {
                    private Iterator<BlockPos> fresh;
                    private final LongOpenHashSet emitted = new LongOpenHashSet();
                    private int retryIndex;
                    private BlockPos nextFresh;
                    private boolean freshFinished;

                    @Override public boolean hasNext() {
                        if (!isValid()) {
                            this.nextFresh = null;
                            remaining = 0;
                            preparedFresh = false;
                            exhausted = true;
                            return false;
                        }
                        // Only one source candidate is retained. A PAUSED source must not be
                        // drained/restarted to sort it, and retries created now belong to next pass.
                        if (this.fresh == null) this.fresh = source.iterator();
                        while (this.nextFresh == null && !this.freshFinished) {
                            if (!this.fresh.hasNext()) {
                                this.freshFinished = true;
                                break;
                            }
                            BlockPos candidate = this.fresh.next();
                            if (!retryKeys.contains(candidate.asLong()) && !this.emitted.contains(candidate.asLong())) {
                                this.nextFresh = candidate.immutable();
                                // Source cursors advance at lookahead. Preserve the offered cell
                                // in the owner's retry store if its action slice ends first.
                                retainFresh.accept(this.nextFresh);
                            }
                        }
                        preparedFresh = this.nextFresh != null;
                        exhausted = this.freshFinished;
                        return this.retryIndex < snapshot.size() || this.nextFresh != null;
                    }

                    @Override public BlockPos next() {
                        if (!this.hasNext()) throw new NoSuchElementException("stable candidate merge is exhausted");
                        if (this.retryIndex < snapshot.size()
                                && (this.nextFresh == null
                                    || order.compare(snapshot.get(this.retryIndex), this.nextFresh) <= 0)) {
                            remaining--;
                            BlockPos result = snapshot.get(this.retryIndex++);
                            this.emitted.add(result.asLong());
                            return result;
                        }
                        BlockPos result = this.nextFresh;
                        this.nextFresh = null;
                        preparedFresh = false;
                        this.emitted.add(result.asLong());
                        return result;
                    }
                };
            }
        };
    }
}
