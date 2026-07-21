package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
<<<<<<< HEAD
import net.minecraft.world.level.block.BaseRailBlock;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ScanCache {
    public static final ScanCache INSTANCE = new ScanCache();

    private static final int SECTION_SIZE = 16;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int MAX_SECTION_CACHE_ENTRIES = 8192;
    private static final int WORLD_SECTION_TTL_TICKS = 80;
    private static final int SCHEMATIC_SECTION_TTL_TICKS = 80;
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int SECTION_SCAN_BUDGET_CHECK_INTERVAL = 8;
    private static final int OWNER_SCAN_BUDGET_PERCENT = 75;
    private static final int MAX_SECTION_ADVANCES_PER_STEP = 16;
    private static final int SECTION_CANDIDATE_BURST = 8;
    private static final int LIVE_SECTION_SCAN_SLICE_BLOCKS = 256;
    private static final int MAX_INTERLEAVED_SECTIONS = 24;
    private static final byte SAMPLE_AIR = 1;
    private static final byte SAMPLE_LIQUID_BLOCK = 1 << 1;
    private static final byte SAMPLE_FLUID = 1 << 2;
    private static final byte SAMPLE_REPLACEABLE = 1 << 3;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int[][] LOCAL_AXIS_ORDER = buildLocalAxisOrder();

    private final Long2ObjectOpenHashMap<SectionEntry> sections = new Long2ObjectOpenHashMap<>();
    private final Map<String, SectionScanSession> sessions = new HashMap<>();
    private final Map<String, MutableScanMetrics> scanMetrics = new HashMap<>();

    private Object levelIdentity;
    private Object schematicIdentity;
    private long tickTime = Long.MIN_VALUE;
    private long scanBudgetTickTime = Long.MIN_VALUE;
    private long globalScanBudgetUsedNanos;

    private ScanCache() {
    }

    public void clear() {
        this.sections.clear();
        this.sessions.clear();
        this.scanMetrics.clear();
        this.levelIdentity = null;
        this.schematicIdentity = null;
        this.tickTime = Long.MIN_VALUE;
        this.scanBudgetTickTime = Long.MIN_VALUE;
        this.globalScanBudgetUsedNanos = 0L;
        DirtyRegionTracker.INSTANCE.clear();
    }

    public record ScanMetrics(
            long scanNanos,
            int scannedBlocks,
            int scannedSections,
            int sourceCandidates,
            int acceptedTargets,
            int budgetPauses,
            int completedPasses
    ) {
        private static final ScanMetrics EMPTY = new ScanMetrics(0L, 0, 0, 0, 0, 0, 0);

        public boolean hasActivity() {
            return this.scanNanos > 0L
                    || this.scannedBlocks > 0
                    || this.scannedSections > 0
                    || this.sourceCandidates > 0
                    || this.acceptedTargets > 0
                    || this.budgetPauses > 0
                    || this.completedPasses > 0;
        }
    }

    public static long key(BlockPos pos) {
        return key(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    public void beginTick(ClientLevel level, WorldSchematic schematic, long tickTime) {
        if (this.levelIdentity == level && this.schematicIdentity == schematic && this.tickTime == tickTime) {
            return;
        }
        if (this.levelIdentity != level || this.schematicIdentity != schematic) {
            this.sections.clear();
            this.sessions.clear();
            this.scanMetrics.clear();
            DirtyRegionTracker.INSTANCE.clear();
            this.levelIdentity = level;
            this.schematicIdentity = schematic;
        }
        if (this.tickTime != tickTime) {
            for (MutableScanMetrics metrics : this.scanMetrics.values()) {
                metrics.reset();
            }
        }
        this.tickTime = tickTime;
        if (this.scanBudgetTickTime != tickTime) {
            this.scanBudgetTickTime = tickTime;
            this.globalScanBudgetUsedNanos = 0L;
        }
        if (tickTime % 20L == 0L) {
            this.prune();
        }
    }

    public ScanMetrics metricsFor(String ownerKey) {
        MutableScanMetrics metrics = this.scanMetrics.get(normalizeMetricsOwnerKey(ownerKey));
        return metrics == null ? ScanMetrics.EMPTY : metrics.snapshot();
    }

    public void invalidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        int sectionX = sectionCoord(pos.getX());
        int sectionY = sectionCoord(pos.getY());
        int sectionZ = sectionCoord(pos.getZ());
        this.sections.remove(sectionKey(sectionX, sectionY, sectionZ));
        for (SectionScanSession session : this.sessions.values()) {
            session.invalidate(pos);
        }
    }

<<<<<<< HEAD
    public void resetOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return;
        }
        String prefix = ownerKey + ":";
        this.sessions.keySet().removeIf(key -> key.startsWith(prefix));
    }

=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    public Iterable<BlockPos> rawIterable(
            String ownerKey,
            PrinterBox sourceBox,
            LocalPlayer player,
            int scanGuardLimit,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), null, null, player, scanGuardLimit, ScanIntent.CUSTOM, pos -> true, preFilter, false);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate
    ) {
        return this.iterable(ownerKey, sourceBox, level, schematic, player, scanGuardLimit, intent, exactPredicate, pos -> true);
    }

    public Iterable<BlockPos> unboundedIterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, Integer.MAX_VALUE, intent, exactPredicate, preFilter, true);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        return this.iterable(ownerKey, List.of(sourceBox), level, schematic, player, scanGuardLimit, intent, exactPredicate, preFilter, false);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate
    ) {
        return this.iterable(
                ownerKey,
                sourceBoxes,
                level,
                schematic,
                player,
                scanGuardLimit,
                intent,
                exactPredicate,
                pos -> true
        );
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        if (sourceBoxes == null || sourceBoxes.isEmpty()) {
            return List.of();
        }
        return this.iterable(ownerKey, sourceBoxes, level, schematic, player, scanGuardLimit, intent, exactPredicate, preFilter, false);
    }

    private Iterable<BlockPos> iterable(
            String ownerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter,
            boolean unbounded
    ) {
        int scanLimit = unbounded ? Integer.MAX_VALUE : this.getScanLimit(scanGuardLimit);
        String cacheOwnerKey = this.cacheOwnerKey(ownerKey, intent);
        String metricsOwnerKey = normalizeMetricsOwnerKey(ownerKey);
        MutableScanMetrics metrics = this.metrics(metricsOwnerKey);
        PrinterBox sourceBounds = enclosingBox(sourceBoxes);
        if (sourceBounds == null) {
            return List.of();
        }
        SectionScanSession session = this.session(
                cacheOwnerKey,
                metrics,
                intent,
                sourceBounds,
                sourceBoxes,
                player
        );
        return () -> new Iterator<>() {
            private final LongSet emitted = new LongOpenHashSet();
            private BlockPos next;
            private boolean prepared;
            private boolean scanLimitHit;
            private boolean sentinelReturned;
            private int considered;
            private int budgetChecks;

            private void prepare() {
                if (this.prepared) {
                    return;
                }
                this.prepared = true;

                long budgetStart = System.nanoTime();
                boolean budgetHit = false;
                try {
                    while (this.considered < scanLimit && session.canScan(tickTime)) {
                        if (!unbounded && ++this.budgetChecks % BUDGET_CHECK_INTERVAL == 0 && isScanBudgetExceeded(budgetStart)) {
                            budgetHit = true;
                            break;
                        }

                        Candidate candidate = session.next(level, schematic, tickTime,
                                () -> !unbounded && isScanBudgetExceeded(budgetStart),
                                preFilter,
                                unbounded);
                        if (candidate == null) {
                            if (session.wasPaused()) {
                                budgetHit = true;
                            }
                            break;
                        }
                        metrics.sourceCandidates++;
                        this.considered++;
                        BlockPos pos = candidate.pos();
                        if (!session.contains(pos)) {
                            continue;
                        }
                        boolean target = candidate.acceptedByFlags(intent);
                        if (!target && intent.shouldRunExactPredicate(candidate.flags())) {
                            target = exactPredicate.test(pos);
                        }
                        if (!target) {
                            continue;
                        }
                        long posKey = key(pos);
                        if (this.emitted.add(posKey)) {
                            metrics.acceptedTargets++;
                            this.next = pos;
                            return;
                        }
                    }
                } finally {
                    if (!unbounded) {
                        recordScanBudget(metrics, budgetStart);
                    }
                }

                if (budgetHit) {
                    metrics.budgetPauses++;
                }
                this.scanLimitHit = session.hasPendingSource(tickTime) && (budgetHit || this.considered >= scanLimit);
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.next != null || this.scanLimitHit && !this.sentinelReturned;
            }

            @Override
            public BlockPos next() {
                this.prepare();
                if (this.next != null) {
                    BlockPos result = this.next;
                    this.next = null;
                    this.prepared = false;
                    return result;
                }
                if (this.scanLimitHit && !this.sentinelReturned) {
                    this.sentinelReturned = true;
                    return null;
                }
                return null;
            }

        };
    }

    private String cacheOwnerKey(String ownerKey, ScanIntent intent) {
        if (intent == ScanIntent.PRINT && Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) {
            return ownerKey + ":breakExtra";
        }
        return ownerKey;
    }

    private SectionScanSession session(
            String ownerKey,
            MutableScanMetrics metrics,
            ScanIntent intent,
            PrinterBox sourceBounds,
            List<PrinterBox> sourceBoxes,
            LocalPlayer player
    ) {
        SectionRegion region = SectionRegion.from(sourceBounds, player);
        String key = ownerKey + ":" + intent.name();
        SectionScanSession session = this.sessions.get(key);
        if (session == null || !session.canReuse(region, sourceBoxes)) {
            session = new SectionScanSession(region, sourceBoxes, intent, metrics);
            this.sessions.put(key, session);
        } else {
            session.updateRegion(region, sourceBoxes);
        }
        return session;
    }

    private static PrinterBox enclosingBox(List<PrinterBox> boxes) {
        PrinterBox result = null;
        for (PrinterBox box : boxes) {
            if (box == null) {
                continue;
            }
            if (result == null) {
                result = box;
                continue;
            }
            result = new PrinterBox(
                    Math.min(result.minX, box.minX),
                    Math.min(result.minY, box.minY),
                    Math.min(result.minZ, box.minZ),
                    Math.max(result.maxX, box.maxX),
                    Math.max(result.maxY, box.maxY),
                    Math.max(result.maxZ, box.maxZ)
            );
        }
        return result;
    }

    private static boolean containsAny(List<PrinterBox> boxes, BlockPos pos) {
        for (PrinterBox box : boxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private SectionEntry sectionEntry(int sectionX, int sectionY, int sectionZ) {
        long sectionKey = sectionKey(sectionX, sectionY, sectionZ);
        SectionEntry entry = this.sections.get(sectionKey);
        if (entry == null) {
            entry = new SectionEntry(sectionX, sectionY, sectionZ);
            this.sections.put(sectionKey, entry);
        }
        return entry;
    }

    private boolean isScanBudgetExceeded(long ownerBudgetStartNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - ownerBudgetStartNanos);
        long globalBudgetNanos = this.globalScanBudgetNanos();
        long ownerBudgetNanos = this.ownerScanBudgetNanos(globalBudgetNanos);
        return elapsed >= ownerBudgetNanos
                || this.globalScanBudgetUsedNanos + elapsed >= globalBudgetNanos;
    }

    private void recordScanBudget(MutableScanMetrics metrics, long ownerBudgetStartNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - ownerBudgetStartNanos);
        this.globalScanBudgetUsedNanos += elapsed;
        metrics.scanNanos += elapsed;
    }

    private MutableScanMetrics metrics(String ownerKey) {
        return this.scanMetrics.computeIfAbsent(ownerKey, key -> new MutableScanMetrics());
    }

    private static String normalizeMetricsOwnerKey(String ownerKey) {
        int separator = ownerKey.length();
        int underscore = ownerKey.indexOf('_');
        if (underscore >= 0) {
            separator = Math.min(separator, underscore);
        }
        int colon = ownerKey.indexOf(':');
        if (colon >= 0) {
            separator = Math.min(separator, colon);
        }
        return ownerKey.substring(0, separator);
    }

    private static int exhaustedRescanDelayTicks() {
        int lazyEnterTicks = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        return lazyEnterTicks > 0 ? lazyEnterTicks + 1 : 1;
    }

    private long globalScanBudgetNanos() {
        return Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
    }

    private long ownerScanBudgetNanos(long globalBudgetNanos) {
        return Math.max(500_000L, globalBudgetNanos * OWNER_SCAN_BUDGET_PERCENT / 100L);
    }

    private int getScanLimit(int scanGuardLimit) {
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }

    private void prune() {
        if (this.sections.isEmpty()) {
            return;
        }
        this.sections.long2ObjectEntrySet().removeIf(entry -> entry.getValue().isExpired(this.tickTime));
        if (this.sections.size() <= MAX_SECTION_CACHE_ENTRIES) {
            return;
        }
        int removeCount = this.sections.size() - MAX_SECTION_CACHE_ENTRIES;
        LongIterator iterator = this.sections.keySet().iterator();
        while (iterator.hasNext() && removeCount-- > 0) {
            iterator.nextLong();
            iterator.remove();
        }
    }

    private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | ((long) sectionY & 0xFFFFFL);
    }

    private static int sectionCoord(int blockCoord) {
        return blockCoord >> 4;
    }

    private static int localIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    private static long distanceSqr(BlockPos pos, int centerX, int centerY, int centerZ) {
        long dx = pos.getX() - (long) centerX;
        long dy = pos.getY() - (long) centerY;
        long dz = pos.getZ() - (long) centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static BlockPos blockPos(int sectionX, int sectionY, int sectionZ, int localIndex) {
        int x = (sectionX << 4) + (localIndex & 15);
        int z = (sectionZ << 4) + (localIndex >> 4 & 15);
        int y = (sectionY << 4) + (localIndex >> 8 & 15);
        return new BlockPos(x, y, z);
    }

    private static int[][] buildLocalAxisOrder() {
        int[][] order = new int[SECTION_SIZE][SECTION_SIZE];
        for (int anchor = 0; anchor < SECTION_SIZE; anchor++) {
            int index = 0;
            order[anchor][index++] = anchor;
            for (int distance = 1; index < SECTION_SIZE; distance++) {
                int negative = anchor - distance;
                int positive = anchor + distance;
                if (negative >= 0) {
                    order[anchor][index++] = negative;
                }
                if (positive < SECTION_SIZE && index < SECTION_SIZE) {
                    order[anchor][index++] = positive;
                }
            }
        }
        return order;
    }

    private static int clampLocal(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private record Candidate(BlockPos pos, byte flags) {
        boolean acceptedByFlags(ScanIntent intent) {
            return intent.acceptsByFlags(this.flags);
        }
    }

    private record SectionRegion(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int maxSectionX,
            int maxSectionY,
            int maxSectionZ,
            int centerX,
            int centerY,
            int centerZ,
            int centerSectionX,
            int centerSectionY,
            int centerSectionZ,
            int maxRadius
    ) {
        static SectionRegion from(PrinterBox box, LocalPlayer player) {
            int centerX = player == null ? (box.minX + box.maxX) >> 1 : (int) Math.floor(player.getX());
            int centerY = player == null ? (box.minY + box.maxY) >> 1 : (int) Math.floor(player.getEyeY());
            int centerZ = player == null ? (box.minZ + box.maxZ) >> 1 : (int) Math.floor(player.getZ());
            int centerSectionX = sectionCoord(centerX);
            int centerSectionY = sectionCoord(centerY);
            int centerSectionZ = sectionCoord(centerZ);
            int minSectionX = sectionCoord(box.minX);
            int minSectionY = sectionCoord(box.minY);
            int minSectionZ = sectionCoord(box.minZ);
            int maxSectionX = sectionCoord(box.maxX);
            int maxSectionY = sectionCoord(box.maxY);
            int maxSectionZ = sectionCoord(box.maxZ);
            int maxRadius = Math.max(
                    Math.max(Math.max(Math.abs(minSectionX - centerSectionX), Math.abs(maxSectionX - centerSectionX)),
                            Math.max(Math.abs(minSectionY - centerSectionY), Math.abs(maxSectionY - centerSectionY))),
                    Math.max(Math.abs(minSectionZ - centerSectionZ), Math.abs(maxSectionZ - centerSectionZ))
            );
            return new SectionRegion(
                    box.minX,
                    box.minY,
                    box.minZ,
                    box.maxX,
                    box.maxY,
                    box.maxZ,
                    minSectionX,
                    minSectionY,
                    minSectionZ,
                    maxSectionX,
                    maxSectionY,
                    maxSectionZ,
                    centerX,
                    centerY,
                    centerZ,
                    centerSectionX,
                    centerSectionY,
                    centerSectionZ,
                    maxRadius
            );
        }

        boolean containsSection(int sectionX, int sectionY, int sectionZ) {
            return sectionX >= this.minSectionX && sectionX <= this.maxSectionX
                    && sectionY >= this.minSectionY && sectionY <= this.maxSectionY
                    && sectionZ >= this.minSectionZ && sectionZ <= this.maxSectionZ;
        }

        boolean sameSectionWindow(SectionRegion other) {
            return this.minSectionX == other.minSectionX
                    && this.minSectionY == other.minSectionY
                    && this.minSectionZ == other.minSectionZ
                    && this.maxSectionX == other.maxSectionX
                    && this.maxSectionY == other.maxSectionY
                    && this.maxSectionZ == other.maxSectionZ;
        }

        boolean sameCenterSection(SectionRegion other) {
            return this.centerSectionX == other.centerSectionX
                    && this.centerSectionY == other.centerSectionY
                    && this.centerSectionZ == other.centerSectionZ;
        }

        boolean sameCenterBlock(SectionRegion other) {
            return this.centerX == other.centerX
                    && this.centerY == other.centerY
                    && this.centerZ == other.centerZ;
        }

        boolean sameBlockBounds(SectionRegion other) {
            return this.minX == other.minX
                    && this.minY == other.minY
                    && this.minZ == other.minZ
                    && this.maxX == other.maxX
                    && this.maxY == other.maxY
                    && this.maxZ == other.maxZ;
        }

        boolean containsBlock(BlockPos pos) {
            return pos.getX() >= this.minX && pos.getX() <= this.maxX
                    && pos.getY() >= this.minY && pos.getY() <= this.maxY
                    && pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
        }

        boolean containsBlock(int x, int y, int z) {
            return x >= this.minX && x <= this.maxX
                    && y >= this.minY && y <= this.maxY
                    && z >= this.minZ && z <= this.maxZ;
        }
    }

    private final class SectionScanSession {
        private final ScanIntent intent;
        private final MutableScanMetrics metrics;
        private SectionRegion region;
        private List<PrinterBox> sourceBoxes;
        private SectionCursor cursor = new SectionCursor();
        private PlayerDistanceCursor distanceCursor;
        private long exhaustedUntilTick = Long.MIN_VALUE;
        private SectionEntry currentSection;
        private short[] currentCandidates = ShortArrayBuilder.EMPTY;
        private final ArrayDeque<SectionProgress> deferredSections = new ArrayDeque<>();
        private final ArrayDeque<LiveSectionProgress> deferredLiveSections = new ArrayDeque<>();
        private final ArrayDeque<SectionPos> dirtySections = new ArrayDeque<>();
        private final LongSet dirtySectionKeys = new LongOpenHashSet();
        private final PriorityQueue<DirtyPosition> dirtyPositions = new PriorityQueue<>();
        private final LongSet dirtyPositionKeys = new LongOpenHashSet();
        private int candidateIndex;
        private int phase;
        private int sectionBurstRemaining;
        private boolean currentSectionPrepared;
        private boolean paused;
        private int liveSectionX;
        private int liveSectionY;
        private int liveSectionZ;
        private int liveLocalIndex;
        private int liveAnchorX;
        private int liveAnchorY;
        private int liveAnchorZ;
        private int liveSectionScannedThisSlice;
        private boolean liveSectionActive;
        private final BlockPos.MutableBlockPos liveMutable = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos liveNeighbor = new BlockPos.MutableBlockPos();
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private boolean lastChunkLoaded;

        private SectionScanSession(
                SectionRegion region,
                List<PrinterBox> sourceBoxes,
                ScanIntent intent,
                MutableScanMetrics metrics
        ) {
            this.region = region;
            this.sourceBoxes = List.copyOf(sourceBoxes);
            this.intent = intent;
            this.metrics = metrics;
            this.distanceCursor = new PlayerDistanceCursor(this.sourceBoxes, region);
        }

        boolean canReuse(SectionRegion region, List<PrinterBox> sourceBoxes) {
            return this.region.sameSectionWindow(region) && this.sourceBoxes.equals(sourceBoxes);
        }

        void updateRegion(SectionRegion region, List<PrinterBox> sourceBoxes) {
            boolean boundsChanged = !this.region.sameBlockBounds(region);
            boolean centerChanged = !this.region.sameCenterBlock(region);
            boolean boxesChanged = !this.sourceBoxes.equals(sourceBoxes);
            this.region = region;
            if (boxesChanged) {
                this.sourceBoxes = List.copyOf(sourceBoxes);
            }
            if (boundsChanged || centerChanged || boxesChanged) {
                this.resetProgress();
            }
        }

        boolean canScan(long tickTime) {
            if (this.exhaustedUntilTick == Long.MIN_VALUE) {
                return true;
            }
            if (tickTime < this.exhaustedUntilTick) {
                return false;
            }
            this.resetProgress();
            return true;
        }

        private void resetProgress() {
            this.cursor = new SectionCursor();
            this.distanceCursor = new PlayerDistanceCursor(this.sourceBoxes, this.region);
            this.exhaustedUntilTick = Long.MIN_VALUE;
            this.currentSection = null;
            this.currentCandidates = ShortArrayBuilder.EMPTY;
            this.deferredSections.clear();
            this.deferredLiveSections.clear();
            this.dirtySections.clear();
            this.dirtySectionKeys.clear();
            this.dirtyPositions.clear();
            this.dirtyPositionKeys.clear();
            this.candidateIndex = 0;
            this.phase = 0;
            this.sectionBurstRemaining = 0;
            this.currentSectionPrepared = false;
            this.liveSectionActive = false;
            this.liveLocalIndex = 0;
            this.liveSectionScannedThisSlice = 0;
            this.lastChunkX = Integer.MIN_VALUE;
            this.lastChunkZ = Integer.MIN_VALUE;
            this.lastChunkLoaded = false;
        }

        boolean hasPendingSource(long tickTime) {
            return this.canScan(tickTime)
                    && (!this.dirtyPositions.isEmpty()
                    || !this.distanceCursor.complete
                    || this.currentSection != null
                    || this.liveSectionActive
                    || !this.dirtySections.isEmpty()
                    || !this.deferredSections.isEmpty()
                    || !this.deferredLiveSections.isEmpty()
                    || !this.cursor.complete);
        }

        boolean wasPaused() {
            return this.paused;
        }

        boolean contains(BlockPos pos) {
            return containsAny(this.sourceBoxes, pos);
        }

        void invalidate(BlockPos pos) {
            this.addDirtyPosition(pos);
            if (this.intent == ScanIntent.FILL) {
                for (Direction direction : DIRECTIONS) {
                    this.addDirtyPosition(pos.relative(direction));
                }
            }
            this.paused = false;
        }

        private void addDirtyPosition(BlockPos pos) {
            if (pos == null || !containsAny(this.sourceBoxes, pos)) {
                return;
            }
            this.exhaustedUntilTick = Long.MIN_VALUE;
            long key = ScanCache.key(pos);
            if (this.dirtyPositionKeys.add(key)) {
                this.dirtyPositions.add(new DirtyPosition(
                        pos.immutable(),
                        distanceSqr(pos, this.region.centerX(), this.region.centerY(), this.region.centerZ())
                ));
            }
        }

        Candidate next(
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause,
                Predicate<BlockPos> preFilter,
                boolean unbounded
        ) {
            this.paused = false;
            if (!this.canScan(tickTime)) {
                return null;
            }
            return this.nextByPlayerDistance(level, schematic, tickTime, shouldPause, preFilter, unbounded);
        }

        private Candidate nextByPlayerDistance(
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause,
                Predicate<BlockPos> preFilter,
                boolean unbounded
        ) {
            if (level == null) {
                this.markExhausted(tickTime);
                return null;
            }
            int scanned = 0;
            while (true) {
                if (!unbounded
                        && scanned > 0
                        && scanned % SECTION_SCAN_BUDGET_CHECK_INTERVAL == 0
                        && shouldPause.getAsBoolean()) {
                    this.paused = true;
                    return null;
                }

                BlockPos dirtyPos = this.pollDirtyPositionBefore(this.distanceCursor.peekDistanceSqr());
                int x;
                int y;
                int z;
                if (dirtyPos != null) {
                    x = dirtyPos.getX();
                    y = dirtyPos.getY();
                    z = dirtyPos.getZ();
                    this.liveMutable.set(x, y, z);
                } else {
                    if (!this.distanceCursor.next(this.liveMutable)) {
                        this.markExhausted(tickTime);
                        return null;
                    }
                    x = this.liveMutable.getX();
                    y = this.liveMutable.getY();
                    z = this.liveMutable.getZ();
                }
                scanned++;

                if (!preFilter.test(this.liveMutable)) {
                    continue;
                }

                int chunkX = sectionCoord(x);
                int chunkZ = sectionCoord(z);
                if (chunkX != this.lastChunkX || chunkZ != this.lastChunkZ) {
                    this.lastChunkX = chunkX;
                    this.lastChunkZ = chunkZ;
                    this.lastChunkLoaded = level.hasChunk(chunkX, chunkZ);
                }
                if (!this.lastChunkLoaded) {
                    continue;
                }

                this.metrics.recordScannedSection(sectionKey(chunkX, sectionCoord(y), chunkZ));

                BlockState state = level.getBlockState(this.liveMutable);
                this.metrics.scannedBlocks++;
                if (this.intent == ScanIntent.CUSTOM) {
                    return new Candidate(new BlockPos(x, y, z), (byte) 0);
                }
                byte flags = this.liveFlags(level, schematic, x, y, z, state);
                if (flags != 0) {
                    return new Candidate(new BlockPos(x, y, z), flags);
                }
            }
        }

        private BlockPos pollDirtyPositionBefore(long sourceDistanceSqr) {
            while (!this.dirtyPositions.isEmpty()) {
                DirtyPosition dirty = this.dirtyPositions.peek();
                if (dirty.distanceSqr() > sourceDistanceSqr) {
                    return null;
                }
                this.dirtyPositions.poll();
                BlockPos pos = dirty.pos();
                this.dirtyPositionKeys.remove(ScanCache.key(pos));
                if (containsAny(this.sourceBoxes, pos)) {
                    return pos;
                }
            }
            return null;
        }

        /*
         * 保留旧 section 缓存路径，供后续针对静态快照做专门优化；主扫描不再从这里调度，
         * 避免 section 成为放置顺序并造成 16x16x16 的成片推进。
         */
        private Candidate nextBySectionCache(
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause,
                boolean unbounded
        ) {
            int advancedSections = 0;
            while (true) {
                if (this.currentSectionPrepared) {
                    Candidate candidate = this.nextFromCurrentSection();
                    if (candidate != null) {
                        return candidate;
                    }
                    this.currentSection = null;
                    this.currentCandidates = ShortArrayBuilder.EMPTY;
                    this.candidateIndex = 0;
                    this.currentSectionPrepared = false;
                }

                if (this.currentSection == null) {
                    if ((!unbounded && advancedSections >= MAX_SECTION_ADVANCES_PER_STEP) || shouldPause.getAsBoolean()) {
                        if (!this.deferredSections.isEmpty()) {
                            this.restoreDeferredSection(this.deferredSections.removeFirst());
                            continue;
                        }
                        this.paused = true;
                        return null;
                    }
                    SectionPos sectionPos = this.pollDirtySection();
                    if (sectionPos == null && this.shouldResumeDeferredSection()) {
                        this.restoreDeferredSection(this.deferredSections.removeFirst());
                        continue;
                    }
                    if (sectionPos == null) {
                        sectionPos = this.cursor.next(this.region);
                    }
                    if (sectionPos == null) {
                        if (!this.deferredSections.isEmpty()) {
                            this.restoreDeferredSection(this.deferredSections.removeFirst());
                            continue;
                        }
                        this.markExhausted(tickTime);
                        return null;
                    }
                    advancedSections++;
                    if (this.usesWorld() && level != null && !level.hasChunk(sectionPos.x(), sectionPos.z())) {
                        continue;
                    }
                    this.currentSection = sectionEntry(sectionPos.x(), sectionPos.y(), sectionPos.z());
                    this.metrics.recordScannedSection(sectionKey(
                            this.currentSection.sectionX,
                            this.currentSection.sectionY,
                            this.currentSection.sectionZ
                    ));
                }

                if (!this.currentSection.ensure(this.intent, level, schematic, tickTime, shouldPause)) {
                    this.paused = true;
                    return null;
                }

                this.phase = 0;
                this.currentCandidates = this.currentSection.candidates(this.intent, this.phase);
                this.candidateIndex = 0;
                this.sectionBurstRemaining = SECTION_CANDIDATE_BURST;
                this.currentSectionPrepared = true;
            }
        }

        private boolean usesWorld() {
            return this.intent == ScanIntent.MINE
                    || this.intent == ScanIntent.FLUID
                    || this.intent == ScanIntent.FILL
                    || this.intent == ScanIntent.PRINT && Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                    || this.intent == ScanIntent.CUSTOM;
        }

        /**
         * 世界 intent 的活体逐方块时间片扫描:复用 SectionCursor 的「玩家中心由近及远」section 顺序,
         * 每个 section 内部从最靠近玩家的位置开始扫,直接逐方块 getBlockState 当场判定,不建 short[]、不缓存 SectionEntry。
         * 时间预算由 shouldPause 切断,跨 tick 续扫靠 liveSectionXYZ 与 liveLocalIndex 记录进度。
         * 这是为了消除大交互距离(box 远大于缓存)下反复 new 数组导致的 GC 卡顿。
         */
        private Candidate nextLive(ClientLevel level, WorldSchematic schematic, long tickTime, BooleanSupplier shouldPause, boolean unbounded) {
            if (level == null) {
                this.markExhausted(tickTime);
                return null;
            }
            int advancedSections = 0;
            while (true) {
                if (this.liveSectionActive) {
                    Candidate candidate = this.nextFromLiveSection(level, schematic, shouldPause, unbounded);
                    if (candidate != null) {
                        return candidate;
                    }
                    if (this.paused) {
                        return null;
                    }
                    this.liveSectionActive = false;
                }

                if ((!unbounded && advancedSections >= MAX_SECTION_ADVANCES_PER_STEP) || shouldPause.getAsBoolean()) {
                    this.paused = true;
                    return null;
                }

                SectionPos sectionPos = this.pollDirtySection();
                if (sectionPos == null && this.shouldResumeDeferredLiveSection()) {
                    this.restoreDeferredLiveSection(this.deferredLiveSections.removeFirst());
                    advancedSections++;
                    continue;
                }
                if (sectionPos == null) {
                    sectionPos = this.cursor.next(this.region);
                }
                if (sectionPos == null) {
                    if (!this.deferredLiveSections.isEmpty()) {
                        this.restoreDeferredLiveSection(this.deferredLiveSections.removeFirst());
                        advancedSections++;
                        continue;
                    }
                    this.markExhausted(tickTime);
                    return null;
                }
                advancedSections++;
                if (!level.hasChunk(sectionPos.x(), sectionPos.z())) {
                    continue;
                }
                this.metrics.recordScannedSection(sectionKey(
                        sectionPos.x(),
                        sectionPos.y(),
                        sectionPos.z()
                ));
                this.liveSectionX = sectionPos.x();
                this.liveSectionY = sectionPos.y();
                this.liveSectionZ = sectionPos.z();
                this.liveLocalIndex = 0;
                this.liveAnchorX = clampLocal(this.region.centerX() - (sectionPos.x() << 4));
                this.liveAnchorY = clampLocal(this.region.centerY() - (sectionPos.y() << 4));
                this.liveAnchorZ = clampLocal(this.region.centerZ() - (sectionPos.z() << 4));
                this.liveSectionScannedThisSlice = 0;
                this.liveSectionActive = true;
            }
        }

        private Candidate nextFromLiveSection(ClientLevel level, WorldSchematic schematic, BooleanSupplier shouldPause, boolean unbounded) {
            int baseX = this.liveSectionX << 4;
            int baseY = this.liveSectionY << 4;
            int baseZ = this.liveSectionZ << 4;
            while (this.liveLocalIndex < SECTION_VOLUME) {
                if (this.liveSectionScannedThisSlice >= LIVE_SECTION_SCAN_SLICE_BLOCKS) {
                    this.deferLiveSection();
                    return null;
                }
                if (!unbounded
                        && this.liveLocalIndex > 0
                        && this.liveLocalIndex % SECTION_SCAN_BUDGET_CHECK_INTERVAL == 0
                        && shouldPause.getAsBoolean()) {
                    this.paused = true;
                    return null;
                }
                int orderIndex = this.liveLocalIndex++;
                this.liveSectionScannedThisSlice++;
                int x = baseX + LOCAL_AXIS_ORDER[this.liveAnchorX][orderIndex & 15];
                int z = baseZ + LOCAL_AXIS_ORDER[this.liveAnchorZ][orderIndex >> 4 & 15];
                int y = baseY + LOCAL_AXIS_ORDER[this.liveAnchorY][orderIndex >> 8 & 15];
                if (!this.region.containsBlock(x, y, z)) {
                    continue;
                }
                this.liveMutable.set(x, y, z);
                BlockState state = level.getBlockState(this.liveMutable);
                this.metrics.scannedBlocks++;
                if (this.intent == ScanIntent.CUSTOM) {
                    return new Candidate(new BlockPos(x, y, z), (byte) 0);
                }
                byte flags = this.liveFlags(level, schematic, x, y, z, state);
                if (flags != 0) {
                    return new Candidate(new BlockPos(x, y, z), flags);
                }
            }
            return null;
        }

        private void markExhausted(long tickTime) {
            this.exhaustedUntilTick = tickTime + exhaustedRescanDelayTicks();
            this.metrics.completedPasses++;
        }

        /**
         * 当场判定一个方块对 world intent 的候选标志,等价于旧缓存路径的 worldSolid/worldFluid/worldFillBase 归类。
         * 返回 0 表示「非该 intent 候选,跳过」(不发射,省去 exactPredicate 调用)。
         */
        private byte liveFlags(ClientLevel level, WorldSchematic schematic, int x, int y, int z, BlockState state) {
            switch (this.intent) {
                case PRINT -> {
                    if (schematic == null) {
                        return 0;
                    }
                    BlockState requiredState = schematic.getBlockState(this.liveMutable);
<<<<<<< HEAD
                    if (requiredState.equals(state)
                            && !(requiredState.getBlock() instanceof BaseRailBlock)) {
                        return 0;
                    }
                    if (!requiredState.isAir()) {
                        return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.SCHEMATIC_NON_AIR);
                    }
                    if (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()
                            && !state.isAir()
                            && !(state.getBlock() instanceof LiquidBlock)) {
=======
                    if (!requiredState.isAir()) {
                        return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.SCHEMATIC_NON_AIR);
                    }
                    if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                        return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.WORLD_NON_AIR);
                    }
                    return 0;
                }
                case MINE -> {
                    if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                        return 0;
                    }
                    return ScanFlags.WORLD_NON_AIR;
                }
                case FLUID -> {
                    if (state.getFluidState().isEmpty()) {
                        return 0;
                    }
                    return (byte) (ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID);
                }
                case FILL -> {
                    boolean potential = state.isAir()
                            || state.getBlock() instanceof LiquidBlock
                            || BlockUtils.isReplaceable(state);
                    if (!potential || !this.hasFillSupportNeighborLive(level, x, y, z)) {
                        return 0;
                    }
                    return ScanFlags.BASE_FILL_TARGET;
                }
                default -> {
                    return 0;
                }
            }
        }

        private boolean hasFillSupportNeighborLive(ClientLevel level, int x, int y, int z) {
            for (Direction direction : DIRECTIONS) {
                this.liveNeighbor.set(
                        x + direction.getStepX(),
                        y + direction.getStepY(),
                        z + direction.getStepZ()
                );
                BlockState neighbor = level.getBlockState(this.liveNeighbor);
                if (!neighbor.isAir()
                        && !(neighbor.getBlock() instanceof LiquidBlock)
                        && !BlockUtils.isReplaceable(neighbor)) {
                    return true;
                }
            }
            return false;
        }

        private Candidate nextFromCurrentSection() {
            while (this.currentSection != null) {
                while (this.candidateIndex < this.currentCandidates.length) {
                    int localIndex = this.currentCandidates[this.candidateIndex++] & 0xFFFF;
                    byte flags = this.currentSection.flagsFor(this.intent, this.phase);
                    Candidate candidate = new Candidate(blockPos(this.currentSection.sectionX, this.currentSection.sectionY, this.currentSection.sectionZ, localIndex), flags);
                    if (--this.sectionBurstRemaining <= 0 && this.candidateIndex < this.currentCandidates.length) {
                        this.deferCurrentSection();
                    }
                    return candidate;
                }
                this.clearCurrentSection();
                return null;
            }
            return null;
        }

        private boolean shouldResumeDeferredSection() {
            return !this.deferredSections.isEmpty()
                    && (this.cursor.complete || this.deferredSections.size() >= MAX_INTERLEAVED_SECTIONS);
        }

        private boolean shouldResumeDeferredLiveSection() {
            return !this.deferredLiveSections.isEmpty()
                    && (this.cursor.complete || this.deferredLiveSections.size() >= MAX_INTERLEAVED_SECTIONS);
        }

        private boolean isCurrentSection(int sectionX, int sectionY, int sectionZ) {
            return this.currentSection != null
                    && this.currentSection.sectionX == sectionX
                    && this.currentSection.sectionY == sectionY
                    && this.currentSection.sectionZ == sectionZ;
        }

        private void removeDeferredSection(int sectionX, int sectionY, int sectionZ) {
            Iterator<SectionProgress> iterator = this.deferredSections.iterator();
            while (iterator.hasNext()) {
                SectionProgress progress = iterator.next();
                SectionEntry section = progress.section();
                if (section.sectionX == sectionX && section.sectionY == sectionY && section.sectionZ == sectionZ) {
                    iterator.remove();
                }
            }
        }

        private void removeDeferredLiveSection(int sectionX, int sectionY, int sectionZ) {
            Iterator<LiveSectionProgress> iterator = this.deferredLiveSections.iterator();
            while (iterator.hasNext()) {
                LiveSectionProgress progress = iterator.next();
                if (progress.sectionX() == sectionX && progress.sectionY() == sectionY && progress.sectionZ() == sectionZ) {
                    iterator.remove();
                }
            }
        }

        private void addDirtySection(int sectionX, int sectionY, int sectionZ) {
            long key = sectionKey(sectionX, sectionY, sectionZ);
            if (this.dirtySectionKeys.add(key)) {
                this.dirtySections.addLast(new SectionPos(sectionX, sectionY, sectionZ));
            }
        }

        private void addPrioritySection(int sectionX, int sectionY, int sectionZ) {
            if (!this.region.containsSection(sectionX, sectionY, sectionZ)) {
                return;
            }
            this.removeDeferredSection(sectionX, sectionY, sectionZ);
            this.removeDeferredLiveSection(sectionX, sectionY, sectionZ);
            long key = sectionKey(sectionX, sectionY, sectionZ);
            if (!this.dirtySectionKeys.add(key)) {
                this.removeDirtySection(sectionX, sectionY, sectionZ);
                this.dirtySectionKeys.add(key);
            }
            this.dirtySections.addFirst(new SectionPos(sectionX, sectionY, sectionZ));
        }

        private void prioritizeCenterSections(SectionRegion region) {
            int sectionX = region.centerSectionX();
            int sectionY = region.centerSectionY();
            int sectionZ = region.centerSectionZ();
            this.addPrioritySection(sectionX, sectionY - 1, sectionZ);
            this.addPrioritySection(sectionX, sectionY + 1, sectionZ);
            this.addPrioritySection(sectionX, sectionY, sectionZ - 1);
            this.addPrioritySection(sectionX, sectionY, sectionZ + 1);
            this.addPrioritySection(sectionX - 1, sectionY, sectionZ);
            this.addPrioritySection(sectionX + 1, sectionY, sectionZ);
            this.addPrioritySection(sectionX, sectionY, sectionZ);
        }

        private void removeDirtySection(int sectionX, int sectionY, int sectionZ) {
            Iterator<SectionPos> iterator = this.dirtySections.iterator();
            while (iterator.hasNext()) {
                SectionPos sectionPos = iterator.next();
                if (sectionPos.x() == sectionX && sectionPos.y() == sectionY && sectionPos.z() == sectionZ) {
                    iterator.remove();
                    return;
                }
            }
        }

        private SectionPos pollDirtySection() {
            while (!this.dirtySections.isEmpty()) {
                SectionPos sectionPos = this.dirtySections.removeFirst();
                this.dirtySectionKeys.remove(sectionKey(sectionPos.x(), sectionPos.y(), sectionPos.z()));
                if (this.region.containsSection(sectionPos.x(), sectionPos.y(), sectionPos.z())) {
                    return sectionPos;
                }
            }
            return null;
        }

        private void deferCurrentSection() {
            this.deferredSections.addLast(new SectionProgress(
                    this.currentSection,
                    this.currentCandidates,
                    this.candidateIndex,
                    this.phase
            ));
            this.clearCurrentSection();
        }

        private void deferLiveSection() {
            if (!this.liveSectionActive || this.liveLocalIndex >= SECTION_VOLUME) {
                this.liveSectionActive = false;
                this.liveSectionScannedThisSlice = 0;
                return;
            }
            this.deferredLiveSections.addLast(new LiveSectionProgress(
                    this.liveSectionX,
                    this.liveSectionY,
                    this.liveSectionZ,
                    this.liveLocalIndex,
                    this.liveAnchorX,
                    this.liveAnchorY,
                    this.liveAnchorZ
            ));
            this.liveSectionActive = false;
            this.liveSectionScannedThisSlice = 0;
        }

        private void restoreDeferredSection(SectionProgress progress) {
            this.currentSection = progress.section();
            this.currentCandidates = progress.candidates();
            this.candidateIndex = progress.candidateIndex();
            this.phase = progress.phase();
            this.sectionBurstRemaining = SECTION_CANDIDATE_BURST;
            this.currentSectionPrepared = true;
        }

        private void restoreDeferredLiveSection(LiveSectionProgress progress) {
            this.liveSectionX = progress.sectionX();
            this.liveSectionY = progress.sectionY();
            this.liveSectionZ = progress.sectionZ();
            this.liveLocalIndex = progress.localIndex();
            this.liveAnchorX = progress.anchorX();
            this.liveAnchorY = progress.anchorY();
            this.liveAnchorZ = progress.anchorZ();
            this.liveSectionScannedThisSlice = 0;
            this.liveSectionActive = true;
        }

        private void clearCurrentSection() {
            this.currentSection = null;
            this.currentCandidates = ShortArrayBuilder.EMPTY;
            this.candidateIndex = 0;
            this.phase = 0;
            this.sectionBurstRemaining = 0;
            this.currentSectionPrepared = false;
        }

        private record SectionProgress(SectionEntry section, short[] candidates, int candidateIndex, int phase) {
        }

        private record LiveSectionProgress(
                int sectionX,
                int sectionY,
                int sectionZ,
                int localIndex,
                int anchorX,
                int anchorY,
                int anchorZ
        ) {
        }
    }

    private record SectionPos(int x, int y, int z) {
    }

    private static final class SectionCursor {
        private boolean complete;
        private PriorityQueue<SectionNode> queue;
        private final LongSet queuedSections = new LongOpenHashSet();

        SectionPos next(SectionRegion region) {
            if (this.complete) {
                return null;
            }
            if (this.queue == null) {
                this.init(region);
            }

            while (!this.queue.isEmpty()) {
                SectionNode node = this.queue.poll();
                this.enqueueNeighbors(region, node);
                return new SectionPos(node.x(), node.y(), node.z());
            }
            this.complete = true;
            return null;
        }

        private void init(SectionRegion region) {
            this.queue = new PriorityQueue<>();
            this.queuedSections.clear();
            int startX = clamp(region.centerSectionX(), region.minSectionX(), region.maxSectionX());
            int startY = clamp(region.centerSectionY(), region.minSectionY(), region.maxSectionY());
            int startZ = clamp(region.centerSectionZ(), region.minSectionZ(), region.maxSectionZ());
            this.enqueue(region, startX, startY, startZ);
        }

        private void enqueueNeighbors(SectionRegion region, SectionNode node) {
            this.enqueue(region, node.x() - 1, node.y(), node.z());
            this.enqueue(region, node.x() + 1, node.y(), node.z());
            this.enqueue(region, node.x(), node.y() - 1, node.z());
            this.enqueue(region, node.x(), node.y() + 1, node.z());
            this.enqueue(region, node.x(), node.y(), node.z() - 1);
            this.enqueue(region, node.x(), node.y(), node.z() + 1);
        }

        private void enqueue(SectionRegion region, int sectionX, int sectionY, int sectionZ) {
            if (!region.containsSection(sectionX, sectionY, sectionZ)) {
                return;
            }
            long key = sectionKey(sectionX, sectionY, sectionZ);
            if (!this.queuedSections.add(key)) {
                return;
            }
            this.queue.add(new SectionNode(sectionX, sectionY, sectionZ,
                    sectionDistanceSqr(region, sectionX, sectionY, sectionZ)));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static long sectionDistanceSqr(SectionRegion region, int sectionX, int sectionY, int sectionZ) {
            long dx = axisDistance(region.centerX(), sectionX);
            long dy = axisDistance(region.centerY(), sectionY);
            long dz = axisDistance(region.centerZ(), sectionZ);
            return dx * dx + dy * dy + dz * dz;
        }

        private static long axisDistance(int blockCoord, int sectionCoord) {
            int min = sectionCoord << 4;
            int max = min + 15;
            if (blockCoord < min) {
                return min - (long) blockCoord;
            }
            if (blockCoord > max) {
                return blockCoord - (long) max;
            }
            return 0L;
        }
    }

    private static final class PlayerDistanceCursor {
        private final List<PrinterBox> boxes;
        private final PriorityQueue<BoxCursorNode> cursors;
        private boolean complete;

        private PlayerDistanceCursor(List<PrinterBox> boxes, SectionRegion region) {
            this.boxes = boxes;
            this.cursors = new PriorityQueue<>();
            for (int index = 0; index < boxes.size(); index++) {
                BoxDistanceCursor cursor = new BoxDistanceCursor(
                        boxes.get(index),
                        region.centerX(),
                        region.centerY(),
                        region.centerZ()
                );
                BlockPos.MutableBlockPos first = new BlockPos.MutableBlockPos();
                if (cursor.next(first)) {
<<<<<<< HEAD
                    this.cursors.add(new BoxCursorNode(
                            index,
                            cursor,
                            first.getX(),
                            first.getY(),
                            first.getZ(),
                            region
                    ));
=======
                    this.cursors.add(new BoxCursorNode(index, cursor, first.immutable(), region));
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                }
            }
            this.complete = this.cursors.isEmpty();
        }

        long peekDistanceSqr() {
            BoxCursorNode node = this.cursors.peek();
            return node == null ? Long.MAX_VALUE : node.distanceSqr();
        }

        boolean next(BlockPos.MutableBlockPos target) {
            while (!this.cursors.isEmpty()) {
                BoxCursorNode node = this.cursors.poll();
<<<<<<< HEAD
                int resultX = node.x;
                int resultY = node.y;
                int resultZ = node.z;
                if (node.cursor.next(node.following)) {
                    node.x = node.following.getX();
                    node.y = node.following.getY();
                    node.z = node.following.getZ();
                    this.cursors.add(node);
                }
                if (this.claimedByEarlierBox(node.boxIndex, resultX, resultY, resultZ)) {
                    continue;
                }
                target.set(resultX, resultY, resultZ);
=======
                BlockPos result = node.pos;
                BlockPos.MutableBlockPos following = new BlockPos.MutableBlockPos();
                if (node.cursor.next(following)) {
                    node.pos = following.immutable();
                    this.cursors.add(node);
                }
                if (this.claimedByEarlierBox(node.boxIndex, result)) {
                    continue;
                }
                target.set(result);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                return true;
            }
            this.complete = true;
            return false;
        }

<<<<<<< HEAD
        private boolean claimedByEarlierBox(int boxIndex, int x, int y, int z) {
            for (int index = 0; index < boxIndex; index++) {
                PrinterBox box = this.boxes.get(index);
                if (x >= box.minX && x <= box.maxX
                        && y >= box.minY && y <= box.maxY
                        && z >= box.minZ && z <= box.maxZ) {
=======
        private boolean claimedByEarlierBox(int boxIndex, BlockPos pos) {
            for (int index = 0; index < boxIndex; index++) {
                if (this.boxes.get(index).contains(pos)) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                    return true;
                }
            }
            return false;
        }

        private static final class BoxCursorNode implements Comparable<BoxCursorNode> {
            private final int boxIndex;
            private final BoxDistanceCursor cursor;
            private final int centerX;
            private final int centerY;
            private final int centerZ;
<<<<<<< HEAD
            private final BlockPos.MutableBlockPos following = new BlockPos.MutableBlockPos();
            private int x;
            private int y;
            private int z;
=======
            private BlockPos pos;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

            private BoxCursorNode(
                    int boxIndex,
                    BoxDistanceCursor cursor,
<<<<<<< HEAD
                    int x,
                    int y,
                    int z,
=======
                    BlockPos pos,
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                    SectionRegion region
            ) {
                this.boxIndex = boxIndex;
                this.cursor = cursor;
<<<<<<< HEAD
                this.x = x;
                this.y = y;
                this.z = z;
=======
                this.pos = pos;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                this.centerX = region.centerX();
                this.centerY = region.centerY();
                this.centerZ = region.centerZ();
            }

            @Override
            public int compareTo(BoxCursorNode other) {
                int result = Long.compare(this.distanceSqr(), other.distanceSqr());
                if (result != 0) {
                    return result;
                }
<<<<<<< HEAD
                result = Integer.compare(this.x, other.x);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.y, other.y);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.z, other.z);
=======
                result = Integer.compare(this.pos.getX(), other.pos.getX());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.pos.getY(), other.pos.getY());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(this.pos.getZ(), other.pos.getZ());
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                if (result != 0) {
                    return result;
                }
                return Integer.compare(this.boxIndex, other.boxIndex);
            }

            private long distanceSqr() {
<<<<<<< HEAD
                long dx = this.x - (long) this.centerX;
                long dy = this.y - (long) this.centerY;
                long dz = this.z - (long) this.centerZ;
=======
                long dx = this.pos.getX() - (long) this.centerX;
                long dy = this.pos.getY() - (long) this.centerY;
                long dz = this.pos.getZ() - (long) this.centerZ;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                return dx * dx + dy * dy + dz * dz;
            }
        }
    }

    private record DirtyPosition(BlockPos pos, long distanceSqr) implements Comparable<DirtyPosition> {
        @Override
        public int compareTo(DirtyPosition other) {
            int result = Long.compare(this.distanceSqr, other.distanceSqr);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.pos.getX(), other.pos.getX());
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.pos.getY(), other.pos.getY());
            if (result != 0) {
                return result;
            }
            return Integer.compare(this.pos.getZ(), other.pos.getZ());
        }
    }

    /**
     * 单个扫描盒内按玩家位置的全局平方距离遍历方块。
     *
     * 三个轴分别按离玩家中心的距离排序，再用一个无 visited 集合的最小堆归并笛卡尔积。
     * 每个三轴索引组合只有唯一父节点，因此不会重复，也不需要为整个扫描盒保存访问标记。
     */
    private static final class BoxDistanceCursor {
        private static final int STATE_BITS = 21;
        private static final long STATE_MASK = (1L << STATE_BITS) - 1L;

        private final int[] xCoordinates;
        private final int[] yCoordinates;
        private final int[] zCoordinates;
        private final long[] xDistanceSqr;
        private final long[] yDistanceSqr;
        private final long[] zDistanceSqr;
        private long[] heap = new long[64];
        private int heapSize;
        private boolean complete;

        private BoxDistanceCursor(PrinterBox box, int centerX, int centerY, int centerZ) {
            this.xCoordinates = buildAxisCoordinates(box.minX, box.maxX, centerX);
            this.yCoordinates = buildAxisCoordinates(box.minY, box.maxY, centerY);
            this.zCoordinates = buildAxisCoordinates(box.minZ, box.maxZ, centerZ);
            this.xDistanceSqr = buildAxisDistances(this.xCoordinates, centerX);
            this.yDistanceSqr = buildAxisDistances(this.yCoordinates, centerY);
            this.zDistanceSqr = buildAxisDistances(this.zCoordinates, centerZ);
            if (this.xCoordinates.length == 0 || this.yCoordinates.length == 0 || this.zCoordinates.length == 0) {
                this.complete = true;
            } else {
                this.push(packState(0, 0, 0));
            }
        }

        boolean next(BlockPos.MutableBlockPos target) {
            if (this.complete) {
                return false;
            }
            if (this.heapSize == 0) {
                this.complete = true;
                return false;
            }

            long state = this.pop();
            int xIndex = xIndex(state);
            int yIndex = yIndex(state);
            int zIndex = zIndex(state);

            if (xIndex + 1 < this.xCoordinates.length) {
                this.push(packState(xIndex + 1, yIndex, zIndex));
            }
            if (xIndex == 0 && yIndex + 1 < this.yCoordinates.length) {
                this.push(packState(0, yIndex + 1, zIndex));
            }
            if (xIndex == 0 && yIndex == 0 && zIndex + 1 < this.zCoordinates.length) {
                this.push(packState(0, 0, zIndex + 1));
            }

            target.set(
                    this.xCoordinates[xIndex],
                    this.yCoordinates[yIndex],
                    this.zCoordinates[zIndex]
            );
            return true;
        }

        private void push(long state) {
            if (this.heapSize >= this.heap.length) {
                long[] expanded = new long[this.heap.length << 1];
                System.arraycopy(this.heap, 0, expanded, 0, this.heap.length);
                this.heap = expanded;
            }
            int index = this.heapSize++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                long parentState = this.heap[parent];
                if (this.compare(parentState, state) <= 0) {
                    break;
                }
                this.heap[index] = parentState;
                index = parent;
            }
            this.heap[index] = state;
        }

        private long pop() {
            long result = this.heap[0];
            long tail = this.heap[--this.heapSize];
            if (this.heapSize == 0) {
                return result;
            }

            int index = 0;
            int half = this.heapSize >>> 1;
            while (index < half) {
                int left = (index << 1) + 1;
                int right = left + 1;
                int child = left;
                if (right < this.heapSize && this.compare(this.heap[right], this.heap[left]) < 0) {
                    child = right;
                }
                if (this.compare(tail, this.heap[child]) <= 0) {
                    break;
                }
                this.heap[index] = this.heap[child];
                index = child;
            }
            this.heap[index] = tail;
            return result;
        }

        private int compare(long left, long right) {
            long leftDistance = this.distanceSqr(left);
            long rightDistance = this.distanceSqr(right);
            int result = Long.compare(leftDistance, rightDistance);
            if (result != 0) {
                return result;
            }

            long leftMaxAxisDistance = this.maxAxisDistanceSqr(left);
            long rightMaxAxisDistance = this.maxAxisDistanceSqr(right);
            result = Long.compare(leftMaxAxisDistance, rightMaxAxisDistance);
            if (result != 0) {
                return result;
            }
            return Long.compareUnsigned(left, right);
        }

        private long distanceSqr(long state) {
            return this.xDistanceSqr[xIndex(state)]
                    + this.yDistanceSqr[yIndex(state)]
                    + this.zDistanceSqr[zIndex(state)];
        }

        private long maxAxisDistanceSqr(long state) {
            return Math.max(
                    Math.max(this.xDistanceSqr[xIndex(state)], this.yDistanceSqr[yIndex(state)]),
                    this.zDistanceSqr[zIndex(state)]
            );
        }

        private static int[] buildAxisCoordinates(int min, int max, int center) {
            if (max < min) {
                return new int[0];
            }
            int[] coordinates = new int[max - min + 1];
<<<<<<< HEAD
            int pivot = Math.max(min, Math.min(max, center));
            int left = pivot;
            int right = pivot + 1;
            int index = 0;
            while (left >= min || right <= max) {
                if (left < min) {
                    coordinates[index++] = right++;
                    continue;
                }
                if (right > max) {
                    coordinates[index++] = left--;
                    continue;
                }
                long leftDistance = axisDistanceSqr(left, center);
                long rightDistance = axisDistanceSqr(right, center);
                if (leftDistance <= rightDistance) {
                    coordinates[index++] = left--;
                } else {
                    coordinates[index++] = right++;
                }
=======
            for (int index = 0; index < coordinates.length; index++) {
                coordinates[index] = min + index;
            }

            for (int index = 1; index < coordinates.length; index++) {
                int value = coordinates[index];
                long valueDistance = axisDistanceSqr(value, center);
                int insertionIndex = index;
                while (insertionIndex > 0) {
                    int previous = coordinates[insertionIndex - 1];
                    long previousDistance = axisDistanceSqr(previous, center);
                    if (previousDistance < valueDistance
                            || previousDistance == valueDistance && previous <= value) {
                        break;
                    }
                    coordinates[insertionIndex] = previous;
                    insertionIndex--;
                }
                coordinates[insertionIndex] = value;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            }
            return coordinates;
        }

        private static long[] buildAxisDistances(int[] coordinates, int center) {
            long[] distances = new long[coordinates.length];
            for (int index = 0; index < coordinates.length; index++) {
                distances[index] = axisDistanceSqr(coordinates[index], center);
            }
            return distances;
        }

        private static long axisDistanceSqr(int coordinate, int center) {
            long delta = coordinate - (long) center;
            return delta * delta;
        }

        private static long packState(int xIndex, int yIndex, int zIndex) {
            return (long) xIndex << STATE_BITS * 2
                    | (long) yIndex << STATE_BITS
                    | zIndex;
        }

        private static int xIndex(long state) {
            return (int) (state >>> STATE_BITS * 2);
        }

        private static int yIndex(long state) {
            return (int) (state >>> STATE_BITS & STATE_MASK);
        }

        private static int zIndex(long state) {
            return (int) (state & STATE_MASK);
        }
    }

    private record SectionNode(int x, int y, int z, long distanceSqr) implements Comparable<SectionNode> {
        @Override
        public int compareTo(SectionNode other) {
            int result = Long.compare(this.distanceSqr, other.distanceSqr);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(Math.abs(this.x), Math.abs(other.x));
            if (result != 0) {
                return result;
            }
            result = Integer.compare(Math.abs(this.z), Math.abs(other.z));
            if (result != 0) {
                return result;
            }
            result = Integer.compare(Math.abs(this.y), Math.abs(other.y));
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.x, other.x);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(this.z, other.z);
            if (result != 0) {
                return result;
            }
            return Integer.compare(this.y, other.y);
        }
    }

    private static final class SectionEntry {
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;
        private long worldScanTick = Long.MIN_VALUE;
        private long schematicScanTick = Long.MIN_VALUE;
        private short[] worldNonAir = ShortArrayBuilder.EMPTY;
        private short[] worldSolid = ShortArrayBuilder.EMPTY;
        private short[] worldFluid = ShortArrayBuilder.EMPTY;
        private short[] worldFillBase = ShortArrayBuilder.EMPTY;
        private short[] schematicNonAir = ShortArrayBuilder.EMPTY;
        private short[] allPositions = ShortArrayBuilder.EMPTY;
        private boolean worldScanIncludesFill;
        private WorldScanWork worldScanWork;

        private SectionEntry(int sectionX, int sectionY, int sectionZ) {
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
        }

        boolean ensure(
                ScanIntent intent,
                ClientLevel level,
                WorldSchematic schematic,
                long tickTime,
                BooleanSupplier shouldPause
        ) {
            if (intent == ScanIntent.PRINT) {
                this.ensureSchematic(schematic, tickTime);
                return true;
            }
            if (intent == ScanIntent.CUSTOM) {
                this.ensureAllPositions();
                return true;
            }
            return this.ensureWorld(intent, level, tickTime, shouldPause);
        }

        short[] candidates(ScanIntent intent, int phase) {
            return switch (intent) {
                case PRINT -> this.schematicNonAir;
                case MINE -> this.worldSolid;
                case FLUID -> this.worldFluid;
                case FILL -> this.worldFillBase;
                case CUSTOM -> this.allPositions;
            };
        }

        byte flagsFor(ScanIntent intent, int phase) {
            return switch (intent) {
                case PRINT -> (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.SCHEMATIC_NON_AIR);
                case MINE, CUSTOM -> ScanFlags.WORLD_NON_AIR;
                case FLUID -> (byte) (ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID);
                case FILL -> ScanFlags.BASE_FILL_TARGET;
            };
        }

        boolean isExpired(long tickTime) {
            if (this.worldScanWork != null) {
                return false;
            }
            boolean worldExpired = this.worldScanTick == Long.MIN_VALUE
                    || tickTime - this.worldScanTick > WORLD_SECTION_TTL_TICKS * 4L;
            boolean schematicExpired = this.schematicScanTick == Long.MIN_VALUE
                    || tickTime - this.schematicScanTick > SCHEMATIC_SECTION_TTL_TICKS * 2L;
            return worldExpired && schematicExpired && this.allPositions.length == 0;
        }

        private boolean ensureWorld(
                ScanIntent intent,
                ClientLevel level,
                long tickTime,
                BooleanSupplier shouldPause
        ) {
            boolean needsFillIndex = intent == ScanIntent.FILL;
            if (level == null) {
                this.worldNonAir = ShortArrayBuilder.EMPTY;
                this.worldSolid = ShortArrayBuilder.EMPTY;
                this.worldFluid = ShortArrayBuilder.EMPTY;
                this.worldFillBase = ShortArrayBuilder.EMPTY;
                this.worldScanIncludesFill = false;
                this.worldScanWork = null;
                this.worldScanTick = tickTime;
                return true;
            }
            if (this.worldScanWork == null
                    && this.worldScanTick != Long.MIN_VALUE
                    && tickTime - this.worldScanTick <= WORLD_SECTION_TTL_TICKS
                    && (!needsFillIndex || this.worldScanIncludesFill)) {
                return true;
            }

            if (this.worldScanWork == null) {
                this.worldScanWork = new WorldScanWork(this.sectionX, this.sectionY, this.sectionZ, needsFillIndex);
            }

            if (!this.worldScanWork.sample(level, shouldPause)) {
                return false;
            }
            WorldScanWork work = this.worldScanWork;
            this.worldScanWork = null;
            this.publishWorldScan(WorldSectionResult.analyze(work), tickTime);
            return true;
        }

        private void publishWorldScan(WorldSectionResult result, long tickTime) {
            this.worldNonAir = result.nonAir();
            this.worldSolid = result.solid();
            this.worldFluid = result.fluid();
            this.worldFillBase = result.fillBase();
            this.worldScanIncludesFill = result.includesFill();
            this.worldScanTick = tickTime;
        }

        private static boolean hasSampleFlag(byte sample, byte flag) {
            return (sample & flag) != 0;
        }

        private static boolean isFillPotential(byte sample) {
            return hasSampleFlag(sample, SAMPLE_AIR)
                    || hasSampleFlag(sample, SAMPLE_LIQUID_BLOCK)
                    || hasSampleFlag(sample, SAMPLE_REPLACEABLE);
        }

        private static boolean isFillSupport(byte sample) {
            return !hasSampleFlag(sample, SAMPLE_AIR)
                    && !hasSampleFlag(sample, SAMPLE_LIQUID_BLOCK)
                    && !hasSampleFlag(sample, SAMPLE_REPLACEABLE);
        }

        private record WorldSectionResult(
                short[] nonAir,
                short[] solid,
                short[] fluid,
                short[] fillBase,
                boolean includesFill
        ) {
            private static WorldSectionResult analyze(WorldScanWork work) {
                ShortArrayBuilder nonAir = new ShortArrayBuilder();
                ShortArrayBuilder solid = new ShortArrayBuilder();
                ShortArrayBuilder fluid = new ShortArrayBuilder();
                ShortArrayBuilder fillBase = work.includesFill ? new ShortArrayBuilder() : null;

                for (int localIndex = 0; localIndex < SECTION_VOLUME; localIndex++) {
                    int localX = localIndex & 15;
                    int localZ = localIndex >> 4 & 15;
                    int localY = localIndex >> 8 & 15;
                    byte sample = work.centerSample(localX, localY, localZ);
                    boolean hasFluid = hasSampleFlag(sample, SAMPLE_FLUID);
                    boolean liquidBlock = hasSampleFlag(sample, SAMPLE_LIQUID_BLOCK);
                    if (!hasSampleFlag(sample, SAMPLE_AIR)) {
                        nonAir.add(localIndex);
                        if (!liquidBlock) {
                            solid.add(localIndex);
                        }
                    }
                    if (hasFluid) {
                        fluid.add(localIndex);
                    }
                    if (work.includesFill
                            && isFillPotential(sample)
                            && work.hasFillSupportNeighbor(localX, localY, localZ)) {
                        fillBase.add(localIndex);
                    }
                }

                return new WorldSectionResult(
                        nonAir.toArray(),
                        solid.toArray(),
                        fluid.toArray(),
                        fillBase == null ? ShortArrayBuilder.EMPTY : fillBase.toArray(),
                        work.includesFill
                );
            }
        }

        private static final class WorldScanWork {
            private static final int FILL_SAMPLE_SIZE = SECTION_SIZE + 2;

            private final int baseX;
            private final int baseY;
            private final int baseZ;
            private final boolean includesFill;
            private final int sampleSize;
            private final int sampleVolume;
            private final byte[] samples;
            private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            private int sampleIndex;
            private boolean sampled;

            private WorldScanWork(int sectionX, int sectionY, int sectionZ, boolean includesFill) {
                this.baseX = sectionX << 4;
                this.baseY = sectionY << 4;
                this.baseZ = sectionZ << 4;
                this.includesFill = includesFill;
                this.sampleSize = includesFill ? FILL_SAMPLE_SIZE : SECTION_SIZE;
                this.sampleVolume = this.sampleSize * this.sampleSize * this.sampleSize;
                this.samples = new byte[this.sampleVolume];
            }

            private boolean sample(ClientLevel level, BooleanSupplier shouldPause) {
                if (this.sampled) {
                    return true;
                }
                while (this.sampleIndex < this.sampleVolume) {
                    if (this.sampleIndex > 0
                            && this.sampleIndex % SECTION_SCAN_BUDGET_CHECK_INTERVAL == 0
                            && shouldPause.getAsBoolean()) {
                        return false;
                    }
                    int index = this.sampleIndex++;
                    int localX = index % this.sampleSize;
                    int localZ = index / this.sampleSize % this.sampleSize;
                    int localY = index / (this.sampleSize * this.sampleSize);
                    if (this.includesFill) {
                        localX--;
                        localY--;
                        localZ--;
                    }
                    this.mutable.set(this.baseX + localX, this.baseY + localY, this.baseZ + localZ);
                    this.samples[index] = sample(level.getBlockState(this.mutable));
                }
                this.sampled = true;
                return true;
            }

            private byte centerSample(int localX, int localY, int localZ) {
                return this.samples[this.sampleIndex(localX, localY, localZ)];
            }

            private boolean hasFillSupportNeighbor(int localX, int localY, int localZ) {
                for (Direction direction : DIRECTIONS) {
                    if (isFillSupport(this.samples[this.sampleIndex(
                            localX + direction.getStepX(),
                            localY + direction.getStepY(),
                            localZ + direction.getStepZ()
                    )])) {
                        return true;
                    }
                }
                return false;
            }

            private int sampleIndex(int localX, int localY, int localZ) {
                int x = this.includesFill ? localX + 1 : localX;
                int y = this.includesFill ? localY + 1 : localY;
                int z = this.includesFill ? localZ + 1 : localZ;
                return y * this.sampleSize * this.sampleSize + z * this.sampleSize + x;
            }

            private static byte sample(net.minecraft.world.level.block.state.BlockState state) {
                byte sample = 0;
                boolean liquidBlock = state.getBlock() instanceof LiquidBlock;
                if (state.isAir()) {
                    sample |= SAMPLE_AIR;
                }
                if (liquidBlock) {
                    sample |= SAMPLE_LIQUID_BLOCK;
                }
                if (!state.getFluidState().isEmpty()) {
                    sample |= SAMPLE_FLUID;
                }
                if (BlockUtils.isReplaceable(state)) {
                    sample |= SAMPLE_REPLACEABLE;
                }
                return sample;
            }
        }

        private void ensureSchematic(WorldSchematic schematic, long tickTime) {
            if (schematic == null) {
                this.schematicNonAir = ShortArrayBuilder.EMPTY;
                this.schematicScanTick = tickTime;
                return;
            }
            if (this.schematicScanTick != Long.MIN_VALUE && tickTime - this.schematicScanTick <= SCHEMATIC_SECTION_TTL_TICKS) {
                return;
            }

            ShortArrayBuilder nonAir = new ShortArrayBuilder();
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            int baseX = this.sectionX << 4;
            int baseY = this.sectionY << 4;
            int baseZ = this.sectionZ << 4;

            for (int localY = 0; localY < SECTION_SIZE; localY++) {
                int y = baseY + localY;
                for (int localZ = 0; localZ < SECTION_SIZE; localZ++) {
                    int z = baseZ + localZ;
                    for (int localX = 0; localX < SECTION_SIZE; localX++) {
                        int x = baseX + localX;
                        mutable.set(x, y, z);
                        if (!schematic.getBlockState(mutable).isAir()) {
                            nonAir.add(localIndex(x, y, z));
                        }
                    }
                }
            }

            this.schematicNonAir = nonAir.toArray();
            this.schematicScanTick = tickTime;
        }

        private void ensureAllPositions() {
            if (this.allPositions.length == SECTION_VOLUME) {
                return;
            }
            ShortArrayBuilder all = new ShortArrayBuilder(SECTION_VOLUME);
            for (int index = 0; index < SECTION_VOLUME; index++) {
                all.add(index);
            }
            this.allPositions = all.toArray();
        }
    }

    private static final class MutableScanMetrics {
        private long scanNanos;
        private int scannedBlocks;
        private int scannedSections;
        private final LongSet scannedSectionKeys = new LongOpenHashSet();
        private int sourceCandidates;
        private int acceptedTargets;
        private int budgetPauses;
        private int completedPasses;

        private void reset() {
            this.scanNanos = 0L;
            this.scannedBlocks = 0;
            this.scannedSections = 0;
            this.scannedSectionKeys.clear();
            this.sourceCandidates = 0;
            this.acceptedTargets = 0;
            this.budgetPauses = 0;
            this.completedPasses = 0;
        }

        private ScanMetrics snapshot() {
            return new ScanMetrics(
                    this.scanNanos,
                    this.scannedBlocks,
                    this.scannedSections,
                    this.sourceCandidates,
                    this.acceptedTargets,
                    this.budgetPauses,
                    this.completedPasses
            );
        }

        private void recordScannedSection(long sectionKey) {
            if (this.scannedSectionKeys.add(sectionKey)) {
                this.scannedSections++;
            }
        }
    }

    private static final class ShortArrayBuilder {
        private static final short[] EMPTY = new short[0];

        private short[] values;
        private int size;

        private ShortArrayBuilder() {
            this(32);
        }

        private ShortArrayBuilder(int capacity) {
            this.values = new short[Math.max(1, capacity)];
        }

        void add(int value) {
            if (this.size >= this.values.length) {
                short[] expanded = new short[this.values.length << 1];
                System.arraycopy(this.values, 0, expanded, 0, this.values.length);
                this.values = expanded;
            }
            this.values[this.size++] = (short) value;
        }

        short[] toArray() {
            if (this.size == 0) {
                return EMPTY;
            }
            short[] result = new short[this.size];
            System.arraycopy(this.values, 0, result, 0, this.size);
            return result;
        }
    }
}
