package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import org.jetbrains.annotations.Nullable;

/** Client-thread lifecycle for one feature module. */
final class ModuleRuntimeLoop {
    private final FeatureModuleBase owner;
    private long lastTickTime = -1L;
    private boolean inventoryRevisionInitialized;
    private long lastInventoryGainRevision;
    private boolean schematicIdentityInitialized;
    @Nullable private Object lastSchematicIdentity;

    ModuleRuntimeLoop(FeatureModuleBase owner) {
        this.owner = owner;
    }

    void tick(TickContext context) {
        this.owner.guiBuffer().tickCache();
        if (this.shouldSkipByTickInterval(context)) return;
        if (!ConfigUtils.isEnable()) {
            this.owner.resetScanRuntime();
            this.owner.resetPlayerTracking();
            return;
        }
        this.owner.updateVariables(context);
        if (!this.hasRequiredClientState()) {
            this.owner.resetScanRuntime();
            this.owner.resetPlayerTracking();
            return;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        this.owner.scanEngine.beginTick(this.owner.level, schematic, context.gameTime);
        this.wakeForSchematicChange(schematic);
        this.owner.updatePlayerInteractionBox();
        this.owner.preprocess();
        this.wakeForInventoryChange();
        if (!this.owner.isConfigAllowExecute()) {
            this.owner.resetScanRuntime();
            this.owner.resetPlayerTracking();
            return;
        }
        if (!this.owner.runIterationIfNeeded()) {
            this.owner.resetPlayerTracking();
        }
    }

    void reset() {
        this.lastTickTime = -1L;
        this.inventoryRevisionInitialized = false;
        this.lastInventoryGainRevision = 0L;
        this.schematicIdentityInitialized = false;
        this.lastSchematicIdentity = null;
    }

    private boolean shouldSkipByTickInterval(TickContext context) {
        int interval = this.owner.getTickInterval();
        if (interval <= 0) return false;
        long current = context.gameTime;
        if (this.lastTickTime != -1L && current - this.lastTickTime < interval) return true;
        this.lastTickTime = current;
        return false;
    }

    private boolean hasRequiredClientState() {
        return this.owner.mc != null && this.owner.level != null && this.owner.player != null
                && this.owner.connection != null && this.owner.gameMode != null && this.owner.gameType != null;
    }

    private void wakeForInventoryChange() {
        long revision = this.owner.inventoryAvailability.gainRevision();
        if (!this.inventoryRevisionInitialized) {
            this.inventoryRevisionInitialized = true;
            this.lastInventoryGainRevision = revision;
            return;
        }
        if (this.lastInventoryGainRevision == revision) return;
        this.lastInventoryGainRevision = revision;
        this.owner.onInventoryAvailabilityChanged();
    }

    private void wakeForSchematicChange(@Nullable WorldSchematic schematic) {
        if (!this.schematicIdentityInitialized) {
            this.schematicIdentityInitialized = true;
            this.lastSchematicIdentity = schematic;
            return;
        }
        if (this.lastSchematicIdentity == schematic) return;
        this.lastSchematicIdentity = schematic;
        if (this.owner.isSchematicBlockHandler()) this.owner.requestFullScan();
    }
}
