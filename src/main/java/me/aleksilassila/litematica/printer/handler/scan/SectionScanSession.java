package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Stage;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason;
import me.aleksilassila.litematica.printer.core.scan.ScanGeneration;
import me.aleksilassila.litematica.printer.core.scan.ScanHandle;
import me.aleksilassila.litematica.printer.core.scan.ScanOrderShadow;
import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.LongSupplier;

/**
 * Owns resumable construction work and section repair for one scan owner.
 *
 * <p>The cache decides when a session may run and how much time it receives. This class only
 * owns traversal progress, invalidated positions and live world/schematic classification.</p>
 */
final class SectionScanSession {
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int MAX_DIRTY_POSITIONS = 16_384;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ScanIntent intent;
    private final ScanMetricsAccumulator metrics;
    private ScanRegion region;
    private List<PrinterBox> sourceBoxes;
    private PositionCursor distanceCursor;
    private final RuntimeEpoch epoch;
    private final LongSupplier snapshotRevision;
    private final LongSupplier generationSequence;
    private final SchematicBlockIndex schematicIndex;
    private final ScanOrderShadow shadow;
    private final SchematicPositionQuery printCandidates;
    private final SectionSnapshotStore snapshots;
    private final ConstructionOrder constructionOrder;
    private long eligibilityRevision = Long.MIN_VALUE;
    private long nextRepairTick = Long.MAX_VALUE;
    private SectionCoverageCursor repairCoverage;
    private final BlockPos.MutableBlockPos repairPosition = new BlockPos.MutableBlockPos();
    private List<PrinterBox> repairBoxes;
    private boolean cursorPrepared;
    private final LongSet offeredDirtyPositions = new DirtyPositionKeys();
    private final LinkedHashSet<BlockPos> pendingReoffers = new LinkedHashSet<>();
    private long deliveryGeneration;
    private boolean deferredDirtyRescan;
    private final BlockPos.MutableBlockPos cursorPosition = new BlockPos.MutableBlockPos();
    private long sourceRevision;
    private long cursorRevision;
    private long exhaustedUntilTick = Long.MIN_VALUE;
    private final PriorityQueue<ScanDirtyPosition> dirtyPositions;
    private final LongSet dirtyPositionKeys = new DirtyPositionKeys();
    private boolean paused;
    private boolean closed;
    private ScanHandle scanHandle;
    private final BlockPos.MutableBlockPos liveMutable = new BlockPos.MutableBlockPos();
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private boolean lastChunkLoaded;
    private final ChunkCandidateCache chunkCandidateCache = new ChunkCandidateCache();

    SectionScanSession(
            ScanRegion region,
            List<PrinterBox> sourceBoxes,
            ScanIntent intent,
            ScanMetricsAccumulator metrics,
            RuntimeEpoch epoch,
            LongSupplier snapshotRevision,
            LongSupplier generationSequence,
            SchematicBlockIndex schematicIndex
    ) {
        this(region, sourceBoxes, intent, metrics, epoch, snapshotRevision, generationSequence, schematicIndex, null);
    }

    SectionScanSession(ScanRegion region, List<PrinterBox> sourceBoxes, ScanIntent intent,
                       ScanMetricsAccumulator metrics, RuntimeEpoch epoch, LongSupplier snapshotRevision,
                       LongSupplier generationSequence, SchematicBlockIndex schematicIndex, ScanOrderShadow shadow) {
        this(region, sourceBoxes, intent, metrics, epoch, snapshotRevision, generationSequence,
                schematicIndex, shadow, schematicIndex);
    }

    SectionScanSession(ScanRegion region, List<PrinterBox> sourceBoxes, ScanIntent intent,
                       ScanMetricsAccumulator metrics, RuntimeEpoch epoch, LongSupplier snapshotRevision,
                       LongSupplier generationSequence, SchematicBlockIndex schematicIndex, ScanOrderShadow shadow,
                       SchematicPositionQuery printCandidates) {
        this(region, sourceBoxes, intent, metrics, epoch, snapshotRevision, generationSequence,
                schematicIndex, shadow, printCandidates, null, null);
    }

    SectionScanSession(ScanRegion region, List<PrinterBox> sourceBoxes, ScanIntent intent,
                        ScanMetricsAccumulator metrics, RuntimeEpoch epoch, LongSupplier snapshotRevision,
                        LongSupplier generationSequence, SchematicBlockIndex schematicIndex, ScanOrderShadow shadow,
                        SchematicPositionQuery printCandidates, SectionSnapshotStore snapshots,
                        ConstructionOrder constructionOrder) {
        this.region = region;
        this.sourceBoxes = List.copyOf(sourceBoxes);
        this.intent = intent;
        this.metrics = metrics;
        this.epoch = epoch;
        this.snapshotRevision = snapshotRevision;
        this.generationSequence = generationSequence;
        this.schematicIndex = schematicIndex;
        this.shadow = shadow;
        this.printCandidates = printCandidates;
        this.snapshots = snapshots;
        this.constructionOrder = constructionOrder;
        if (snapshots != null) this.repairCoverage = new SectionCoverageCursor(this.sourceBoxes);
        this.dirtyPositions = snapshots == null ? new PriorityQueue<>()
                : new PriorityQueue<>((first, second) -> constructionOrder.compare(
                        first.pos().asLong(), second.pos().asLong()));
        this.scanHandle = this.createScanHandle();
        this.distanceCursor = this.createDistanceCursor();
    }

    SectionScanSession(
            ScanRegion region,
            List<PrinterBox> sourceBoxes,
            ScanIntent intent,
            ScanMetricsAccumulator metrics
    ) {
        this(region, sourceBoxes, intent, metrics, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L, null);
    }

    boolean isFor(ScanIntent intent) {
        return this.intent == intent;
    }

    boolean canReuse(ScanRegion region) {
        return this.snapshots != null || this.region.sameSectionWindow(region)
                && this.region.maxDistanceBand() == region.maxDistanceBand();
    }

    void updateRegion(ScanRegion region, List<PrinterBox> sourceBoxes) {
        boolean boxesChanged = !this.sourceBoxes.equals(sourceBoxes);
        boolean centerChanged = this.region.centerX() != region.centerX()
                || this.region.centerY() != region.centerY() || this.region.centerZ() != region.centerZ();
        boolean bandChanged = this.region.maxDistanceBand() != region.maxDistanceBand();
        // Compare the section window (16-block granularity), not the exact center. The center
        // follows the player's block position; bumping the revision for every single block of
        // movement made finishPass() rebuild the cursor from scratch on every pass, which
        // showed up as a permanent full rescan in the HUD. Within a section the in-flight
        // cursor is reused and fine center movement only shifts the reach shape slightly.
        boolean windowChanged = !this.region.sameSectionWindow(region);
        this.region = region;
        if (boxesChanged) {
            this.sourceBoxes = List.copyOf(sourceBoxes);
            this.pendingReoffers.removeIf(pos -> !ScanGeometry.containsAny(this.sourceBoxes, pos));
            if (this.snapshots != null) this.repairCoverage = new SectionCoverageCursor(this.sourceBoxes);
        }
        if (boxesChanged || windowChanged || this.snapshots != null && (centerChanged || bandChanged)) {
            this.sourceRevision++;
        }
        if (this.snapshots != null && this.exhaustedUntilTick == Long.MAX_VALUE
                && this.cursorRevision != this.sourceRevision) {
            this.resetProgress();
        }
        // Keep the active cursor while the section window remains stable. Rebuilding it for every
        // block of player movement repeatedly walks the already-scanned prefix and starves the
        // edge of large ranges. A revision check at the end of this pass schedules exactly one
        // follow-up pass over the latest center and boxes before completion is reported.
    }

    void updateEligibility(long revision) {
        if (this.snapshots == null || this.eligibilityRevision == revision) return;
        if (this.eligibilityRevision != Long.MIN_VALUE) {
            this.sourceRevision++;
            if (this.exhaustedUntilTick == Long.MAX_VALUE) this.resetProgress();
        }
        this.eligibilityRevision = revision;
    }

    boolean canScan(long tickTime) {
        if (this.snapshots != null) this.paused = false;
        if (this.closed) {
            return false;
        }
        if (this.exhaustedUntilTick == Long.MIN_VALUE) {
            return true;
        }
        if (this.snapshots != null && this.exhaustedUntilTick == Long.MAX_VALUE) {
            if (tickTime >= this.nextRepairTick && this.startRepair(tickTime)) return true;
            // Reoffers belong to a new delivery iterator, never to the one that evicted them.
            if (!this.pendingReoffers.isEmpty()) this.paused = true;
            return false;
        }
        if (tickTime < this.exhaustedUntilTick) {
            return false;
        }
        this.resetProgress();
        return true;
    }

    private void resetProgress() {
        this.recordReason(Reason.PASS_RESTART);
        this.repairBoxes = null;
        this.rebuildDistanceCursor();
        this.dirtyPositions.clear();
        this.dirtyPositionKeys.clear();
    }

    private void rebuildDistanceCursor() {
        this.distanceCursor.close();
        this.scanHandle.close();
        this.scanHandle = this.createScanHandle();
        this.distanceCursor = this.createDistanceCursor();
        this.cursorRevision = this.sourceRevision;
        this.exhaustedUntilTick = Long.MIN_VALUE;
        this.lastChunkX = Integer.MIN_VALUE;
        this.lastChunkZ = Integer.MIN_VALUE;
        this.lastChunkLoaded = false;
        this.cursorPrepared = false;
        this.offeredDirtyPositions.clear();
        this.deferredDirtyRescan = false;
    }

    private PositionCursor createDistanceCursor() {
        this.recordReason(Reason.CURSOR_CREATED);
        long cursorStart = this.metrics.timing.start();
        try {
            if (this.snapshots != null) {
                return new ConstructionPositionCursor(this.repairBoxes == null ? this.sourceBoxes : this.repairBoxes,
                        this.region, this.constructionOrder);
            }
            if (this.intent == ScanIntent.PRINT
                    && !Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                    && this.schematicIndex != null
                    && this.schematicIndex.isReady()) {
                return new IndexedPositionCursor(this.printCandidates, this.sourceBoxes, this.region,
                        this.metrics.timing, this.metrics.owner, this.shadow, this.scanHandle);
            }
            return new SynchronousPositionCursor(
                    this.sourceBoxes,
                    this.region.centerX(),
                    this.region.centerY(),
                    this.region.centerZ(),
                    this.region.maxDistanceBand()
            );
        } finally {
            this.metrics.timing.finish(this.metrics.owner, Stage.CURSOR_BUILD, cursorStart);
        }
    }

    private boolean startRepair(long tickTime) {
        SectionCoverageCursor.PollResult result = this.repairCoverage.poll(this.repairPosition, 64);
        if (result == SectionCoverageCursor.PollResult.COMPLETE) {
            this.repairCoverage.reset();
            result = this.repairCoverage.poll(this.repairPosition, 64);
        }
        if (result == SectionCoverageCursor.PollResult.PAUSED) {
            this.paused = true;
            this.nextRepairTick = tickTime + 1;
            return false;
        }
        if (result != SectionCoverageCursor.PollResult.AVAILABLE) {
            this.nextRepairTick = tickTime + 20;
            return false;
        }
        int sectionX = this.repairPosition.getX(), sectionY = this.repairPosition.getY(), sectionZ = this.repairPosition.getZ();
        long key = ScanGeometry.sectionKey(sectionX, sectionY, sectionZ);
        List<PrinterBox> boxes = new ArrayList<>();
        int minX = sectionX << 4, minY = sectionY << 4, minZ = sectionZ << 4;
        for (PrinterBox box : this.sourceBoxes) {
            int x1 = Math.max(minX, box.minX), y1 = Math.max(minY, box.minY), z1 = Math.max(minZ, box.minZ);
            int x2 = Math.min(minX + 15, box.maxX), y2 = Math.min(minY + 15, box.maxY), z2 = Math.min(minZ + 15, box.maxZ);
            if (x1 <= x2 && y1 <= y2 && z1 <= z2) boxes.add(new PrinterBox(x1, y1, z1, x2, y2, z2));
        }
        this.snapshots.invalidateSection(key, this.intent, Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue());
        this.chunkCandidateCache.invalidate(sectionX, sectionZ);
        this.repairBoxes = List.copyOf(boxes);
        this.rebuildDistanceCursor();
        this.nextRepairTick = tickTime + 20;
        return true;
    }

    void recordReason(Reason reason) { this.metrics.timing.count(this.metrics.owner, reason, 1); }

    boolean hasPendingSource(long tickTime) {
        if (this.snapshots != null) {
            if (this.closed) return false;
            if (!this.pendingReoffers.isEmpty()) return true;
            if (this.exhaustedUntilTick == Long.MAX_VALUE) {
                return tickTime >= this.nextRepairTick;
            }
            return this.cursorRevision != this.sourceRevision || this.deferredDirtyRescan || this.cursorPrepared
                    || !this.dirtyPositions.isEmpty() || !this.distanceCursor.isComplete();
        }
        return this.canScan(tickTime)
                && (this.cursorRevision != this.sourceRevision
                || !this.dirtyPositions.isEmpty() || !this.distanceCursor.isComplete());
    }

    boolean wasPaused() {
        return this.paused;
    }

    boolean belongsTo(RuntimeEpoch epoch) {
        return !this.closed && this.epoch.equals(epoch) && this.scanHandle.accepts(this.scanHandle.generation());
    }

    long sourceRevision() { return this.sourceRevision; }

    boolean contains(BlockPos pos) {
        return ScanGeometry.containsAny(this.sourceBoxes, pos);
    }

    boolean intersectsChunkAndNeighbors(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        for (PrinterBox box : this.sourceBoxes) {
            boolean overlapsX = box.minX <= maxX && box.maxX >= minX;
            boolean overlapsZ = box.minZ <= maxZ && box.maxZ >= minZ;
            if ((overlapsX && box.minZ <= maxZ + 1 && box.maxZ >= minZ - 1)
                    || (overlapsZ && box.minX <= maxX + 1 && box.maxX >= minX - 1)) {
                return true;
            }
        }
        return false;
    }

    void invalidate(BlockPos pos) {
        this.chunkCandidateCache.invalidate(
                ScanGeometry.sectionCoord(pos.getX()),
                ScanGeometry.sectionCoord(pos.getZ())
        );
        if (this.lastChunkX == ScanGeometry.sectionCoord(pos.getX())
                && this.lastChunkZ == ScanGeometry.sectionCoord(pos.getZ())) this.lastChunkX = Integer.MIN_VALUE;
        this.addDirtyPosition(pos);
        if (this.intent == ScanIntent.FILL
                || this.intent == ScanIntent.PRINT
                || this.intent == ScanIntent.BEDROCK) {
            // Support, waterlogging and bedrock-machine exposure are neighbor-dependent.
            // Revisit exactly the six affected targets rather than restarting the selection.
            // Fluid does not need this expansion because flowing liquid already produces the
            // neighboring updates it needs.
            for (Direction direction : DIRECTIONS) {
                this.addDirtyPosition(pos.relative(direction));
            }
        }
        this.paused = false;
    }

    /** Stages unowned deliveries beyond the current iterator's duplicate-suppression lifetime. */
    void reoffer(BlockPos pos) {
        if (this.closed || pos == null || !ScanGeometry.containsAny(this.sourceBoxes, pos)) return;
        if (this.pendingReoffers.size() < MAX_DIRTY_POSITIONS) {
            this.pendingReoffers.add(pos.immutable());
        } else if (!this.pendingReoffers.contains(pos)) {
            // Invalidate this delivery stream so overflow recovery starts in another iterator.
            // A finite full follow-up covers the unretained key without invalidating facts.
            this.sourceRevision++;
            this.deferredDirtyRescan = true;
            this.exhaustedUntilTick = Long.MIN_VALUE;
        }
        this.paused = false;
    }

    long beginDeliveryIterator() {
        int remaining = Configs.Core.WORK_PER_TICK.getIntegerValue();
        for (Iterator<BlockPos> iterator = this.pendingReoffers.iterator(); remaining > 0 && iterator.hasNext(); remaining--) {
            BlockPos pos = iterator.next();
            iterator.remove();
            // An explicit ownership release starts a new delivery lifetime. Unlike a repeated
            // world notification, it must not wait for the unscanned tail of this pass.
            this.offeredDirtyPositions.remove(ScanCache.key(pos));
            this.addDirtyPosition(pos);
        }
        return ++this.deliveryGeneration;
    }

    boolean acceptsDeliveryIterator(long generation) { return this.deliveryGeneration == generation; }

    private void addDirtyPosition(BlockPos pos) {
        if (pos == null || !ScanGeometry.containsAny(this.sourceBoxes, pos)) {
            return;
        }
        this.exhaustedUntilTick = Long.MIN_VALUE;
        long key = ScanCache.key(pos);
        if (this.snapshots != null && (this.offeredDirtyPositions.contains(key)
                || this.offeredDirtyPositions.size() >= MAX_DIRTY_POSITIONS)) {
            // Repeated notifications invalidate shared facts immediately, but may only
            // offer this position again after the finite current pass advances to its end.
            this.deferredDirtyRescan = true;
            return;
        }
        if (this.snapshots != null && this.dirtyPositionKeys.size() >= MAX_DIRTY_POSITIONS
                && !this.dirtyPositionKeys.contains(key)) {
            // A burst becomes one resumable coverage revision; shared facts were invalidated
            // by the event router, so dropping individual offers cannot mark them complete.
            this.dirtyPositions.clear();
            this.dirtyPositionKeys.clear();
            this.sourceRevision++;
        }
        if (this.dirtyPositionKeys.add(key)) {
            this.dirtyPositions.add(new ScanDirtyPosition(
                    pos.immutable(),
                    ScanGeometry.distanceSqr(pos, this.region.centerX(), this.region.centerY(), this.region.centerZ())
            ));
        }
    }

    Candidate next(
            WorldObservationPort observation,
            long tickTime,
            BooleanSupplier shouldPause,
            Predicate<BlockPos> preFilter,
            boolean unbounded
    ) {
        return this.next(observation, tickTime, shouldPause, preFilter, unbounded, () -> true);
    }

    Candidate next(
            WorldObservationPort observation,
            long tickTime,
            BooleanSupplier shouldPause,
            Predicate<BlockPos> preFilter,
            boolean unbounded,
            BooleanSupplier tryAdvance
    ) {
        this.paused = false;
        if (!this.canScan(tickTime)) {
            return null;
        }
        return this.nextByPlayerDistance(observation, tickTime, shouldPause, preFilter, unbounded, tryAdvance);
    }

    BlockPos peekPosition() {
        if (this.closed || !this.pendingReoffers.isEmpty()) return null;
        BlockPos next = this.cursorPrepared ? this.cursorPosition.immutable() : this.distanceCursor.peekPosition();
        ScanDirtyPosition dirty = this.dirtyPositions.peek();
        if (dirty != null && (next == null
                || this.constructionOrder.compare(dirty.pos().asLong(), next.asLong()) <= 0)) {
            next = dirty.pos();
        }
        return next != null && this.contains(next) ? next.immutable() : null;
    }

    private Candidate nextByPlayerDistance(
            WorldObservationPort observation,
            long tickTime,
            BooleanSupplier shouldPause,
            Predicate<BlockPos> preFilter,
            boolean unbounded,
            BooleanSupplier tryAdvance
    ) {
        if (observation == null) {
            this.cursorRevision = this.sourceRevision;
            this.finishPass(tickTime);
            return null;
        }
        int scanned = 0;
        while (true) {
            if (!unbounded
                    && scanned > 0
                    && scanned % BUDGET_CHECK_INTERVAL == 0
                    && shouldPause.getAsBoolean()) {
                this.paused = true;
                return null;
            }
            boolean fromCursor = false;
            BlockPos dirtyPos;
            if (this.snapshots != null) {
                if (!this.cursorPrepared && !this.distanceCursor.isComplete()) {
                    if (!tryAdvance.getAsBoolean()) {
                        this.paused = true;
                        return null;
                    }
                    this.cursorPrepared = this.distanceCursor.poll(this.cursorPosition) == PositionCursor.PollResult.AVAILABLE;
                }
                ScanDirtyPosition dirty = this.dirtyPositions.peek();
                if (dirty != null && (!this.cursorPrepared
                        || this.constructionOrder.compare(dirty.pos().asLong(), this.cursorPosition.asLong()) <= 0)) {
                    if (!tryAdvance.getAsBoolean()) {
                        this.paused = true;
                        return null;
                    }
                    dirtyPos = this.dirtyPositions.remove().pos();
                    this.dirtyPositionKeys.remove(ScanCache.key(dirtyPos));
                    if (this.offeredDirtyPositions.size() < MAX_DIRTY_POSITIONS) {
                        this.offeredDirtyPositions.add(ScanCache.key(dirtyPos));
                    }
                } else {
                    dirtyPos = null;
                }
            } else {
                if (!tryAdvance.getAsBoolean()) {
                    this.paused = true;
                    return null;
                }
                dirtyPos = this.pollDirtyPositionBefore(this.distanceCursor.peekDistanceSqr());
            }
            int x;
            int y;
            int z;
            if (dirtyPos != null) {
                x = dirtyPos.getX();
                y = dirtyPos.getY();
                z = dirtyPos.getZ();
                this.liveMutable.set(x, y, z);
            } else {
                PositionCursor.PollResult pollResult;
                if (this.snapshots != null) {
                    pollResult = this.cursorPrepared ? PositionCursor.PollResult.AVAILABLE : PositionCursor.PollResult.COMPLETE;
                    if (this.cursorPrepared) this.liveMutable.set(this.cursorPosition);
                    this.cursorPrepared = false;
                } else {
                    pollResult = this.distanceCursor.poll(this.liveMutable);
                }
                if (pollResult == PositionCursor.PollResult.COMPLETE) {
                    if (this.finishPass(tickTime)) {
                        return null;
                    }
                    continue;
                }
                x = this.liveMutable.getX();
                y = this.liveMutable.getY();
                z = this.liveMutable.getZ();
                fromCursor = true;
            }
            scanned++;

            if (!preFilter.test(this.liveMutable)) {
                continue;
            }

            int chunkX = ScanGeometry.sectionCoord(x);
            int chunkZ = ScanGeometry.sectionCoord(z);
            if (chunkX != this.lastChunkX || chunkZ != this.lastChunkZ) {
                this.lastChunkX = chunkX;
                this.lastChunkZ = chunkZ;
                if (!observation.hasChunk(chunkX, chunkZ)) {
                    this.lastChunkLoaded = false;
                } else {
                    this.lastChunkLoaded = this.chunkCandidateCache.getOrCompute(
                            chunkX,
                            chunkZ,
                            () -> observation.hasCandidatesInChunk(
                                this.intent,
                                chunkX,
                                chunkZ,
                                Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                            )
                    );
                }
            }
            if (!this.lastChunkLoaded) {
                // Chunk-level rejection is stronger than section metadata. Do not walk
                // every cell of an empty or unloaded chunk before its next wake/repair.
                if (fromCursor && this.distanceCursor instanceof ConstructionPositionCursor cursor) cursor.skipSection();
                continue;
            }

            if (this.snapshots != null && !observation.hasCandidatesInSection(this.intent,
                    chunkX, ScanGeometry.sectionCoord(y), chunkZ, Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue())) {
                if (fromCursor && this.distanceCursor instanceof ConstructionPositionCursor cursor) cursor.skipSection();
                continue;
            }

            this.metrics.recordScannedSection(ScanGeometry.sectionKey(
                    chunkX, ScanGeometry.sectionCoord(y), chunkZ));
            long sectionKey = ScanGeometry.sectionKey(chunkX, ScanGeometry.sectionCoord(y), chunkZ);

            if (this.snapshots != null && this.intent != ScanIntent.CUSTOM
                    && !this.snapshots.hasPotentialOrUnknown(sectionKey, this.intent,
                    Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue())) {
                if (fromCursor && this.distanceCursor instanceof ConstructionPositionCursor cursor) cursor.skipSection();
                continue;
            }

            this.metrics.scannedBlocks++;
            if (this.intent == ScanIntent.CUSTOM) {
                return this.candidate(x, y, z, (byte) 0, tickTime);
            }
            byte flags;
            long classifyStart = this.metrics.timing.start();
            try {
                flags = observation.classify(
                        this.intent,
                        this.liveMutable,
                        Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                );
            } finally {
                this.metrics.timing.finish(this.metrics.owner, Stage.CLASSIFICATION, classifyStart);
            }
            if (flags != 0) {
                return this.candidate(x, y, z, flags, tickTime);
            }
            if (this.snapshots != null && fromCursor && this.distanceCursor instanceof ConstructionPositionCursor cursor
                    && !this.snapshots.hasPotentialOrUnknown(sectionKey, this.intent,
                    Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue())) cursor.skipSection();
        }
    }

    private Candidate candidate(int x, int y, int z, byte flags, long tickTime) {
        if (this.distanceCursor.isComplete() && !this.cursorPrepared && this.dirtyPositions.isEmpty()
                && this.cursorRevision == this.sourceRevision) {
            // The consumer may stop after this candidate. Do not spend the next tick merely
            // discovering that this pass ended; same-tick restarts remain forbidden.
            this.finishPass(tickTime);
        }
        return new Candidate(new BlockPos(x, y, z), flags);
    }

    private BlockPos pollDirtyPositionBefore(long sourceDistanceSqr) {
        while (!this.dirtyPositions.isEmpty()) {
            ScanDirtyPosition dirty = this.dirtyPositions.peek();
            if (dirty.distanceSqr() > sourceDistanceSqr) {
                return null;
            }
            this.dirtyPositions.poll();
            BlockPos pos = dirty.pos();
            this.dirtyPositionKeys.remove(ScanCache.key(pos));
            if (ScanGeometry.containsAny(this.sourceBoxes, pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean finishPass(long tickTime) {
        // A finite traversal establishes maintenance even when deferred updates require
        // another pass immediately; repeated follow-ups must not postpone its first deadline.
        if (this.snapshots != null && this.nextRepairTick == Long.MAX_VALUE) this.nextRepairTick = tickTime + 20;
        boolean needsRescan = this.cursorRevision != this.sourceRevision || this.deferredDirtyRescan;
        // A dirty-only wake may arrive before every tick and prevent canScan() from ever
        // observing the sleeping state. Give due repair a turn when ordinary work drains.
        // A repair pass itself must still yield instead of chaining overdue sections.
        if (this.snapshots != null && this.repairBoxes == null && tickTime >= this.nextRepairTick
                && this.startRepair(tickTime)) {
            // Starting repair replaces the cursor. Preserve a pending full traversal so
            // repeated dirty deferrals cannot suppress repair or disappear behind it.
            this.deferredDirtyRescan = needsRescan;
            return false;
        }
        if (needsRescan) {
            this.recordReason(Reason.SOURCE_CHANGED);
            this.repairBoxes = null;
            this.rebuildDistanceCursor();
            return false;
        }
        // Legacy diagnostic cursors restart next tick. Production worksets sleep until a local
        // change or due repair; the outer coordinator never delays admission.
        this.exhaustedUntilTick = this.snapshots == null ? tickTime + 1 : Long.MAX_VALUE;
        if (this.snapshots != null) {
            this.repairBoxes = null;
        }
        this.metrics.completedPasses++;
        return true;
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.scanHandle.close();
        this.distanceCursor.close();
        this.dirtyPositions.clear();
        this.dirtyPositionKeys.clear();
        this.offeredDirtyPositions.clear();
        this.pendingReoffers.clear();
        this.deferredDirtyRescan = false;
        this.chunkCandidateCache.clear();
    }

    private ScanHandle createScanHandle() {
        return new ScanHandle(new ScanGeneration(
                this.epoch,
                this.sourceRevision,
                this.snapshotRevision.getAsLong(),
                this.generationSequence.getAsLong()
        ));
    }

    record Candidate(BlockPos pos, byte flags) {
        boolean acceptedByFlags(ScanIntent intent) {
            return intent.acceptsByFlags(this.flags);
        }
    }

    /** Reuse the active burst's table; release its peak capacity on pass reset or close. */
    private static final class DirtyPositionKeys extends LongOpenHashSet {
        @Override
        protected void rehash(int newSize) {
            // Removing a batch must not shrink the table that the next update batch will grow again.
            if (newSize > this.n) super.rehash(newSize);
        }

        @Override
        public void clear() {
            super.clear();
            if (this.n > this.minN) super.rehash(this.minN);
        }
    }

}
