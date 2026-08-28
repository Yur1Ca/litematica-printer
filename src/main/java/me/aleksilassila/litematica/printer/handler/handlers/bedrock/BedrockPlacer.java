package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

public final class BedrockPlacer {
    private final Minecraft client;
    private final Map<BlockPos, PendingHorizontalPlacement> pendingHorizontalPistonPlacements = new HashMap<>();
    private final Map<BlockPos, PendingPistonRetry> pendingPistonRetries = new HashMap<>();

    BedrockPlacer(Minecraft client) {
        this.client = client;
    }

    public void clearHorizontalLookState() {
        pendingHorizontalPistonPlacements.clear();
        pendingPistonRetries.clear();
        NetworkUtils.clearScopedLookOverride();
    }

    void schedulePistonRetry(BlockPos bedrockPos, BlockPos pistonPos, Direction facing) {
        if (bedrockPos == null || pistonPos == null || facing == null) {
            return;
        }
        this.pendingPistonRetries.put(pistonPos.immutable(), new PendingPistonRetry(
                bedrockPos.immutable(), facing, RuntimeAccess.get().currentTick() + 1L, 0));
    }

    void cancelPistonRetry(BlockPos pistonPos) {
        if (pistonPos != null) {
            this.pendingPistonRetries.remove(pistonPos.immutable());
        }
    }

    void tickPistonRetries() {
        if (this.client.level == null) {
            this.pendingPistonRetries.clear();
            return;
        }
        long now = RuntimeAccess.get().currentTick();
        var iterator = this.pendingPistonRetries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingPistonRetry retry = entry.getValue();
            if (!BedrockTargetBlocks.isTargetBlock(this.client.level.getBlockState(retry.bedrockPos()))
                    || this.client.level.getBlockState(entry.getKey()).is(Blocks.PISTON)) {
                iterator.remove();
                continue;
            }
            if (retry.attempts() >= 4) {
                iterator.remove();
                continue;
            }
            if (now < retry.nextTick()) {
                continue;
            }
            this.placePiston(entry.getKey(), retry.facing());
            entry.setValue(new PendingPistonRetry(
                    retry.bedrockPos(), retry.facing(), now + 1L, retry.attempts() + 1));
        }
    }

    public boolean hasPendingHorizontalLook(BlockPos pistonPos) {
        return pistonPos != null && pendingHorizontalPistonPlacements.containsKey(pistonPos.immutable());
    }

    public boolean placeSimple(BlockPos supportPos, Direction clickedFace, Item item) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return false;
        }
        if (!BedrockInventory.switchToOffhand(item)) {
            return false;
        }
        PlayerLook look = new PlayerLook(clickedFace.getOpposite());
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        boolean accepted = placeBlockAggressively(player, hitResult, true);
        return accepted;
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

    public boolean placePiston(BlockPos pistonPos, Direction facing, BlockPos... preferredAnchors) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            NetworkUtils.clearScopedLookOverride();
            return false;
        }
        if (!BedrockInventory.switchToOffhand(Blocks.PISTON.asItem())) {
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
            if (placementInteraction != null) {
                clickedPos = placementInteraction.anchorPos();
                clickedFace = placementInteraction.clickedFace();
            }
        }

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(clickedPos), clickedFace, clickedPos, false);

        boolean accepted = placeBlockAggressively(player, hitResult, false);
        NetworkUtils.clearScopedLookOverride();
        return accepted;
    }

    private boolean placeBlockAggressively(LocalPlayer player, BlockHitResult hitResult, boolean allowLocalUseFallback) {
        boolean useShift = client.level != null && BedrockTargetBlocks.requiresSneakPlacement(client.level.getBlockState(hitResult.getBlockPos()));
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            RuntimeAccess.get().actionBroker().setShift(player, true);
        }
        InteractionResult result;
        try {
            result = InteractionUtils.getRuntime().useItemOn(false, InteractionHand.OFF_HAND, hitResult);
            if (allowLocalUseFallback) {
                ItemStack offhand = player.getOffhandItem();
                if (!offhand.isEmpty()) {
                    offhand.useOn(new UseOnContext(player, InteractionHand.OFF_HAND, hitResult));
                }
            }
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

    private record PendingPistonRetry(
            BlockPos bedrockPos,
            Direction facing,
            long nextTick,
            int attempts
    ) {
    }
}
