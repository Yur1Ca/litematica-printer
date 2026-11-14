package me.aleksilassila.litematica.printer.render;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Runtime-owned, bounded operation history. Rendering only receives immutable snapshots. */
public final class OperationHighlights implements RuntimeComponent {
    public static final int DEFAULT_CAPACITY = 1024;
    private final int capacity;
    private final LongSupplier clock;
    private final Map<BlockPos, Entry> history = new LinkedHashMap<>();
    private List<Preview> previews = List.of();

    public OperationHighlights() {
        this(DEFAULT_CAPACITY, () -> System.nanoTime() / 1_000_000L);
    }

    OperationHighlights(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("Highlight capacity must be positive");
        this.capacity = capacity;
        this.clock = clock;
    }

    public synchronized void record(BlockPos pos, HighlightType type) {
        BlockPos immutable = pos.immutable();
        this.history.remove(immutable);
        this.history.put(immutable, new Entry(immutable, type, this.clock.getAsLong()));
        while (this.history.size() > this.capacity) this.history.remove(this.history.keySet().iterator().next());
    }

    public long now() { return this.clock.getAsLong(); }

    /** Callback bridge; state itself remains owned by the installed runtime. */
    public static void emit(BlockPos pos, HighlightType type) {
        if (!me.aleksilassila.litematica.printer.config.Configs.Highlight.ENABLED.getBooleanValue()) return;
        var runtime = me.aleksilassila.litematica.printer.runtime.RuntimeAccess.currentOrNull();
        if (runtime != null && runtime.operationHighlights() != null) runtime.operationHighlights().record(pos, type);
    }

    public synchronized List<Entry> snapshot(long durationMillis) {
        long now = this.clock.getAsLong();
        this.history.values().removeIf(entry -> durationMillis <= 0 || now - entry.time() >= durationMillis);
        return List.copyOf(this.history.values());
    }

    public synchronized void setPreviews(List<Preview> next) {
        this.previews = next.stream().limit(8)
                .map(preview -> new Preview(preview.pos().immutable(), preview.knownTarget())).toList();
    }

    public synchronized List<Preview> previews() { return this.previews; }

    public synchronized void clear() {
        this.history.clear();
        this.previews = List.of();
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.clear(); }

    public record Entry(BlockPos pos, HighlightType type, long time) { }
    public record Preview(BlockPos pos, boolean knownTarget) { }
}
