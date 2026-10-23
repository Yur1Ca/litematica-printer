package me.aleksilassila.litematica.printer.core.scan;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Optional pure-coordinate comparison. Never supplies gameplay candidates or waits for a result.
 * Owns one lazy daemon worker, one waiting snapshot and one scalar completion; no world references.
 */
public final class ScanOrderShadow implements AutoCloseable {
    public enum Status { MATCH, MISMATCH, STALE, FAILED }

    public record Result(ScanGeneration generation, Status status, int positions,
                         long snapshotNanos, long queueNanos, long computeNanos, long readyNanos) { }

    private record Completion(ScanHandle handle, long lifecycle, Result result) { }

    private final ThreadPoolExecutor executor;
    private Completion completed;
    private long lifecycle;
    private long superseded;
    private long overwritten;
    private boolean closed;

    public ScanOrderShadow() {
        this(task -> {
            Thread thread = new Thread(task, "litematica-printer-scan-shadow");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ScanOrderShadow(ThreadFactory threadFactory) {
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Caller owns live scanning; replacing diagnostic work must never cancel its ScanHandle. */
    public synchronized boolean submit(ScanHandle handle, long[] input, OrderedPositions expected,
                                       int centerX, int centerY, int centerZ) {
        if (this.closed || handle.isCancelled()) return false;
        long start = System.nanoTime();
        long currentLifecycle = this.lifecycle;
        try {
            long[] snapshot = input.clone();
            long prepared = System.nanoTime();
            if (this.executor.getQueue().remainingCapacity() == 0
                    && this.executor.getQueue().poll() != null) this.superseded++;
            this.executor.execute(() -> this.compare(handle, currentLifecycle, snapshot, expected,
                    centerX, centerY, centerZ, start, prepared));
            return true;
        } catch (RuntimeException ignored) {
            // Optional diagnostics must not interrupt the caller's synchronous scan.
            this.publish(handle, currentLifecycle, new Result(handle.generation(), Status.FAILED,
                    input.length, 0L, 0L, 0L, System.nanoTime() - start));
            return false;
        }
    }

    private void compare(ScanHandle handle, long lifecycle, long[] input, OrderedPositions expected,
                         int centerX, int centerY, int centerZ, long start, long prepared) {
        long computing = System.nanoTime();
        Status status;
        try {
            if (handle.isCancelled()) {
                status = Status.STALE;
            } else {
                OrderedPositions actual = new OrderedPositions(input, centerX, centerY, centerZ);
                boolean equal = actual.size() == expected.size();
                for (int i = 0; equal && i < actual.size(); i++) {
                    equal = actual.positionAt(i) == expected.positionAt(i)
                            && actual.distanceAt(i) == expected.distanceAt(i);
                }
                status = equal ? Status.MATCH : Status.MISMATCH;
            }
        } catch (RuntimeException ignored) {
            status = Status.FAILED;
        }
        long ready = System.nanoTime();
        this.publish(handle, lifecycle, new Result(handle.generation(), status, input.length,
                prepared - start, computing - prepared, ready - computing, ready - start));
    }

    private synchronized void publish(ScanHandle handle, long lifecycle, Result result) {
        if (this.closed) return;
        if (this.completed != null) this.overwritten++;
        this.completed = new Completion(handle, lifecycle, result);
    }

    /** Called on the client thread. A completion is revalidated at consumption, not just publication. */
    public synchronized Result poll() {
        Completion completion = this.completed;
        this.completed = null;
        if (completion == null) return null;
        Result result = completion.result();
        if (completion.lifecycle() != this.lifecycle || !completion.handle().accepts(result.generation())) {
            return new Result(result.generation(), Status.STALE, result.positions(), result.snapshotNanos(),
                    result.queueNanos(), result.computeNanos(), result.readyNanos());
        }
        return result;
    }

    public synchronized long supersededRequests() { return this.superseded; }
    public synchronized long overwrittenResults() { return this.overwritten; }
    public int queuedRequests() { return this.executor.getQueue().size(); }

    /** World/cache reset drops queued snapshots; any running result carries the old lifecycle. */
    public synchronized void reset() {
        this.lifecycle++;
        this.executor.getQueue().clear();
        this.completed = null;
    }

    @Override public synchronized void close() {
        this.closed = true;
        this.reset();
        this.executor.shutdownNow();
    }
}
