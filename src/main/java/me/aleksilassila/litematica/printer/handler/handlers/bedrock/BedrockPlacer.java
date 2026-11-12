package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class BedrockPlacer {
    private final Minecraft client;
    private final Map<BlockPos, PendingHorizontalPlacement> pendingHorizontalPistonPlacements = new HashMap<>();
    private final BedrockInventorySession inventory;
    private final BedrockBreaker cleanupBreaker;
    private BooleanSupplier constructionAllowed;
    private Runnable constructionSent;

    BedrockPlacer(Minecraft client) {
        this.client = client;
        this.inventory = new BedrockInventorySession(client);
        this.cleanupBreaker = new BedrockBreaker(client, this);
    }

    void withConstructionStart(BlockPos owner, BedrockThroughputScheduler<BlockPos> starts, Runnable action) {
        if (!starts.canStart(owner)) return;
        this.constructionAllowed = () -> starts.canStart(owner);
        this.constructionSent = () -> starts.started(owner);
        try { action.run(); }
        finally { this.constructionAllowed = null; this.constructionSent = null; }
    }

    private boolean canConstruct() { return this.constructionAllowed == null || this.constructionAllowed.getAsBoolean(); }

    private void constructionSent() { if (this.constructionSent != null) this.constructionSent.run(); }

    public void clearHorizontalLookState() {
        pendingHorizontalPistonPlacements.clear();
        NetworkUtils.clearScopedLookOverride();
    }

    boolean isInventorySettling() {
        return this.inventory.isSettling();
    }

    boolean isToolSettling() { return this.inventory.isToolSettling(); }

    boolean isInventoryPreparationPending() { return this.inventory.isInventoryPreparationPending(); }

    boolean reconcileInventoryIfDirty() { return this.inventory.reconcileInventoryIfDirty(); }

    void maintainInventory(int reserveBuffer) { this.inventory.maintainInventory(reserveBuffer); }

    void onInventorySynchronized(Object connection) { this.inventory.onInventorySynchronized(connection); }

    void resetInventorySync() {
        this.cleanupBreaker.reset();
        this.inventory.reset();
    }

    boolean preparePistons() { return this.inventory.preparePistons(); }

    boolean preparePistons(int required) { return this.inventory.preparePistons(required); }

    int availablePistons() { return this.inventory.availablePistons(); }

    int availableToolUses() { return this.inventory.availableToolUses(); }

    boolean canReserveToolUses(int requiredUses) { return this.inventory.canReserveToolUses(requiredUses); }

    void recordToolUse() { this.inventory.recordToolUse(); }

    void recordToolUse(int slot) { this.inventory.recordToolUse(slot); }

    void recordRawUses(int pistons, int torchSlot, int torches) { this.inventory.recordRawUses(pistons, torchSlot, torches); }

    boolean prepareTool(BlockState state, boolean cleanup) { return this.inventory.prepareTool(state, cleanup); }

    void confirmToolSwitch() { this.inventory.confirmToolSwitch(); }

    boolean prepareCriticalToolTick() { return this.inventory.prepareCriticalToolTick(); }

    void prewarmCriticalTool() { this.inventory.prewarmCriticalTool(); }

    void acknowledgeToolTick(Object connection, int sequence) { this.inventory.acknowledgeToolTick(connection, sequence); }

    boolean breakCleanupBlock(BlockPos pos, Direction direction) { return this.cleanupBreaker.breakCleanup(pos, direction); }

    int breakCleanupBlocks(List<BlockPos> positions, int limit) { return this.cleanupBreaker.breakCleanupBatch(positions, limit); }

    boolean hasActiveCleanupMining() { return this.cleanupBreaker != null && this.cleanupBreaker.hasActiveMining(); }

    void advanceCleanupMining() { this.cleanupBreaker.advanceMining(); }

    boolean isCleanupPendingAt(BlockPos pos) { return this.cleanupBreaker != null && this.cleanupBreaker.isPendingAt(pos); }

    boolean isCleanupRetryPausedAt(BlockPos pos) { return this.cleanupBreaker != null && this.cleanupBreaker.isRetryPausedAt(pos); }

    void onCleanupBlockUpdate(BlockPos pos) { this.cleanupBreaker.onServerBlockUpdate(pos); }

    void cancelPistonPlacement(BlockPos pistonPos) {
        if (pistonPos != null) this.clearPendingPistonLook(pistonPos);
    }

    private void clearPendingPistonLook(BlockPos pistonPos) {
        if (this.pendingHorizontalPistonPlacements.remove(pistonPos) != null) {
            NetworkUtils.clearScopedLookOverride();
        }
    }

    public boolean hasPendingHorizontalLook(BlockPos pistonPos) {
        return pistonPos != null && pendingHorizontalPistonPlacements.containsKey(pistonPos.immutable());
    }

    public boolean placeSimple(BlockPos supportPos, Direction clickedFace, Item item) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || this.isInventorySettling() || this.hasActiveCleanupMining()
                || !this.canConstruct()) {
            return false;
        }
        if (!this.inventory.prepareMaterial(item)) {
            return false;
        }
        PlayerLook look = new PlayerLook(clickedFace.getOpposite());
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        boolean accepted = placeBlockAggressively(player, hitResult, true, InteractionHand.MAIN_HAND);
        if (accepted) {
            this.inventory.recordPredictedUse();
            this.constructionSent();
        }
        return accepted;
    }

    boolean placeSlimeSupport(BlockPos slimePos) {
        if (this.client.level == null || slimePos == null) return false;
        var state = this.client.level.getBlockState(slimePos);
        if (state.is(Blocks.SLIME_BLOCK)) return true;
        if (!BedrockEnvironment.canPlaceSlimeSupport(this.client.level, slimePos)) return false;
        var interaction = BedrockEnvironment.findPlacementInteraction(this.client.level, slimePos);
        if (interaction == null) return false;
        // A stable neighbor keeps even a delayed duplicate aimed at this exact support position.
        return this.placeSimple(interaction.anchorPos(), interaction.clickedFace(), Blocks.SLIME_BLOCK.asItem());
    }

    public boolean placePiston(BlockPos pistonPos, Direction facing) {
        return placePiston(pistonPos, facing, pistonPos.relative(facing.getOpposite()));
    }

    public boolean preparePistonPlacementLook(BlockPos pistonPos, Direction facing) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return false;
        }

        PlayerLook look = new PlayerLook(facing.getOpposite());
        return !ensureHorizontalLookSettled(player, pistonPos, facing, look, false);
    }

    record PreparedPistonPlacement(BlockHitResult hit, PlayerLook look) { }

    record PistonRequest(BlockPos pistonPos, Direction facing, BedrockTorchPlacement torchPlacement) {
        PistonRequest(BlockPos pistonPos, Direction facing) { this(pistonPos, facing, null); }
    }

    List<PistonRequest> placePistonBatch(List<PistonRequest> requests) {
        return this.placePistonBatch(requests, Integer.MAX_VALUE, request -> false);
    }

    List<PistonRequest> placePistonBatch(List<PistonRequest> requests, int newStarts, Predicate<PistonRequest> started) {
        if (this.hasActiveCleanupMining()) return List.of();
        var preparation = BedrockInitialPackets.placeBatch(this.client, this.inventory, requests,
                this::isCleanupPendingAt, newStarts, started);
        if (preparation.powered()) {
            this.inventory.onInitialPreparationSent(preparation.changedMainHand());
        }
        return preparation.accepted();
    }

    PreparedPistonPlacement prepareCriticalPlacement(BlockPos bedrockPos, BlockPos pistonPos,
                                                     Direction facing, int requiredPistons) {
        LocalPlayer player = this.client.player;
        if (player == null || this.client.level == null || this.client.gameMode == null
                || this.isInventorySettling()
                || player.inventoryMenu == null
                || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()
                || !NetworkUtils.canSendPacketBatch()) return null;

        var offhand = InventoryUtils.getOffhandStack(player);
        if (!this.preparePistons(requiredPistons)) return null;
        offhand = InventoryUtils.getOffhandStack(player);
        if (this.availablePistons() < requiredPistons) return null;
        PlayerLook look = new PlayerLook(facing.getOpposite());
        if (ensureHorizontalLookSettled(player, pistonPos, facing, look, false)) return null;
        var replacement = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing);
        if (!BedrockEnvironment.canInteract(pistonPos)
                || !this.client.level.isUnobstructed(replacement, pistonPos, CollisionContext.empty())) return null;
        var interaction = BedrockEnvironment.getPlacementInteractions(this.client.level, pistonPos, bedrockPos).stream()
                .filter(candidate -> BedrockEnvironment.canInteract(candidate.anchorPos())
                        && !BedrockTargetBlocks.requiresSneakPlacement(this.client.level.getBlockState(candidate.anchorPos())))
                .findFirst().orElse(null);
        if (interaction == null) return null;
        Direction clickedFace = interaction.clickedFace();
        var hit = new BlockHitResult(Vec3.atCenterOf(interaction.anchorPos()).add(
                clickedFace.getStepX() * 0.5, clickedFace.getStepY() * 0.5, clickedFace.getStepZ() * 0.5),
                interaction.clickedFace(), interaction.anchorPos().immutable(), false);
        return new PreparedPistonPlacement(hit, look);
    }

    public boolean placePiston(BlockPos pistonPos, Direction facing, BlockPos... preferredAnchors) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || this.isInventorySettling() || this.hasActiveCleanupMining()
                || !this.canConstruct()
                || player.inventoryMenu == null || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }
        if (!this.preparePistons()) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }

        // Pistons face opposite to the direction the player is looking when placed.
        // We want the resulting piston facing to match `facing`, so look at the opposite side.
        PlayerLook look = new PlayerLook(facing.getOpposite());
        if (ensureHorizontalLookSettled(player, pistonPos, facing, look, true)) {
            return false;
        }
        applyPlacementLook(player, look);

        BlockPos clickedPos = pistonPos.relative(facing.getOpposite());
        Direction clickedFace = facing;
        if (client.level != null) {
            BlockPos[] anchors = preferredAnchors != null && preferredAnchors.length > 0
                    ? preferredAnchors
                    : new BlockPos[]{clickedPos};
            BedrockEnvironment.PlacementInteraction placementInteraction =
                    BedrockEnvironment.findPlacementInteraction(client.level, pistonPos, anchors);
            if (placementInteraction == null) {
                NetworkUtils.clearScopedLookOverride();
                return false;
            }
            clickedPos = placementInteraction.anchorPos();
            clickedFace = placementInteraction.clickedFace();
        }

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(clickedPos), clickedFace, clickedPos, false);

        boolean accepted = placeBlockAggressively(player, hitResult, false, InteractionHand.OFF_HAND);
        if (accepted) {
            this.inventory.recordRawUses(1, -1, 0);
            this.constructionSent();
        }
        NetworkUtils.clearScopedLookOverride();
        return accepted;
    }

    private boolean placeBlockAggressively(LocalPlayer player, BlockHitResult hitResult, boolean localPrediction,
                                          InteractionHand hand) {
        boolean useShift = client.level != null && BedrockTargetBlocks.requiresSneakPlacement(client.level.getBlockState(hitResult.getBlockPos()));
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            RuntimeAccess.get().actionBroker().setShift(player, true);
        }
        InteractionResult result;
        try {
            result = InteractionUtils.getRuntime().useItemOn(localPrediction, hand, hitResult);
        } finally {
            if (useShift && !wasSneak) {
                RuntimeAccess.get().actionBroker().setShift(player, false);
            }
        }
        return result != InteractionResult.FAIL;
    }

    private boolean ensureHorizontalLookSettled(LocalPlayer player, BlockPos pistonPos, Direction facing, PlayerLook look, boolean consumeReadyPlacement) {
        Direction lookDirection = DirectionUtils.orderedByNearest(look.getYaw(), look.getPitch())[0];
        BlockPos pendingKey = pistonPos.immutable();
        if (!lookDirection.getAxis().isHorizontal()) {
            pendingHorizontalPistonPlacements.remove(pendingKey);
            NetworkUtils.clearScopedLookOverride();
            return false;
        }

        PendingHorizontalPlacement pendingPlacement = pendingHorizontalPistonPlacements.get(pendingKey);
        if (pendingPlacement != null && facing == pendingPlacement.facing()) {
            NetworkUtils.setScopedLookOverride(look);
            if (!isHorizontalLookReady(pendingPlacement)) {
                return true;
            }
            if (consumeReadyPlacement) {
                pendingHorizontalPistonPlacements.remove(pendingKey);
            }
            return false;
        }

        long sentTick = RuntimeAccess.get().currentTick();
        pendingHorizontalPistonPlacements.put(pendingKey, new PendingHorizontalPlacement(facing, sentTick));
        NetworkUtils.setScopedLookOverride(look);
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        return true;
    }

    private boolean isHorizontalLookReady(PendingHorizontalPlacement pendingPlacement) {
        long now = RuntimeAccess.get().currentTick();
        // Movement and interaction packets share the ordered game connection.  Sending the
        // placement on the following client tick is sufficient and keeps the original safety
        // boundary without treating an unrelated inbound packet as an acknowledgement.
        return now > pendingPlacement.sentTick();
    }

    private void applyPlacementLook(LocalPlayer player, PlayerLook look) {
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
    }

    private record PendingHorizontalPlacement(Direction facing, long sentTick) {
    }

}
