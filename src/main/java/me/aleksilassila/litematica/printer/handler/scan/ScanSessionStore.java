package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.core.scan.ScanOrderShadow;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Owns scan sessions and their per-owner metrics, independent of budget policy. */
final class ScanSessionStore implements AutoCloseable {
    private final Map<String, SectionScanSession> sessions = new HashMap<>();
    private final Map<String, ScanMetricsAccumulator> metrics = new HashMap<>();
    private final SchematicBlockIndex schematicIndex;
    private final RuntimeTiming timing;
    private final ScanOrderShadow shadow;
    private long shadowSuperseded;
    private long shadowOverwritten;

    ScanSessionStore(SchematicBlockIndex schematicIndex) {
        this(schematicIndex, RuntimeTiming.disabled());
    }

    ScanSessionStore(SchematicBlockIndex schematicIndex, RuntimeTiming timing) {
        this(schematicIndex, timing, timing.enabled() && Boolean.getBoolean("litematica.printer.scanShadow")
                ? new ScanOrderShadow() : null);
    }

    ScanSessionStore(SchematicBlockIndex schematicIndex, RuntimeTiming timing, ScanOrderShadow shadow) {
        this.schematicIndex = schematicIndex;
        this.timing = timing;
        this.shadow = shadow;
    }

    void collectComparisons() {
        if (this.shadow == null) return;
        ScanOrderShadow.Result result = this.shadow.poll();
        if (result != null) {
            Reason reason = switch (result.status()) {
                case MATCH -> Reason.SHADOW_MATCHED;
                case MISMATCH -> Reason.SHADOW_MISMATCHED;
                case STALE -> Reason.SHADOW_STALE;
                case FAILED -> Reason.SHADOW_FAILED;
            };
            this.timing.count("scan-shadow", reason, 1);
            this.timing.recordElapsed("scan-shadow", Stage.SHADOW_SNAPSHOT, result.snapshotNanos());
            this.timing.recordElapsed("scan-shadow", Stage.SHADOW_QUEUE, result.queueNanos());
            this.timing.recordElapsed("scan-shadow", Stage.SHADOW_COMPUTE, result.computeNanos());
            this.timing.recordElapsed("scan-shadow", Stage.SHADOW_READY, result.readyNanos());
        }
        long superseded = this.shadow.supersededRequests(), overwritten = this.shadow.overwrittenResults();
        this.timing.count("scan-shadow", Reason.SHADOW_SUPERSEDED, superseded - this.shadowSuperseded);
        this.timing.count("scan-shadow", Reason.SHADOW_OVERWRITTEN, overwritten - this.shadowOverwritten);
        this.shadowSuperseded = superseded;
        this.shadowOverwritten = overwritten;
    }

    ScanMetricsAccumulator metrics(String ownerKey) {
        return this.metrics.computeIfAbsent(ownerKey, key -> new ScanMetricsAccumulator(this.timing, key));
    }

    ScanCache.ScanMetrics metricsFor(String ownerKey) {
        ScanMetricsAccumulator value = this.metrics.get(ownerKey);
        return value == null ? ScanCache.ScanMetrics.empty() : value.snapshot();
    }

    void resetMetrics() {
        for (ScanMetricsAccumulator value : this.metrics.values()) {
            value.reset();
        }
    }

    void invalidate(BlockPos pos) {
        for (SectionScanSession session : this.sessions.values()) {
            session.invalidate(pos);
        }
    }

    void invalidateChunk(int chunkX, int chunkZ) {
        Iterator<SectionScanSession> iterator = this.sessions.values().iterator();
        while (iterator.hasNext()) {
            SectionScanSession session = iterator.next();
            if (session.intersectsChunkAndNeighbors(chunkX, chunkZ)) {
                session.recordReason(Reason.CHUNK_REPLACED);
                session.close();
                iterator.remove();
            }
        }
    }

    void resetOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) return;
        String prefix = ownerKey + ":";
        Iterator<Map.Entry<String, SectionScanSession>> iterator = this.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SectionScanSession> entry = iterator.next();
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().recordReason(Reason.OWNER_RESET);
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    void resetIntent(ScanIntent intent) {
        Iterator<SectionScanSession> iterator = this.sessions.values().iterator();
        while (iterator.hasNext()) {
            SectionScanSession session = iterator.next();
            if (session.isFor(intent)) {
                session.recordReason(Reason.CHUNK_REPLACED);
                session.close();
                iterator.remove();
            }
        }
    }

    SectionScanSession getOrCreate(
            String ownerKey,
            ScanMetricsAccumulator metrics,
            ScanIntent intent,
            PrinterBox sourceBounds,
            List<PrinterBox> sourceBoxes,
            LocalPlayer player,
            RuntimeEpoch epoch,
            LongSupplier snapshotRevision,
            LongSupplier generationSequence
    ) {
        ScanRegion region = ScanRegion.from(sourceBounds, player);
        String key = ownerKey + ":" + intent.name();
        SectionScanSession session = this.sessions.get(key);
        if (session == null || !session.canReuse(region)) {
            metrics.timing.count(metrics.owner, session == null ? Reason.NEW_SESSION : Reason.REGION_REPLACED, 1);
            if (session != null) session.close();
            session = new SectionScanSession(
                    region,
                    sourceBoxes,
                    intent,
                    metrics,
                    epoch,
                    snapshotRevision,
                    generationSequence,
                    this.schematicIndex,
                    this.shadow
            );
            this.sessions.put(key, session);
        } else {
            session.updateRegion(region, sourceBoxes);
        }
        return session;
    }

    @Override
    public void close() {
        for (SectionScanSession session : this.sessions.values()) {
            session.close();
        }
        this.sessions.clear();
        if (this.shadow != null) this.shadow.reset();
    }

    void clearMetrics() {
        this.metrics.clear();
    }
}
