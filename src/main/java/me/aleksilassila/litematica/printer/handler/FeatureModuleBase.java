package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public abstract class FeatureModuleBase extends ConfigUtils implements RuntimeComponent {
    @Nullable
    public final AtomicReference<PrinterBox> playerInteractionBox;
    @Nullable
    private final AtomicReference<PrinterBox> externalScanBoxRef;
    private final InteractionBoxTracker interactionBoxTracker;
    private final GuiBlockInfoBuffer guiBlockInfoBuffer = new GuiBlockInfoBuffer();
    private final ModuleScanCoordinator scanCoordinator;
    private final ModuleSelectionScope selectionScope;
    private final ModuleRuntimeLoop runtimeLoop;
    private final String id;
    protected final PrinterRuntime runtime;
    protected final ScanEngine scanEngine;
    protected final ActionPort actionBroker;
    protected final CooldownUtils cooldownUtils;
    protected final RttReplayController rttReplayController;
    protected final HudStatsManager hudStats;
    protected final MissingMaterialTracker missingMaterials;
    protected final LitematicaAdapter litematica;
    final InventoryAvailabilityTracker inventoryAvailability;
    @Nullable
    final PrintModeType printMode;
    @Nullable
    final ConfigBoolean enableConfig;
    @Nullable
    private final ConfigOptionList selectionType;
    private boolean iterationConsumedEffectiveExecution = true;

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

    public ScanState getScanState() {
        return this.scanCoordinator.state();
    }

    public final String getId() { return this.id; }

    public int getPendingDirtyRegionCount() {
        return this.scanCoordinator.pendingDirtyRegionCount();
    }

    protected FeatureModuleBase(
            PrinterRuntime runtime,
            String id,
            @Nullable PrintModeType printMode,
            @Nullable ConfigBoolean enableConfig,
            @Nullable ConfigOptionList selectionType,
            boolean useBox
    ) {
        this.runtime = runtime;
        this.scanEngine = runtime.scanEngine();
        this.actionBroker = runtime.actionBroker();
        this.cooldownUtils = runtime.cooldownUtils();
        this.rttReplayController = runtime.rttReplayController();
        this.hudStats = runtime.hudStats();
        this.missingMaterials = runtime.missingMaterials();
        this.litematica = runtime.litematica();
        this.inventoryAvailability = runtime.inventoryAvailability();
        this.id = id;
        this.printMode = printMode;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.interactionBoxTracker = new InteractionBoxTracker(useBox);
        this.playerInteractionBox = this.interactionBoxTracker.getBoxReference();
        this.externalScanBoxRef = this.playerInteractionBox == null ? null : new AtomicReference<>();
        this.scanCoordinator = new ModuleScanCoordinator(
                new ModuleScanHost(this), this.externalScanBoxRef, this.scanEngine);
        this.selectionScope = new ModuleSelectionScope(this, selectionType);
        this.runtimeLoop = new ModuleRuntimeLoop(this);
        this.updateVariables(TickContext.capture());
    }

    @Nullable
    public AtomicReference<PrinterBox> getBoxRef() {
        return this.externalScanBoxRef;
    }

    public final void resetRuntimeState() {
        this.updateVariables(TickContext.capture());
        this.resetScanRuntime();
        this.resetPlayerTracking();
        this.guiBlockInfoBuffer.resetForTracking(false);
        this.iterationConsumedEffectiveExecution = true;
        this.clearScanSourceCache();
        this.runtimeLoop.reset();
        this.onRuntimeReset();
    }

    public void tick(TickContext context) {
        this.runtimeLoop.tick(context);
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

    void resetPlayerTracking() {
        this.interactionBoxTracker.resetPlayerTracking();
    }

    void updatePlayerInteractionBox() {
        this.interactionBoxTracker.update(this.player);
    }

    boolean runIterationIfNeeded() {
        if (this.playerInteractionBox == null || !this.canExecute()) {
            return false;
        }
        PrinterBox playerInteractionBox = this.playerInteractionBox.get();
        if (playerInteractionBox == null || !this.canIterate()) {
            return false;
        }
        return this.scanCoordinator.run(playerInteractionBox);
    }

    @Override
    public final void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.resetRuntimeState();
    }

    protected final void requestFullScan() {
        this.scanCoordinator.requestFullScan();
    }

    void clearScanSourceCache() {
        this.selectionScope.clearCache();
    }

    void resetScanRuntime() {
        this.scanEngine.resetOwner(this.id);
        this.scanCoordinator.reset();
    }

    public final IterationOutcome runIteration(PrinterBox playerInteractionBox) {
        return ModuleIterationRunner.run(this, playerInteractionBox);
    }

    public record IterationOutcome(
            boolean interrupt,
            boolean didWork,
            boolean foundCandidate,
            boolean completedPass,
            boolean scanPaused
    ) {
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

    GuiBlockInfoBuffer guiBuffer() {
        return this.guiBlockInfoBuffer;
    }

    @Nullable
    GuiBlockInfo createGuiBlockInfo(boolean enabled, BlockPos pos) {
        if (!enabled) {
            return null;
        }
        if (isSchematicBlockHandler()) {
            WorldSchematic schematic = this.litematica.schematicWorld();
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

    boolean isConfigAllowExecute() {
        return ModuleEnablePolicy.allows(this);
    }

    boolean isSchematicBlock(BlockPos pos) {
        return this.litematica.isSchematicBlock(pos);
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
        return ModuleCandidateSource.raw(this, playerInteractionBox, candidatePredicate);
    }

    protected Iterable<BlockPos> getCachedFilteredIterationPositions(PrinterBox playerInteractionBox, ScanIntent intent, Predicate<BlockPos> candidatePredicate) {
        return ModuleCandidateSource.cached(this, playerInteractionBox, intent, candidatePredicate);
    }

    @Nullable
    protected PrinterBox getScanSourceBox(PrinterBox playerInteractionBox) {
        return this.selectionScope.enclosingBox(playerInteractionBox);
    }

    protected List<PrinterBox> getScanSourceBoxes(PrinterBox playerInteractionBox) {
        return this.selectionScope.boxes(playerInteractionBox);
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

    /** Targets that can be consumed without discovering another scan batch. */
    protected boolean hasRunnableIterationWork() {
        return this.hasPendingIterationWork();
    }

    /** Targets waiting for a block update, material, or another resource. */
    protected boolean hasWaitingIterationWork() {
        return false;
    }

    /** Inventory gains wake only the feature that explicitly owns a waiting target. */
    protected void onInventoryAvailabilityChanged() {
    }

    public int getPendingIterationWorkCount() {
        return 0;
    }

    public boolean usesDirtyRegionWakeup() {
        return true;
    }

    protected boolean canReachIterationPosition(BlockPos pos) {
        return ConfigUtils.canInteracted(pos);
    }

    protected Predicate<BlockPos> createScanReachPredicate() {
        return ConfigUtils.createCanInteractPredicate();
    }

    protected boolean isInSelectionRange(BlockPos pos) {
        return this.selectionScope.contains(pos);
    }

    protected Predicate<BlockPos> createSelectionRangePredicate() {
        return this.selectionScope.predicate();
    }

    public boolean canIterationBlockPos(BlockPos pos) {
        return true;
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    protected final void setIterationConsumedEffectiveExecution(boolean consumed) {
        this.iterationConsumedEffectiveExecution = consumed;
    }

    final void beginEffectiveExecution() {
        this.iterationConsumedEffectiveExecution = true;
    }

    final boolean consumedEffectiveExecution() {
        return this.iterationConsumedEffectiveExecution;
    }

    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return this.cooldownUtils.isOnCooldown(this.level, this.getId(), pos);
    }

    public boolean isBlockPosOnCooldown(String name, @Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return this.cooldownUtils.isOnCooldown(this.level, this.getId() + "_" + name, pos);
    }

    public void setBlockPosCooldown(@Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        this.cooldownUtils.setCooldown(this.level, this.getId(), pos, cooldownTicks);
    }

    public void setBlockPosCooldown(String name, @Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        this.cooldownUtils.setCooldown(this.level, this.getId() + "_" + name, pos, cooldownTicks);
    }

    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return getPlayerOrderedByNearest()[0].getOpposite();
    }
}
