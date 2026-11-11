package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Keeps the original placement face and height while avoiding an out-of-reach air click. */
final class VoidWarehousePlacementHit {
    private static final double FACE_INSET = 1.0E-4;

    private VoidWarehousePlacementHit() { }

    static BlockHitResult adjustAirHit(BlockPos target, BlockHitResult hit, Vec3 eye, double reach) {
        if (!target.equals(hit.getBlockPos()) || hit.getType() != HitResult.Type.BLOCK
                || hit.getDirection().getAxis() != Direction.Axis.Y
                || !Double.isFinite(reach) || reach <= 0
                || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) return hit;
        if (hit.isWorldBorderHit()) return hit;
        Vec3 point = hit.getLocation();
        double faceY = target.getY() + (hit.getDirection() == Direction.UP ? 1.0 : 0.0);
        // Custom offsets may encode ordinary slab/stair placement semantics; leave them intact.
        if (point.x != target.getX() + .5 || point.z != target.getZ() + .5 || point.y != faceY
                || eye.distanceToSqr(point) <= reach * reach) return hit;
        Vec3 closer = new Vec3(
                Math.max(target.getX() + FACE_INSET, Math.min(target.getX() + 1 - FACE_INSET, eye.x)),
                point.y,
                Math.max(target.getZ() + FACE_INSET, Math.min(target.getZ() + 1 - FACE_INSET, eye.z)));
        return eye.distanceToSqr(closer) <= reach * reach
                ? new BlockHitResult(closer, hit.getDirection(), hit.getBlockPos(), hit.isInside()) : hit;
    }
}
