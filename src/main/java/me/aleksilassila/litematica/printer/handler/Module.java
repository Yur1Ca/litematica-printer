package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.scan.DirtyRegionTracker;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public abstract class Module extends ConfigUtils {
    private static final int ITERATION_BUDGET_CHECK_INTERVAL = 8;

    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> playerInteractionBox;
    @Nullable
    private final AtomicReference<PrinterBox> externalScanBoxRef;
    private final InteractionBoxTracker interactionBoxTracker;
    private final GuiBlockInfoBuffer guiBlockInfoBuffer = new GuiBlockInfoBuffer();
    @Getter
    private final String id;
    @Getter
    @Nullable
    private final PrintModeType printMode;
    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;
    @Getter
    @Nullable
    private final ConfigOptionList selectionType;
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);
    private boolean iterationConsumedEffectiveExecution = true;
    @Getter
    private ScanState scanState = ScanState.FULL;
    @Getter
    private int pendingDirtyRegionCount;
    private int idleScanTicks;
    @Nullable
    private PrinterBox lastScanSourceBox;
    private List<PrinterBox> lastScanSourceBoxes = List.of();
    private long lastDirtyVersion;
    private final ArrayDeque<PrinterBox> dirtyScanQueue = new ArrayDeque<>();
    @Nullable
    private PrinterBox activeDirtyScanBox;
    private boolean currentIterationDidWork;
    private boolean currentIterationFoundCandidate;
<<<<<<< HEAD
    private int lazyProbeTicks;
    private boolean inventoryFingerprintInitialized;
    private int lastInventoryFingerprint;
    private boolean schematicIdentityInitialized;
    @Nullable
    private Object lastSchematicIdentity;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;

    private long lastTickTime = -1L;

    protected Module(String id, @Nullable PrintModeType printMode, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.printMode = printMode;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.interactionBoxTracker = new InteractionBoxTracker(useBox);
        this.playerInteractionBox = this.interactionBoxTracker.getBoxReference();
        this.externalScanBoxRef = this.playerInteractionBox == null ? null : new AtomicReference<>();
        this.updateVariables();
    }

    @Nullable
    public AtomicReference<PrinterBox> getBoxRef() {
        return this.externalScanBoxRef;
    }

    protected void updateVariables() {
        this.updateVariables(TickContext.capture());
    }

    public final void resetRuntimeState() {
        this.updateVariables();
        this.resetScanRuntime();
        this.resetPlayerTracking();
        this.guiBlockInfoBuffer.resetForTracking(false);
        this.skipIteration.set(false);
        this.iterationConsumedEffectiveExecution = true;
        this.currentIterationDidWork = false;
        this.currentIterationFoundCandidate = false;
<<<<<<< HEAD
        this.lazyProbeTicks = 0;
        this.inventoryFingerprintInitialized = false;
        this.lastInventoryFingerprint = 0;
        this.schematicIdentityInitialized = false;
        this.lastSchematicIdentity = null;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        this.lastTickTime = -1L;
        this.onRuntimeReset();
    }

    public void tick() {
        this.tick(TickContext.capture());
    }

    public void tick(TickContext context) {
        this.guiBlockInfoBuffer.tickCache();
        if (this.shouldSkipByTickInterval(context)) {
            return;
        }
        if (!isEnable()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        this.updateVariables(context);
        if (!this.hasRequiredClientState()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
<<<<<<< HEAD
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        ScanCache.INSTANCE.beginTick(this.level, schematic, context.gameTime);
        this.wakeForSchematicChange(schematic);
        this.updatePlayerInteractionBox();
        this.preprocess(); // 运行前处理的事情
        this.wakeForInventoryChange();
=======
        ScanCache.INSTANCE.beginTick(this.level, SchematicWorldHandler.getSchematicWorld(), context.gameTime);
        this.updatePlayerInteractionBox();
        this.preprocess(); // 运行前处理的事情
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (!this.isConfigAllowExecute()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        boolean interrupt = this.runIterationIfNeeded();
        if (!interrupt) {
            this.resetPlayerTracking();
        }
    }

    protected void updateVariables(TickContext context) {
        this.mc = context.mc;
        this.level = context.level;
        this.player = context.player;
        this.connection = context.connection;
        this.gameMode = context.gameMode;
        this.gameType = context.gameType;
        this.hitResult = context.hitResult;
        this.blockHitResult = context.blockHitResult;
    }

    private boolean shouldSkipByTickInterval(TickContext context) {
        int tickInterval = this.getTickInterval();
        if (tickInterval <= 0) {
            return false;
        }
        long currentTickTime = context.gameTime;
        if (this.lastTickTime != -1L && currentTickTime - this.lastTickTime < tickInterval) {
            return true;
        }
        this.lastTickTime = currentTickTime;
        return false;
    }

    private boolean hasRequiredClientState() {
        return this.mc != null
                && this.level != null
                && this.player != null
                && this.connection != null
                && this.gameMode != null
                && this.gameType != null;
    }

    private void resetPlayerTracking() {
        this.interactionBoxTracker.resetPlayerTracking();
    }

    private void updatePlayerInteractionBox() {
        this.interactionBoxTracker.update(this.player);
    }

<<<<<<< HEAD
    private void wakeForInventoryChange() {
        int fingerprint = 1;
        for (int slot = 0; slot < this.player.getInventory().getContainerSize(); slot++) {
            fingerprint = 31 * fingerprint
                    + this.player.getInventory().getItem(slot).getItem().hashCode();
        }
        if (!this.inventoryFingerprintInitialized) {
            this.inventoryFingerprintInitialized = true;
            this.lastInventoryFingerprint = fingerprint;
            return;
        }
        if (this.lastInventoryFingerprint == fingerprint) {
            return;
        }
        this.lastInventoryFingerprint = fingerprint;
        ScanCache.INSTANCE.resetOwner(this.id);
        this.requestFullScan();
    }

    private void wakeForSchematicChange(@Nullable WorldSchematic schematic) {
        if (!this.schematicIdentityInitialized) {
            this.schematicIdentityInitialized = true;
            this.lastSchematicIdentity = schematic;
            return;
        }
        if (this.lastSchematicIdentity == schematic) {
            return;
        }
        this.lastSchematicIdentity = schematic;
        if (this.isSchematicBlockHandler()) {
            this.requestFullScan();
        }
    }

=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    private boolean runIterationIfNeeded() {
        if (this.playerInteractionBox == null || !this.canExecute()) {
            this.updateExternalScanBox(null);
            return false;
        }
        PrinterBox playerInteractionBox = this.playerInteractionBox.get();
        if (playerInteractionBox == null || !this.canIterate()) {
            this.updateExternalScanBox(null);
            return false;
        }
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        PrinterBox scanSourceBox = enclosingBox(scanSourceBoxes);
        if (scanSourceBox == null) {
            this.updateExternalScanBox(null);
            this.lastScanSourceBox = null;
            this.lastScanSourceBoxes = List.of();
            return false;
        }
        this.updateExternalScanBox(scanSourceBox);
        this.updateScanSource(scanSourceBox, scanSourceBoxes);
        if (!this.isLazyScanEnabled()) {
            this.scanState = ScanState.FULL;
            this.clearDirtyScanQueue();
            return this.runFullIteration(playerInteractionBox);
        }
        return switch (this.scanState) {
            case FULL -> this.runFullIteration(playerInteractionBox);
            case PARTIAL -> this.runPartialIteration(playerInteractionBox);
            case LAZY -> this.runLazyIteration(playerInteractionBox);
        };
    }

    private void updateScanSource(PrinterBox scanSourceBox, List<PrinterBox> scanSourceBoxes) {
        boolean boxesChanged = !this.lastScanSourceBoxes.equals(scanSourceBoxes);
        if (scanSourceBox.equals(this.lastScanSourceBox) && !boxesChanged) {
            return;
        }
        this.lastScanSourceBoxes = List.copyOf(scanSourceBoxes);
        if (!boxesChanged
                && this.lastScanSourceBox != null
                && this.lastScanSourceBox.sameSectionWindow(scanSourceBox)) {
            PrinterBox previousScanSourceBox = this.lastScanSourceBox;
            this.lastScanSourceBox = scanSourceBox;
            if (this.scanState == ScanState.LAZY) {
                if (this.usesDirtyRegionWakeup()) {
                    this.queueNewlyExposedScanRegions(previousScanSourceBox, scanSourceBox);
                } else {
                    this.scanState = ScanState.FULL;
                    this.idleScanTicks = 0;
                    this.clearDirtyScanQueue();
                }
            }
            return;
        }
        this.lastScanSourceBox = scanSourceBox;
        this.scanState = ScanState.FULL;
        this.idleScanTicks = 0;
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private boolean runFullIteration(PrinterBox playerInteractionBox) {
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        this.updateFullScanIdleState(interrupt);
        return interrupt;
    }

    private boolean runLazyIteration(PrinterBox playerInteractionBox) {
        if (this.usesDirtyRegionWakeup() && this.scanState == ScanState.LAZY) {
            this.refreshDirtyScanQueue(playerInteractionBox);
        }
        if (this.scanState == ScanState.LAZY) {
<<<<<<< HEAD
            int fallbackProbeInterval = Math.max(40, Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() * 10);
            if (++this.lazyProbeTicks < fallbackProbeInterval) {
                return false;
            }
            this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            return this.runLazyProbeIteration(playerInteractionBox);
        }
        if (this.scanState == ScanState.FULL) {
            return this.runFullIteration(playerInteractionBox);
        }
        return this.runPartialIteration(playerInteractionBox);
    }

    private void updateExternalScanBox(@Nullable PrinterBox scanSourceBox) {
        if (this.externalScanBoxRef != null) {
            this.externalScanBoxRef.set(scanSourceBox);
        }
    }

    private boolean runLazyProbeIteration(PrinterBox playerInteractionBox) {
        this.pendingDirtyRegionCount = 0;
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        if (this.currentIterationDidWork || this.currentIterationFoundCandidate) {
            this.scanState = ScanState.FULL;
            this.idleScanTicks = 0;
<<<<<<< HEAD
            this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            return interrupt;
        }
        this.scanState = ScanState.LAZY;
        return true;
    }

    private boolean runPartialIteration(PrinterBox playerInteractionBox) {
        if (this.activeDirtyScanBox == null) {
            if (this.dirtyScanQueue.isEmpty()) {
                this.refreshDirtyScanQueue(playerInteractionBox);
                if (this.scanState == ScanState.LAZY) {
                    return false;
                }
                if (this.scanState == ScanState.FULL) {
                    return this.runFullIteration(playerInteractionBox);
                }
            }
            this.activeDirtyScanBox = this.dirtyScanQueue.pollFirst();
        }

        if (this.activeDirtyScanBox == null) {
            this.scanState = ScanState.LAZY;
            this.pendingDirtyRegionCount = 0;
            return false;
        }

        PrinterBox boundedDirtyBox = intersect(playerInteractionBox, this.activeDirtyScanBox);
        if (boundedDirtyBox == null || this.getScanSourceBox(boundedDirtyBox) == null) {
            this.activeDirtyScanBox = null;
            this.updatePartialScanState(false);
            return this.hasPendingPartialScan();
        }

        boolean interrupt = this.runIterationLoop(boundedDirtyBox);
        if (!interrupt) {
            this.activeDirtyScanBox = null;
        }
        this.updatePartialScanState(interrupt);
        return interrupt || this.hasPendingPartialScan();
    }

    private void refreshDirtyScanQueue(PrinterBox playerInteractionBox) {
        DirtyRegionTracker.DirtySnapshot snapshot = DirtyRegionTracker.INSTANCE.snapshotAfter(this.lastDirtyVersion, playerInteractionBox);
        this.lastDirtyVersion = snapshot.version();
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;

        List<PrinterBox> dirtyBoxes = new ArrayList<>();
        for (PrinterBox dirtyBox : snapshot.boxes()) {
            PrinterBox boundedDirtyBox = intersect(playerInteractionBox, dirtyBox);
            if (boundedDirtyBox != null && this.getScanSourceBox(boundedDirtyBox) != null) {
                dirtyBoxes.add(boundedDirtyBox);
            }
        }
        dirtyBoxes.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
        this.dirtyScanQueue.addAll(dirtyBoxes);

        this.pendingDirtyRegionCount = this.dirtyScanQueue.size();
        if (this.dirtyScanQueue.isEmpty()) {
            this.scanState = ScanState.LAZY;
            return;
        }
        this.scanState = ScanState.PARTIAL;
    }

    private void updateFullScanIdleState(boolean interrupt) {
        if (interrupt) {
            this.idleScanTicks = 0;
            return;
        }
        if (this.currentIterationDidWork || this.currentIterationFoundCandidate) {
            this.idleScanTicks = 0;
            return;
        }
        int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        if (lazyThreshold <= 0) {
            return;
        }
        if (++this.idleScanTicks >= lazyThreshold) {
            this.scanState = ScanState.LAZY;
            this.idleScanTicks = 0;
<<<<<<< HEAD
            this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            this.clearDirtyScanQueue();
        }
    }

    private void updatePartialScanState(boolean interrupt) {
        this.pendingDirtyRegionCount = this.dirtyScanQueue.size() + (this.activeDirtyScanBox == null ? 0 : 1);
        if (interrupt) {
            return;
        }
        if (!this.hasPendingPartialScan()) {
            this.scanState = ScanState.LAZY;
            this.idleScanTicks = 0;
<<<<<<< HEAD
            this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            this.pendingDirtyRegionCount = 0;
        }
    }

    private boolean hasPendingPartialScan() {
        return this.activeDirtyScanBox != null || !this.dirtyScanQueue.isEmpty();
    }

    private boolean isLazyScanEnabled() {
        return Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() > 0;
    }

    private void clearDirtyScanQueue() {
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;
        this.pendingDirtyRegionCount = 0;
    }

    private void queueNewlyExposedScanRegions(PrinterBox previous, PrinterBox current) {
        int overlapMinX = Math.max(previous.minX, current.minX);
        int overlapMinY = Math.max(previous.minY, current.minY);
        int overlapMinZ = Math.max(previous.minZ, current.minZ);
        int overlapMaxX = Math.min(previous.maxX, current.maxX);
        int overlapMaxY = Math.min(previous.maxY, current.maxY);
        int overlapMaxZ = Math.min(previous.maxZ, current.maxZ);
        if (overlapMinX > overlapMaxX || overlapMinY > overlapMaxY || overlapMinZ > overlapMaxZ) {
            this.scanState = ScanState.FULL;
            this.idleScanTicks = 0;
            this.clearDirtyScanQueue();
            return;
        }

        this.addDirtyScanBox(current.minX, current.minY, current.minZ,
                overlapMinX - 1, current.maxY, current.maxZ);
        this.addDirtyScanBox(overlapMaxX + 1, current.minY, current.minZ,
                current.maxX, current.maxY, current.maxZ);
        this.addDirtyScanBox(overlapMinX, current.minY, current.minZ,
                overlapMaxX, overlapMinY - 1, current.maxZ);
        this.addDirtyScanBox(overlapMinX, overlapMaxY + 1, current.minZ,
                overlapMaxX, current.maxY, current.maxZ);
        this.addDirtyScanBox(overlapMinX, overlapMinY, current.minZ,
                overlapMaxX, overlapMaxY, overlapMinZ - 1);
        this.addDirtyScanBox(overlapMinX, overlapMinY, overlapMaxZ + 1,
                overlapMaxX, overlapMaxY, current.maxZ);

        if (!this.dirtyScanQueue.isEmpty()) {
            List<PrinterBox> sorted = new ArrayList<>(this.dirtyScanQueue);
            sorted.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
            this.dirtyScanQueue.clear();
            this.dirtyScanQueue.addAll(sorted);
            this.pendingDirtyRegionCount = this.dirtyScanQueue.size();
            this.scanState = ScanState.PARTIAL;
            this.idleScanTicks = 0;
        }
    }

    private void addDirtyScanBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
            this.dirtyScanQueue.addLast(new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    protected final void requestFullScan() {
        this.scanState = ScanState.FULL;
        this.idleScanTicks = 0;
<<<<<<< HEAD
        this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        this.clearDirtyScanQueue();
    }

    private void resetScanRuntime() {
        this.scanState = ScanState.FULL;
        this.idleScanTicks = 0;
<<<<<<< HEAD
        this.lazyProbeTicks = 0;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        this.lastScanSourceBox = null;
        this.lastScanSourceBoxes = List.of();
        this.updateExternalScanBox(null);
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private double distanceToPlayerSqr(PrinterBox box) {
        if (this.player == null) {
            return 0.0D;
        }
        double dx = axisDistance(this.player.getX(), box.minX, box.maxX);
        double dy = axisDistance(this.player.getEyeY(), box.minY, box.maxY);
        double dz = axisDistance(this.player.getZ(), box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    private boolean runIterationLoop(PrinterBox playerInteractionBox) {
        int maxEffectiveExec = this.getMaxEffectiveExecutionsPerTick();
        int scanGuardLimit = this.getScanGuardLimit();
        int totalIterCount = 0;
        int effectiveExecCount = 0;
        int iterationBudgetChecks = 0;
        long iterationStartNanos = System.nanoTime();
        long actionExecutionNanos = 0L;
        long iterationBudgetNanos = Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
        boolean interrupt = false;
        boolean trackGuiBlockInfo = this.shouldTrackGuiBlockInfo();
        boolean prefilteredReachAndSelection = this.iterationPositionsPrefilterReachAndSelection();
        boolean prefilteredCooldown = this.iterationPositionsPrefilterCooldown();
        boolean exactCandidates = this.iterationPositionsAreExactCandidates();
        this.skipIteration.set(false);
        this.currentIterationDidWork = false;
        this.currentIterationFoundCandidate = false;
        this.guiBlockInfoBuffer.resetForTracking(trackGuiBlockInfo);

        Iterable<BlockPos> iterationPositions = this.getIterationPositions(playerInteractionBox);
        for (BlockPos pos : iterationPositions) {
            if (++iterationBudgetChecks % ITERATION_BUDGET_CHECK_INTERVAL == 0
                    && System.nanoTime() - iterationStartNanos - actionExecutionNanos >= iterationBudgetNanos) {
                interrupt = true;
                break;
            }
            if (scanGuardLimit > 0 && totalIterCount++ >= scanGuardLimit) {
                interrupt = true;
                break;
            }
            if (this.skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                interrupt = true;
                break;
            }
            if (pos == null) {
                interrupt = true;
                break;
            }
            GuiBlockInfo gui = this.createGuiBlockInfo(trackGuiBlockInfo, pos);
            if (prefilteredReachAndSelection || this.canReachIterationPosition(pos)) {
                if (gui != null) {
                    gui.interacted = true;
                }
            } else {
                if (gui != null) {
                    gui.interacted = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (isSchematicBlockHandler()) {
                if (!LitematicaUtils.isSchematicBlock(pos)) {
                    this.guiBlockInfoBuffer.add(gui);
                    continue;
                }
            }
            if (!prefilteredReachAndSelection && !this.isInSelectionRange(pos)) {
                if (gui != null) {
                    gui.posInSelectionRange = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (gui != null) {
                gui.posInSelectionRange = true;
            }
            if (!prefilteredCooldown && isBlockPosOnCooldown(pos)) {
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (exactCandidates || this.canIterationBlockPos(pos)) {
                this.currentIterationFoundCandidate = true;
                this.iterationConsumedEffectiveExecution = true;
                long actionStartNanos = System.nanoTime();
                try {
                    this.executeIteration(pos, this.skipIteration);
                } finally {
                    if (this.iterationConsumedEffectiveExecution) {
                        actionExecutionNanos += Math.max(0L, System.nanoTime() - actionStartNanos);
                    }
                }
                if (gui != null) {
                    gui.execute = true;
                }
                boolean consumedEffectiveExecution = this.iterationConsumedEffectiveExecution;
                if (this.skipIteration.get()
                        || maxEffectiveExec > 0 && consumedEffectiveExecution && ++effectiveExecCount >= maxEffectiveExec) {
                    interrupt = true;
                }
                if (consumedEffectiveExecution) {
                    this.currentIterationDidWork = true;
                }
            }
            this.guiBlockInfoBuffer.add(gui);
            if (interrupt) {
                break;
            }
        }
        stopIteration(interrupt);
        return interrupt;
    }

    protected void stopIteration(boolean interrupt) {
    }

    protected void onRuntimeReset() {
    }

    protected boolean isSchematicBlockHandler() {
        return false;
    }

    protected boolean requiresSelection1ModeRangeCheck() {
        return true;
    }

    protected boolean shouldTrackGuiBlockInfo() {
        return false;
    }

    @Nullable
    private GuiBlockInfo createGuiBlockInfo(boolean enabled, BlockPos pos) {
        if (!enabled) {
            return null;
        }
        if (isSchematicBlockHandler()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            return new GuiBlockInfo(level, schematic, pos);
        }
        return new GuiBlockInfo(level, null, pos);
    }

    @Nullable
    public GuiBlockInfo getCurrentRenderGuiBlockInfo() {
        return this.guiBlockInfoBuffer.current();
    }

    @Nullable
    public GuiBlockInfo getGuiBlockInfo() {
        return this.guiBlockInfoBuffer.latest();
    }

    public void setGuiBlockInfo(@Nullable GuiBlockInfo guiBlockInfo) {
        this.guiBlockInfoBuffer.add(guiBlockInfo);
    }

    public int getGuiBlockInfoQueueSize() {
        return this.guiBlockInfoBuffer.size();
    }

    public int getRenderIndex() {
        return this.guiBlockInfoBuffer.renderIndex();
    }

    private boolean isConfigAllowExecute() {
        // 全局打印机功能未启用，直接禁止所有处理器执行
        if (!ConfigUtils.isEnable()) {
            return false;
        }
        // 处理器绑定了模式和配置，按当前游戏模式校验
        if (this.printMode != null && this.enableConfig != null) {
            WorkingModeType modeType = (WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue();
            return switch (modeType) {
                case SINGLE -> Configs.Core.WORK_MODE_TYPE.getOptionListValue().equals(this.printMode);
                case MULTI -> this.enableConfig.getBooleanValue();
            };
        }
        // 仅绑定了启用配置，直接校验配置是否启用
        if (this.enableConfig != null) {
            return this.enableConfig.getBooleanValue();
        }
        // 无任何配置绑定，默认允许执行（由全局配置控制）
        return true;
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxEffectiveExecutionsPerTick() {
        return -1;
    }

    protected int getScanGuardLimit() {
        return 0;
    }

    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return false;
    }

    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    protected boolean iterationPositionsAreExactCandidates() {
        return false;
    }

    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        return playerInteractionBox;
    }

    protected Iterable<BlockPos> getFilteredIterationPositions(PrinterBox playerInteractionBox, Predicate<BlockPos> candidatePredicate) {
        return ScanCache.INSTANCE.rawIterable(
                this.id + "_raw",
                playerInteractionBox,
                this.player,
                this.getScanGuardLimit(),
                candidatePredicate
        );
    }

    protected Iterable<BlockPos> getCachedFilteredIterationPositions(PrinterBox playerInteractionBox, ScanIntent intent, Predicate<BlockPos> candidatePredicate) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return java.util.List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        return ScanCache.INSTANCE.iterable(
                this.id,
                scanSourceBoxes,
                this.level,
                SchematicWorldHandler.getSchematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                intent,
                candidatePredicate,
                pos -> this.canReachIterationPosition(pos) && selectionPredicate.test(pos)
        );
    }

    @Nullable
    protected PrinterBox getScanSourceBox(PrinterBox playerInteractionBox) {
        return enclosingBox(this.getScanSourceBoxes(playerInteractionBox));
    }

    protected List<PrinterBox> getScanSourceBoxes(PrinterBox playerInteractionBox) {
        if (playerInteractionBox == null) {
            return List.of();
        }

        List<PrinterBox> baseBoxes;
        if (isSchematicBlockHandler()) {
            baseBoxes = LitematicaUtils.createSchematicPlacementBoxes();
        } else if (requiresSelection1ModeRangeCheck()) {
            baseBoxes = LitematicaUtils.createSelection1Boxes();
        } else {
            baseBoxes = List.of(playerInteractionBox);
        }

        List<PrinterBox> result = new ArrayList<>(baseBoxes.size());
        for (PrinterBox baseBox : baseBoxes) {
            PrinterBox bounded = intersect(playerInteractionBox, baseBox);
            bounded = this.clampToConfiguredSelection(bounded);
            if (bounded != null) {
                result.add(bounded);
            }
        }
        return result;
    }

    @Nullable
    private PrinterBox clampToConfiguredSelection(@Nullable PrinterBox box) {
        if (box == null || this.selectionType == null) {
            return box;
        }
        if (!(this.selectionType.getOptionListValue() instanceof SelectionType selectionType)) {
            return null;
        }
        return switch (selectionType) {
            case LITEMATICA_SELECTION -> box;
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils.clampToRenderLayer(box);
            case LITEMATICA_SELECTION_BELOW_PLAYER -> this.player == null
                    ? null
                    : clipMaximumY(box, (int) Math.floor(this.player.getY()));
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> this.player == null
                    ? null
                    : clipMinimumY(box, (int) Math.ceil(this.player.getY()));
        };
    }

    @Nullable
    private static PrinterBox clipMaximumY(PrinterBox box, int maxY) {
        int clippedMaxY = Math.min(box.maxY, maxY);
        return clippedMaxY < box.minY
                ? null
                : new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, clippedMaxY, box.maxZ);
    }

    @Nullable
    private static PrinterBox clipMinimumY(PrinterBox box, int minY) {
        int clippedMinY = Math.max(box.minY, minY);
        return clippedMinY > box.maxY
                ? null
                : new PrinterBox(box.minX, clippedMinY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    @Nullable
    private static PrinterBox intersect(PrinterBox first, PrinterBox second) {
        int minX = Math.max(first.minX, second.minX);
        int minY = Math.max(first.minY, second.minY);
        int minZ = Math.max(first.minZ, second.minZ);
        int maxX = Math.min(first.maxX, second.maxX);
        int maxY = Math.min(first.maxY, second.maxY);
        int maxZ = Math.min(first.maxZ, second.maxZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Nullable
    private static PrinterBox enclosingBox(List<PrinterBox> boxes) {
        PrinterBox result = null;
        for (PrinterBox box : boxes) {
            if (result == null) {
                result = box;
            } else {
                result = new PrinterBox(
                        Math.min(result.minX, box.minX),
                        Math.min(result.minY, box.minY),
                        Math.min(result.minZ, box.minZ),
                        Math.max(result.maxX, box.maxX),
                        Math.max(result.maxY, box.maxY),
                        Math.max(result.maxZ, box.maxZ)
                );
            }
        }
        return result;
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    protected boolean hasPendingIterationWork() {
        return false;
    }

    public int getPendingIterationWorkCount() {
        return 0;
    }

    protected boolean usesDirtyRegionWakeup() {
        return true;
    }

    protected boolean canReachIterationPosition(BlockPos pos) {
        return ConfigUtils.canInteracted(pos);
    }

    protected boolean isInSelectionRange(BlockPos pos) {
        if (!isSchematicBlockHandler()
                && requiresSelection1ModeRangeCheck()
                && !LitematicaUtils.isWithinSelection1ModeRange(pos)) {
            return false;
        }
        return selectionType == null || ConfigUtils.isPositionInSelectionRange(player, pos, selectionType);
    }

    protected Predicate<BlockPos> createSelectionRangePredicate() {
        Predicate<BlockPos> selection1Predicate = isSchematicBlockHandler() || !requiresSelection1ModeRangeCheck()
                ? pos -> true
                : LitematicaUtils.createSelection1RangePredicate();
        Predicate<BlockPos> configuredSelectionPredicate = this.createConfiguredSelectionRangePredicate();
        return pos -> selection1Predicate.test(pos) && configuredSelectionPredicate.test(pos);
    }

    private Predicate<BlockPos> createConfiguredSelectionRangePredicate() {
        if (this.selectionType == null) {
            return pos -> true;
        }
        if (!(this.selectionType.getOptionListValue() instanceof SelectionType selectionType)) {
            return pos -> false;
        }
        return switch (selectionType) {
            case LITEMATICA_SELECTION -> pos -> true;
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils::isPositionWithinRange;
            case LITEMATICA_SELECTION_BELOW_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.floor(this.player.getY());
                yield pos -> pos.getY() <= playerY;
            }
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.ceil(this.player.getY());
                yield pos -> pos.getY() >= playerY;
            }
        };
    }

    public boolean canIterationBlockPos(BlockPos pos) {
        return true;
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    protected final void setIterationConsumedEffectiveExecution(boolean consumed) {
        this.iterationConsumedEffectiveExecution = consumed;
    }

    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId(), pos);
    }

    public boolean isBlockPosOnCooldown(String name, @Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId() + "_" + name, pos);
    }

    public void setBlockPosCooldown(@Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId(), pos, cooldownTicks);
    }

    public void setBlockPosCooldown(String name, @Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId() + "_" + name, pos, cooldownTicks);
    }

    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return getPlayerOrderedByNearest()[0].getOpposite();
    }
}
