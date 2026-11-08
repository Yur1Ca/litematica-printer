package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.function.Predicate;

public final class BedrockEnvironment {
    private BedrockEnvironment() {
    }

    static boolean isChunkLoaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean isTorchPlacementUsable(ClientLevel level, BedrockTorchPlacement placement) {
        if (placement == null || placement.getSupportPos() == null || placement.getClickedFace() == null) {
            return false;
        }
        BlockPos supportPos = placement.getSupportPos();
        BlockPos torchPos = placement.getTorchPos();
        Direction clickedFace = placement.getClickedFace();
        if (torchPos == null) {
            return false;
        }
        if (!isWithinBuildHeight(level, supportPos) || !isWithinBuildHeight(level, torchPos)) {
            return false;
        }
        boolean supportOk = clickedFace == Direction.UP
                ? BlockUtils.canSupportCenter(level, supportPos, Direction.UP)
                : level.getBlockState(supportPos).isFaceSturdy(level, supportPos, clickedFace);
        if (!supportOk) {
            return false;
        }
        BlockState torchState = level.getBlockState(torchPos);
        if (BlockUtils.isReplaceable(torchState)) {
            return true;
        }
        if (!isRedstoneTorch(torchState)) {
            return false;
        }
        if (clickedFace == Direction.UP) {
            return torchState.is(Blocks.REDSTONE_TORCH);
        }
        return torchState.is(Blocks.REDSTONE_WALL_TORCH)
                && torchState.getValue(RedstoneWallTorchBlock.FACING) == clickedFace;
    }

    public static boolean isTorchSupportUsable(ClientLevel level, BlockPos supportPos) {
        return isTorchPlacementUsable(level, new BedrockTorchPlacement(supportPos, Direction.UP));
    }

    public static boolean isSlimePlacementUsable(ClientLevel level, BedrockTorchPlacement placement) {
        if (placement == null || placement.getSupportPos() == null || placement.getTorchPos() == null) {
            return false;
        }
        if (!isWithinBuildHeight(level, placement.getSupportPos()) || !isWithinBuildHeight(level, placement.getTorchPos())) {
            return false;
        }
        BlockState supportState = level.getBlockState(placement.getSupportPos());
        if (!canPlaceSlimeSupport(level, placement.getSupportPos())) {
            return false;
        }
        if (!supportState.is(Blocks.SLIME_BLOCK)
                && findPlacementInteraction(level, placement.getSupportPos()) == null) {
            return false;
        }
        BlockState torchState = level.getBlockState(placement.getTorchPos());
        return BlockUtils.isReplaceable(torchState) || isRedstoneTorch(torchState);
    }

    /** Existing supports are reusable; a new full block must pass vanilla's entity collision query. */
    static boolean canPlaceSlimeSupport(ClientLevel level, BlockPos pos) {
        if (level == null || pos == null || !isWithinBuildHeight(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.SLIME_BLOCK) || BlockUtils.isReplaceable(state)
                && level.isUnobstructed(Blocks.SLIME_BLOCK.defaultBlockState(), pos, CollisionContext.empty());
    }

    public static BlockPos findTorchSupport(ClientLevel level, BlockPos bedrockPos) {
        return findTorchSupport(level, bedrockPos, null);
    }

    public static BlockPos findTorchSupport(ClientLevel level, BlockPos centerPos, Direction excludedAxis) {
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (excludedAxis != null && direction == excludedAxis) {
                continue;
            }
            BlockPos support = centerPos.relative(direction);
            if (isTorchSupportUsable(level, support)) {
                return support;
            }
        }
        return null;
    }

    public static BedrockTorchPlacement findTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis, BlockPos... blockedPositions) {
        return findPreferredTorchPlacement(level, centerPos, excludedAxis, false, placement -> true, blockedPositions);
    }

    static BedrockTorchPlacement findTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis,
                                                    Predicate<BedrockTorchPlacement> allowed, BlockPos... blockedPositions) {
        return findPreferredTorchPlacement(level, centerPos, excludedAxis, false, allowed, blockedPositions);
    }

    public static BedrockTorchPlacement findTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis) {
        return findTorchPlacement(level, centerPos, excludedAxis, new BlockPos[0]);
    }

    public static BedrockTorchPlacement findPossibleSlimeTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis, BlockPos... blockedPositions) {
        return findPreferredTorchPlacement(level, centerPos, excludedAxis, true, placement -> true, blockedPositions);
    }

    static BedrockTorchPlacement findPossibleSlimeTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis,
                                                                Predicate<BedrockTorchPlacement> allowed, BlockPos... blockedPositions) {
        return findPreferredTorchPlacement(level, centerPos, excludedAxis, true, allowed, blockedPositions);
    }

    private static BedrockTorchPlacement findPreferredTorchPlacement(ClientLevel level, BlockPos centerPos, Direction excludedAxis,
                                                                    boolean allowSlimeSupport, Predicate<BedrockTorchPlacement> allowed,
                                                                    BlockPos... blockedPositions) {
        BedrockTorchPlacement topFallback = null;
        BedrockTorchPlacement topDirtyFallback = null;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (excludedAxis != null && direction == excludedAxis) {
                continue;
            }
            BedrockTorchPlacement placement = new BedrockTorchPlacement(centerPos.relative(direction), Direction.UP);
            if (!allowed.test(placement) || !isPlacementUsable(level, placement, allowSlimeSupport, blockedPositions)) {
                continue;
            }
            if (isPlacementInteractable(placement, blockedPositions)) {
                if (isCleanupFriendlyPlacement(level, placement)) {
                    return placement;
                }
                if (topDirtyFallback == null) {
                    topDirtyFallback = placement;
                }
                continue;
            }
            if (topFallback == null) {
                topFallback = placement;
            }
        }

        BedrockTorchPlacement wallFallback = null;
        BedrockTorchPlacement wallDirtyFallback = null;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (excludedAxis != null && direction == excludedAxis) {
                continue;
            }
            BlockPos torchPos = centerPos.relative(direction);
            for (Direction attachedFace : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
                if (excludedAxis != null && attachedFace == excludedAxis) {
                    continue;
                }
                BedrockTorchPlacement placement = new BedrockTorchPlacement(torchPos.relative(attachedFace.getOpposite()), attachedFace);
                if (!allowed.test(placement) || !isPlacementUsable(level, placement, allowSlimeSupport, blockedPositions)) {
                    continue;
                }
                if (isPlacementInteractable(placement, blockedPositions)) {
                    if (isCleanupFriendlyPlacement(level, placement)) {
                        return placement;
                    }
                    if (wallDirtyFallback == null) {
                        wallDirtyFallback = placement;
                    }
                    continue;
                }
                if (wallFallback == null) {
                    wallFallback = placement;
                }
            }
        }

        BedrockTorchPlacement lowerTopFallback = null;
        BedrockTorchPlacement lowerTopDirtyFallback = null;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (excludedAxis != null && direction == excludedAxis) {
                continue;
            }
            BedrockTorchPlacement placement = new BedrockTorchPlacement(centerPos.relative(direction).below(), Direction.UP);
            if (!allowed.test(placement) || !isPlacementUsable(level, placement, allowSlimeSupport, blockedPositions)) {
                continue;
            }
            if (isPlacementInteractable(placement, blockedPositions)) {
                if (isCleanupFriendlyPlacement(level, placement)) {
                    return placement;
                }
                if (lowerTopDirtyFallback == null) {
                    lowerTopDirtyFallback = placement;
                }
                continue;
            }
            if (lowerTopFallback == null) {
                lowerTopFallback = placement;
            }
        }

        if (topDirtyFallback != null) {
            return topDirtyFallback;
        }
        if (wallDirtyFallback != null) {
            return wallDirtyFallback;
        }
        if (lowerTopDirtyFallback != null) {
            return lowerTopDirtyFallback;
        }
        if (topFallback != null) {
            return topFallback;
        }
        if (wallFallback != null) {
            return wallFallback;
        }
        return lowerTopFallback;
    }

    private static boolean isPlacementUsable(ClientLevel level, BedrockTorchPlacement placement, boolean allowSlimeSupport, BlockPos... blockedPositions) {
        if (conflictsWithBlockedPositions(placement, blockedPositions)) {
            return false;
        }
        return allowSlimeSupport
                ? isSlimePlacementUsable(level, placement)
                : isTorchPlacementUsable(level, placement);
    }

    private static boolean conflictsWithBlockedPositions(BedrockTorchPlacement placement, BlockPos... blockedPositions) {
        if (placement == null || blockedPositions == null) {
            return false;
        }
        BlockPos supportPos = placement.getSupportPos();
        BlockPos torchPos = placement.getTorchPos();
        for (BlockPos blockedPos : blockedPositions) {
            if (blockedPos == null) {
                continue;
            }
            if (blockedPos.equals(supportPos) || blockedPos.equals(torchPos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCleanupFriendlyPlacement(ClientLevel level, BedrockTorchPlacement placement) {
        if (level == null || placement == null || placement.getSupportPos() == null || placement.getTorchPos() == null) {
            return false;
        }

        BlockState supportState = level.getBlockState(placement.getSupportPos());
        if (BedrockTargetBlocks.isCleanupResidue(supportState) && !supportState.is(Blocks.SLIME_BLOCK)) {
            return false;
        }

        BlockState torchState = level.getBlockState(placement.getTorchPos());
        return !BedrockTargetBlocks.isCleanupResidue(torchState) || isRedstoneTorch(torchState);
    }

    public static BlockPos findPossibleSlimeSupport(ClientLevel level, BlockPos bedrockPos) {
        return findPossibleSlimeSupport(level, bedrockPos, null);
    }

    public static BlockPos findPossibleSlimeSupport(ClientLevel level, BlockPos centerPos, Direction excludedAxis) {
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (excludedAxis != null && direction == excludedAxis) {
                continue;
            }
            BlockPos slimePos = centerPos.relative(direction);
            BlockPos torchPos = slimePos.above();
            if (!isWithinBuildHeight(level, slimePos) || !isWithinBuildHeight(level, torchPos)) {
                continue;
            }
            if (BlockUtils.isReplaceable(level.getBlockState(slimePos)) && BlockUtils.isReplaceable(level.getBlockState(torchPos))) {
                return slimePos;
            }
        }
        return null;
    }

    public static boolean hasRoomForPiston(ClientLevel level, BlockPos bedrockPos) {
        return hasRoomForPiston(level, bedrockPos.above(), Direction.UP);
    }

    public static boolean hasRoomForPiston(ClientLevel level, BlockPos pistonPos, Direction facing) {
        BlockPos headPos = pistonPos.relative(facing);
        if (!isWithinBuildHeight(level, pistonPos) || !isWithinBuildHeight(level, headPos)) {
            return false;
        }
        return BlockUtils.isReplaceable(level.getBlockState(pistonPos)) && BlockUtils.isReplaceable(level.getBlockState(headPos));
    }

    private static boolean isWithinBuildHeight(ClientLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos);
    }

    public static List<BlockPos> findNearbyRedstoneTorches(ClientLevel level, BlockPos pistonPos) {
        return BedrockPlacementQuery.findNearbyRedstoneTorches(level, pistonPos);
    }

    public static boolean isRedstoneTorch(BlockState state) {
        return BedrockPlacementQuery.isRedstoneTorch(state);
    }

    public static boolean isRedstoneTorchAt(ClientLevel level, BlockPos pos) {
        return pos != null && isRedstoneTorch(level.getBlockState(pos));
    }

    public static boolean arePositionsInteractable(BlockPos... positions) {
        return findFirstOutOfRangePosition(positions) == null;
    }

    public static boolean arePositionsInteractable(Iterable<BlockPos> positions) {
        return findFirstOutOfRangePosition(positions) == null;
    }

    public static PlacementInteraction findPlacementInteraction(ClientLevel level, BlockPos placePos, BlockPos... preferredAnchors) {
        List<PlacementInteraction> interactions = getPlacementInteractions(level, placePos, preferredAnchors);
        return interactions.isEmpty() ? null : interactions.get(0);
    }

    public static List<PlacementInteraction> getPlacementInteractions(ClientLevel level, BlockPos placePos, BlockPos... preferredAnchors) {
        return BedrockPlacementQuery.getPlacementInteractions(level, placePos, preferredAnchors);
    }

    public static boolean canInteract(BlockPos pos) {
        return BedrockRangePolicy.canInteract(pos);
    }

    public static BlockPos findFirstOutOfRangePosition(BlockPos... positions) {
        return BedrockRangePolicy.findFirstOutOfRange(positions);
    }

    public static BlockPos findFirstOutOfRangePosition(Iterable<BlockPos> positions) {
        return BedrockRangePolicy.findFirstOutOfRange(positions);
    }

    public static List<BlockPos> getTorchInfluencePositions(BlockPos pistonPos) {
        return BedrockPlacementQuery.getTorchInfluencePositions(pistonPos);
    }

    private static boolean isPlacementInteractable(BedrockTorchPlacement placement, BlockPos... blockedPositions) {
        return placement != null
                && arePositionsInteractable(blockedPositions)
                && arePositionsInteractable(placement.getSupportPos(), placement.getTorchPos());
    }

    public record PlacementInteraction(BlockPos anchorPos, Direction clickedFace) {
    }
}
