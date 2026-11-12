package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Cached machine plan for one bedrock candidate and its local world revision. */
record BedrockCandidatePlan(
        BlockPos pos,
        BedrockMachineLayout layout,
        BedrockTorchPlacement placement,
        BlockPos slimePos,
        List<BlockPos> structuralPositions,
        List<BlockPos> powerReservationPositions,
        int priority,
        int neighborTargetCount,
        PrinterBox footprint,
        long planRevision
) {
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();

    static BedrockCandidatePlan build(ClientLevel level, BlockPos pos, LongSupplier revision) {
        BedrockMachineLayout layout = BedrockMachineLayout.find(level, pos);
        PlacementSelection placementSelection = layout == null ? null : findPlacementSelection(level, layout, pos);
        BedrockTorchPlacement placement = placementSelection == null ? null : placementSelection.placement();
        BlockPos slimePos = placementSelection == null ? null : placementSelection.slimePos();
        int priority = candidatePriority(level, pos, layout, placement);
        int neighborTargetCount = neighborTargetCount(level, pos);
        return new BedrockCandidatePlan(
                pos,
                layout,
                placement,
                slimePos,
                buildStructuralPositions(pos, layout),
                buildPowerReservationPositions(placement),
                priority,
                neighborTargetCount,
                footprint(pos),
                revision.getAsLong()
        );
    }

    BedrockCandidatePlan refreshSchedulingPriority(ClientLevel level, long revision) {
        // Reservations and cleanup change without a block update; keep geometry cached,
        // but compare candidates using the current runtime's occupancy and cleanup cost.
        int priority = candidatePriority(level, this.pos, this.layout, this.placement);
        if (this.priority == priority && this.planRevision == revision) return this;
        return new BedrockCandidatePlan(
                this.pos,
                this.layout,
                this.placement,
                this.slimePos,
                this.structuralPositions,
                this.powerReservationPositions,
                priority,
                this.neighborTargetCount,
                this.footprint,
                revision
        );
    }

    private static PrinterBox footprint(BlockPos pos) {
        // Layout search reaches at most three blocks from the bedrock target. One extra block
        // covers neighbor-dependent support and torch-face changes at the boundary.
        return new PrinterBox(
                pos.getX() - 4,
                pos.getY() - 4,
                pos.getZ() - 4,
                pos.getX() + 4,
                pos.getY() + 4,
                pos.getZ() + 4
        );
    }

    private static int candidatePriority(ClientLevel level, BlockPos pos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
        int controllerPenalty = BedrockController.getSchedulingPenalty(pos);
        if (layout != null) {
            int penalty = controllerPenalty;
            penalty += BedrockController.getSchedulingPenalty(layout.getPistonPos());
            penalty += BedrockController.getSchedulingPenalty(layout.getHeadPos());
            if (placement != null) {
                penalty += BedrockController.getSchedulingPenalty(placement.getSupportPos());
                penalty += BedrockController.getSchedulingPenalty(placement.getTorchPos());
                if (level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)) {
                    penalty += 200;
                }
            }
            penalty += BedrockController.getPredictedMachineOverlapPenalty(pos, layout, placement);
            return penalty;
        }
        if (BedrockMachineLayout.shouldDeferUntilExposed(level, pos)) {
            return controllerPenalty + 1_000;
        }
        return controllerPenalty + 10_000;
    }

    private static List<BlockPos> buildStructuralPositions(BlockPos bedrockPos, BedrockMachineLayout layout) {
        List<BlockPos> positions = new ArrayList<>(3);
        positions.add(bedrockPos);
        if (layout != null) {
            positions.add(layout.getPistonPos());
            positions.add(layout.getHeadPos());
        }
        return positions;
    }

    private static List<BlockPos> buildPowerReservationPositions(BedrockTorchPlacement placement) {
        if (placement == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(2);
        if (placement.getSupportPos() != null) {
            positions.add(placement.getSupportPos());
        }
        if (placement.getTorchPos() != null) {
            positions.add(placement.getTorchPos());
        }
        return positions;
    }

    private static PlacementSelection findPlacementSelection(ClientLevel level, BedrockMachineLayout layout, BlockPos bedrockPos) {
        BedrockTorchPlacement placement = BedrockEnvironment.findTorchPlacement(
                level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        if (placement != null) {
            return new PlacementSelection(placement, level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)
                    ? placement.getSupportPos()
                    : null);
        }
        placement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        return placement == null ? null : new PlacementSelection(placement, placement.getSupportPos());
    }

    private static int neighborTargetCount(ClientLevel level, BlockPos pos) {
        int count = 0;
        for (Direction direction : NEIGHBOR_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            if (BedrockTargetBlocks.isTargetBlock(level.getBlockState(neighborPos))) {
                count++;
            }
        }
        return count;
    }

    private record PlacementSelection(BedrockTorchPlacement placement, BlockPos slimePos) { }
}
