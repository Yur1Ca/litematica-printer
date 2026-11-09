package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns active target membership, reservations and machine-overlap rules. */
final class BedrockTargetRegistry {
    private final List<BedrockTarget> targets = new ArrayList<>();

    void clear() {
        this.targets.clear();
    }

    boolean isEmpty() {
        return this.targets.isEmpty();
    }

    int size() {
        return this.targets.size();
    }

    void add(BedrockTarget target) {
        this.targets.add(target);
    }

    boolean containsOwner(BlockPos owner) {
        return this.targets.stream().anyMatch(target -> target != null && target.getBedrockPos().equals(owner));
    }

    Iterator<BedrockTarget> iterator() {
        return this.targets.iterator();
    }

    BedrockTarget findConflict(BedrockTarget candidate) {
        for (BedrockTarget existing : this.targets) {
            if (hasStructuralConflict(candidate, existing) || hasPowerConflict(candidate, existing)) {
                return existing;
            }
        }
        return null;
    }

    boolean canReuseBlockingPosition(BedrockTarget candidate, BlockPos pos, BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        if (this.hasInFlightPowerConflict(candidate, candidate.getTorchPos())) return false;
        for (BedrockTarget target : this.targets) {
            if (target.sharesTorchPlacementWith(candidate)) {
                return candidate.canReusePowerReservation(pos, state);
            }
        }
        return false;
    }

    int predictedMachineOverlapPenalty(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement
    ) {
        CandidateFootprint candidate = CandidateFootprint.of(bedrockPos, layout, placement);
        if (candidate.isEmpty()) {
            return 0;
        }
        int penalty = 0;
        for (BedrockTarget target : this.targets) {
            if (candidate.conflictsWith(target)) {
                penalty += 4_000;
            }
        }
        return penalty;
    }

    boolean isReserved(BlockPos pos) {
        for (BedrockTarget target : this.targets) {
            if (target.getReservedPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    boolean isReservedByOther(BlockPos pos, BedrockTarget self) {
        for (BedrockTarget target : this.targets) {
            if (target != self && target.getReservedPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    boolean isTorchPlacementReservedByOther(BedrockTorchPlacement placement, BedrockTarget self) {
        if (placement == null) {
            return false;
        }
        if (this.hasInFlightPowerConflict(self, placement.getTorchPos())) return true;
        for (BedrockTarget target : this.targets) {
            if (target == self || target.matchesTorchPlacement(placement)) {
                continue;
            }
            if (target.getReservedPositions().contains(placement.getSupportPos())
                    || target.getReservedPositions().contains(placement.getTorchPos())) {
                return true;
            }
        }
        return false;
    }

    boolean hasInFlightPowerConflict(BedrockTarget self, BlockPos torchPos) {
        if (torchPos == null) return false;
        for (BedrockTarget target : this.targets) {
            if (target != self && target.hasTried() && target.isTorchPoweredBy(torchPos)) return true;
        }
        return false;
    }

    String activePositionConflict(BlockPos pos) {
        for (BedrockTarget target : this.targets) {
            if (target.getBedrockPos().equals(pos)) {
                return "duplicate_active_target";
            }
            if (target.getPistonPos().equals(pos)) {
                return "occupied_by_active_piston";
            }
        }
        return null;
    }

    int countActive() {
        int count = 0;
        for (BedrockTarget target : this.targets) {
            if (target != null && BedrockSchedulingPolicy.countsTowardsActiveCap(target.getStatus())) {
                count++;
            }
        }
        return count;
    }

    int countVerticalActive() {
        int count = 0;
        for (BedrockTarget target : this.targets) {
            if (target != null
                    && !target.isHorizontalLayout()
                    && BedrockSchedulingPolicy.countsTowardsActiveCap(target.getStatus())) {
                count++;
            }
        }
        return count;
    }

    int countSide() {
        int count = 0;
        for (BedrockTarget target : this.targets) {
            if (target != null && target.isHorizontalLayout()) {
                count++;
            }
        }
        return count;
    }

    BedrockTarget findSideExclusive() {
        for (BedrockTarget target : this.targets) {
            if (target != null && target.isHorizontalLayout()) {
                return target;
            }
        }
        return null;
    }

    void removeOutsideSelection(Predicate<BlockPos> insideSelection, Consumer<BedrockTarget> removed) {
        Iterator<BedrockTarget> iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null || insideSelection.test(target.getBedrockPos())) {
                continue;
            }
            iterator.remove();
            removed.accept(target);
        }
    }

    private static boolean hasStructuralConflict(BedrockTarget candidate, BedrockTarget existing) {
        Set<BlockPos> candidateStructural = candidate.getStructuralPositions();
        Set<BlockPos> existingStructural = existing.getStructuralPositions();
        for (BlockPos pos : candidateStructural) {
            if (existingStructural.contains(pos) || existing.getPowerReservationPositions().contains(pos)) {
                return true;
            }
        }
        for (BlockPos pos : candidate.getPowerReservationPositions()) {
            if (existingStructural.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPowerConflict(BedrockTarget candidate, BedrockTarget existing) {
        if (candidate.sharesTorchPlacementWith(existing)) {
            return false;
        }
        BlockPos candidateTorchPos = candidate.getTorchPos();
        if (candidateTorchPos != null && existing.isTorchPoweredBy(candidateTorchPos)) {
            return true;
        }
        BlockPos existingTorchPos = existing.getTorchPos();
        return existingTorchPos != null && candidate.isTorchPoweredBy(existingTorchPos);
    }

    private record CandidateFootprint(
            Set<BlockPos> structuralPositions,
            Set<BlockPos> powerReservationPositions
    ) {
        private static CandidateFootprint of(
                BlockPos bedrockPos,
                BedrockMachineLayout layout,
                BedrockTorchPlacement placement
        ) {
            LinkedHashSet<BlockPos> structural = new LinkedHashSet<>();
            LinkedHashSet<BlockPos> power = new LinkedHashSet<>();
            structural.add(bedrockPos);
            structural.add(layout.getPistonPos());
            structural.add(layout.getHeadPos());
            if (placement != null) {
                if (placement.getSupportPos() != null) {
                    power.add(placement.getSupportPos());
                }
                if (placement.getTorchPos() != null) {
                    power.add(placement.getTorchPos());
                }
            }
            return new CandidateFootprint(structural, power);
        }

        private boolean isEmpty() {
            return this.structuralPositions.isEmpty() && this.powerReservationPositions.isEmpty();
        }

        private boolean conflictsWith(BedrockTarget target) {
            Set<BlockPos> targetStructural = target.getStructuralPositions();
            Set<BlockPos> targetPower = target.getPowerReservationPositions();
            for (BlockPos pos : this.structuralPositions) {
                if (targetStructural.contains(pos) || targetPower.contains(pos)) {
                    return true;
                }
            }
            for (BlockPos pos : this.powerReservationPositions) {
                if (targetStructural.contains(pos)) {
                    return true;
                }
            }
            return false;
        }
    }
}
