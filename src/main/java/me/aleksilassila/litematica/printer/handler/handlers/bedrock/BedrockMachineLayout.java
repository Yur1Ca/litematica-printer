package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class BedrockMachineLayout {
    private static final Direction[] VERTICAL_SEARCH_ORDER = {
            Direction.UP,
            Direction.DOWN
    };
    private static final Direction[] HORIZONTAL_SEARCH_ORDER = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
    };
    private static final Direction[] SEARCH_ORDER = {
            Direction.UP,
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
    };

    private final BlockPos bedrockPos;
    private final Direction pistonOffset;
    private final BlockPos pistonPos;
    private final BlockPos headPos;

    private BedrockMachineLayout(BlockPos bedrockPos, Direction pistonOffset) {
        this.bedrockPos = bedrockPos;
        this.pistonOffset = pistonOffset;
        this.pistonPos = bedrockPos.relative(pistonOffset);
        this.headPos = this.pistonPos.relative(pistonOffset);
    }

    public static BedrockMachineLayout find(ClientLevel level, BlockPos bedrockPos) {
        if (level == null || bedrockPos == null) {
            return null;
        }
        LayoutCandidate verticalNaturalLayout = findLayout(level, bedrockPos, false, VERTICAL_SEARCH_ORDER);
        if (verticalNaturalLayout != null) {
            return verticalNaturalLayout.layout();
        }
        LayoutCandidate verticalFallbackLayout = findLayout(level, bedrockPos, true, VERTICAL_SEARCH_ORDER);
        if (verticalFallbackLayout != null) {
            return verticalFallbackLayout.layout();
        }
        LayoutCandidate horizontalNaturalLayout = findLayout(level, bedrockPos, false, HORIZONTAL_SEARCH_ORDER);
        if (horizontalNaturalLayout != null) {
            return horizontalNaturalLayout.layout();
        }
        LayoutCandidate horizontalFallbackLayout = findLayout(level, bedrockPos, true, HORIZONTAL_SEARCH_ORDER);
        return horizontalFallbackLayout == null ? null : horizontalFallbackLayout.layout();
    }

    private static boolean shouldDeferHorizontalFallbackUntilVerticalExposed(ClientLevel level, BlockPos bedrockPos) {
        for (Direction direction : VERTICAL_SEARCH_ORDER) {
            BedrockMachineLayout layout = new BedrockMachineLayout(bedrockPos, direction);
            if (isBlockingTarget(level, bedrockPos, layout.getPistonPos())
                    || isBlockingTarget(level, bedrockPos, layout.getHeadPos())
                    || hasBlockingTorchPlacement(level, bedrockPos, layout)) {
                return true;
            }
        }
        return false;
    }

    private static LayoutCandidate findLayout(ClientLevel level, BlockPos bedrockPos, boolean allowSlimeFallback, Direction[] directions) {
        LayoutCandidate bestCandidate = null;
        for (int searchIndex = 0; searchIndex < directions.length; searchIndex++) {
            Direction direction = directions[searchIndex];
            BedrockMachineLayout layout = new BedrockMachineLayout(bedrockPos, direction);
            if (!BedrockEnvironment.hasRoomForPiston(level, layout.getPistonPos(), layout.getPistonOffset())) {
                continue;
            }
            BedrockTorchPlacement torchPlacement = BedrockEnvironment.findTorchPlacement(
                    level,
                    layout.getPistonPos(),
                    layout.getPistonOffset().getOpposite(),
                    bedrockPos,
                    layout.getPistonPos(),
                    layout.getHeadPos()
            );
            if (torchPlacement == null && allowSlimeFallback) {
                torchPlacement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                        level,
                        layout.getPistonPos(),
                        layout.getPistonOffset().getOpposite(),
                        bedrockPos,
                        layout.getPistonPos(),
                        layout.getHeadPos()
                );
            }
            if (torchPlacement == null) {
                continue;
            }
            if (!BedrockEnvironment.arePositionsInteractable(
                    bedrockPos,
                    layout.getPistonPos(),
                    layout.getHeadPos(),
                    torchPlacement.getSupportPos(),
                    torchPlacement.getTorchPos())) {
                continue;
            }

            LayoutCandidate candidate = new LayoutCandidate(
                    layout,
                    torchPlacement,
                    layoutScore(level, torchPlacement),
                    searchIndex
            );
            if (bestCandidate == null || candidate.isBetterThan(bestCandidate)) {
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    public static boolean shouldDeferUntilExposed(ClientLevel level, BlockPos bedrockPos) {
        if (level == null || bedrockPos == null) {
            return false;
        }
        if (findLayout(level, bedrockPos, false, VERTICAL_SEARCH_ORDER) != null) {
            return false;
        }
        if (findLayout(level, bedrockPos, true, VERTICAL_SEARCH_ORDER) != null) {
            return false;
        }
        return shouldDeferHorizontalFallbackUntilVerticalExposed(level, bedrockPos);
    }

    public BlockPos getBedrockPos() {
        return this.bedrockPos;
    }

    public Direction getPistonOffset() {
        return this.pistonOffset;
    }

    public boolean isHorizontal() {
        return this.pistonOffset.getAxis().isHorizontal();
    }

    public BlockPos getPistonPos() {
        return this.pistonPos;
    }

    public BlockPos getHeadPos() {
        return this.headPos;
    }

    public Direction getPrimingFacing() {
        return this.pistonOffset;
    }

    public Direction getExecuteFacing() {
        return this.pistonOffset.getOpposite();
    }

    private static boolean isBlockingTarget(ClientLevel level, BlockPos bedrockPos, BlockPos pos) {
        return pos != null
                && !pos.equals(bedrockPos)
                && !level.isOutsideBuildHeight(pos)
                && BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos));
    }

    private static boolean hasBlockingTorchPlacement(ClientLevel level, BlockPos bedrockPos, BedrockMachineLayout layout) {
        BlockPos centerPos = layout.getPistonPos();
        Direction excludedAxis = layout.getPistonOffset().getOpposite();

        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (direction == excludedAxis) {
                continue;
            }

            BlockPos topSupportPos = centerPos.relative(direction);
            BlockPos topTorchPos = topSupportPos.above();
            if (isBlockingTarget(level, bedrockPos, topSupportPos) || isBlockingTarget(level, bedrockPos, topTorchPos)) {
                return true;
            }

            BlockPos lowerSupportPos = topSupportPos.below();
            BlockPos lowerTorchPos = lowerSupportPos.above();
            if (isBlockingTarget(level, bedrockPos, lowerSupportPos) || isBlockingTarget(level, bedrockPos, lowerTorchPos)) {
                return true;
            }

            BlockPos wallTorchPos = centerPos.relative(direction);
            if (isBlockingTarget(level, bedrockPos, wallTorchPos)) {
                return true;
            }
        }

        return false;
    }

    private static int layoutScore(ClientLevel level, BedrockTorchPlacement placement) {
        return BedrockEnvironment.isCleanupFriendlyPlacement(level, placement) ? 0 : 1;
    }

    private record LayoutCandidate(
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            int score,
            int searchIndex
    ) {
        private boolean isBetterThan(LayoutCandidate other) {
            if (other == null) {
                return true;
            }
            if (this.score != other.score) {
                return this.score < other.score;
            }
            return this.searchIndex < other.searchIndex;
        }
    }
}
