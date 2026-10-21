package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure placement-anchor and torch-neighborhood queries for the bedrock machine. */
final class BedrockPlacementQuery {
    private static final Direction[] PLACEMENT_DIRECTIONS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.UP
    };
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH
    };

    private BedrockPlacementQuery() {
    }

    static List<BlockPos> findNearbyRedstoneTorches(ClientLevel level, BlockPos pistonPos) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos candidate : getTorchInfluencePositions(pistonPos)) {
            if (isRedstoneTorch(level.getBlockState(candidate))) {
                result.add(candidate);
            }
        }
        return result;
    }

    static boolean isRedstoneTorch(BlockState state) {
        return state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    static List<BlockPos> getTorchInfluencePositions(BlockPos pistonPos) {
        List<BlockPos> result = new ArrayList<>();
        for (int yOffset : new int[]{0, 1, -1}) {
            BlockPos center = pistonPos.offset(0, yOffset, 0);
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                result.add(center.relative(direction));
            }
        }
        return result;
    }

    static List<BedrockEnvironment.PlacementInteraction> getPlacementInteractions(
            ClientLevel level,
            BlockPos placePos,
            BlockPos... preferredAnchors
    ) {
        List<BedrockEnvironment.PlacementInteraction> interactions = new ArrayList<>();
        if (level == null || placePos == null) {
            return interactions;
        }
        Set<BlockPos> seenAnchors = new LinkedHashSet<>();
        if (preferredAnchors != null) {
            for (BlockPos preferredAnchor : preferredAnchors) {
                addPlacementInteraction(level, placePos, preferredAnchor, seenAnchors, interactions);
            }
        }
        for (Direction direction : PLACEMENT_DIRECTIONS) {
            addPlacementInteraction(level, placePos, placePos.relative(direction), seenAnchors, interactions);
        }
        return interactions;
    }

    private static void addPlacementInteraction(
            ClientLevel level,
            BlockPos placePos,
            BlockPos anchorPos,
            Set<BlockPos> seenAnchors,
            List<BedrockEnvironment.PlacementInteraction> interactions
    ) {
        if (anchorPos == null || !seenAnchors.add(anchorPos)) {
            return;
        }
        Direction clickedFace = getClickedFace(placePos, anchorPos);
        if (clickedFace == null || !isPlacementAnchorUsable(level, anchorPos, clickedFace)) {
            return;
        }
        interactions.add(new BedrockEnvironment.PlacementInteraction(anchorPos, clickedFace));
    }

    private static Direction getClickedFace(BlockPos placePos, BlockPos anchorPos) {
        int dx = placePos.getX() - anchorPos.getX();
        int dy = placePos.getY() - anchorPos.getY();
        int dz = placePos.getZ() - anchorPos.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return null;
        }
        if (dx == 1) return Direction.EAST;
        if (dx == -1) return Direction.WEST;
        if (dy == 1) return Direction.UP;
        if (dy == -1) return Direction.DOWN;
        if (dz == 1) return Direction.SOUTH;
        return Direction.NORTH;
    }

    private static boolean isPlacementAnchorUsable(ClientLevel level, BlockPos anchorPos, Direction clickedFace) {
        if (level.isOutsideBuildHeight(anchorPos) || !BedrockEnvironment.canInteract(anchorPos)) {
            return false;
        }
        BlockState anchorState = level.getBlockState(anchorPos);
        // The critical bundle breaks the base before placing its replacement. Its
        // head may still be visible here but will no longer be a server-side anchor.
        if (anchorState.isAir() || anchorState.is(Blocks.PISTON_HEAD) || BlockUtils.isReplaceable(anchorState)) {
            return false;
        }
        return anchorState.isFaceSturdy(level, anchorPos, clickedFace)
                || BlockUtils.canSupportCenter(level, anchorPos, clickedFace);
    }
}
