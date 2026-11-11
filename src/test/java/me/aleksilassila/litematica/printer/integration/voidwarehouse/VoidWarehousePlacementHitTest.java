package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementHitTest {
    @Test
    void reachableBlockMustNotUseAnUnreachableBottomFaceCenter() {
        Vec3 eye = new Vec3(5.9, 1.62, .5);
        double reach = 5.5;
        var original = hit(BlockPos.ZERO, Direction.DOWN);
        assertTrue(eye.distanceToSqr(new Vec3(1, 1, .5)) < reach * reach,
                "The target passes the normal block-AABB interaction check");
        assertTrue(eye.distanceToSqr(Vec3.atCenterOf(BlockPos.ZERO)) < 6 * 6,
                "The target also passes the configured work range");
        assertTrue(eye.distanceToSqr(original.getLocation()) > reach * reach,
                "The old physical point would be refused by warehouse hit_reach");

        var adjusted = VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, original, eye, reach);

        assertTrue(eye.distanceToSqr(adjusted.getLocation()) <= reach * reach,
                "Use a reachable point on the same face instead of wasting a request");
        assertEquals(Direction.DOWN, adjusted.getDirection());
        assertEquals(original.getBlockPos(), adjusted.getBlockPos());
        assertEquals(0, adjusted.getLocation().y);
        assertTrue(adjusted.getLocation().x > .5 && adjusted.getLocation().x < 1);
        assertEquals(.5, adjusted.getLocation().z);
    }

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = {"DOWN", "UP"})
    void projectionStaysInsideTheOriginalFaceIncludingNegativeWorldCoordinates(Direction face) {
        var target = new BlockPos(-7, 89, -2241);
        var original = hit(target, face);
        var eye = original.getLocation().add(-4, 0, 4);
        double reach = 5.1;
        assertTrue(eye.distanceToSqr(original.getLocation()) > reach * reach);

        var adjusted = VoidWarehousePlacementHit.adjustAirHit(target, original, eye, reach);

        assertTrue(eye.distanceToSqr(adjusted.getLocation()) <= reach * reach);
        assertEquals(face, adjusted.getDirection());
        assertEquals(original.getLocation().y, adjusted.getLocation().y,
                "Changing Y or face could change slab/stair placement semantics");
        assertTrue(adjusted.getLocation().x > target.getX());
        assertTrue(adjusted.getLocation().x < target.getX() + 1);
        assertTrue(adjusted.getLocation().z > target.getZ());
        assertTrue(adjusted.getLocation().z < target.getZ() + 1);
    }

    @Test
    void alreadyReachableHitKeepsItsExactPoint() {
        var original = hit(BlockPos.ZERO, Direction.DOWN);
        assertSame(original, VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO, original, new Vec3(1.5, 1.62, .5), 5.5));
    }

    @Test
    void realSupportClickCannotMoveToThePlacementTarget() {
        var support = hit(BlockPos.ZERO, Direction.UP);
        assertSame(support, VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO.above(), support, new Vec3(5.9, 1.62, .5), 5.5));
    }

    @Test
    void intentionalOffsetsAndHorizontalFacesKeepTheirPlacementSemantics() {
        Vec3 eye = new Vec3(5.9, 1.62, .5);
        for (var original : new BlockHitResult[]{
                new BlockHitResult(new Vec3(.5, .75, 0), Direction.NORTH, BlockPos.ZERO, false),
                new BlockHitResult(new Vec3(.75, 0, .5), Direction.DOWN, BlockPos.ZERO, false),
                new BlockHitResult(new Vec3(.5, .01, .5), Direction.DOWN, BlockPos.ZERO, false)}) {
            assertSame(original, VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, original, eye, 5.5));
        }
    }

    @Test
    void cannotMakeAnUnreachableFaceReachableByChangingItsHeightOrDirection() {
        var original = hit(BlockPos.ZERO, Direction.DOWN);
        assertSame(original, VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO, original, new Vec3(.5, 6, .5), 5.5));
        assertSame(original, VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO, original, new Vec3(7, 0, .5), 5.5));
    }

    @Test
    void exactReachBoundaryKeepsTheOriginalPoint() {
        var original = hit(BlockPos.ZERO, Direction.DOWN);
        assertSame(original, VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO, original, new Vec3(6, 0, .5), 5.5));
    }

    @Test
    void projectionPreservesInsideFlagAndTheAlreadyNearestAxis() {
        var original = new BlockHitResult(new Vec3(.5, 0, .5), Direction.DOWN, BlockPos.ZERO, true);
        var adjusted = VoidWarehousePlacementHit.adjustAirHit(
                BlockPos.ZERO, original, new Vec3(5.9, 1.62, .5), 5.5);
        assertNotSame(original, adjusted);
        assertTrue(adjusted.isInside());
        assertEquals(.5, adjusted.getLocation().z);
    }

    @Test
    void missesAndWorldBorderHitsRemainUntouched() {
        var miss = BlockHitResult.miss(new Vec3(.5, 0, .5), Direction.DOWN, BlockPos.ZERO);
        Vec3 eye = new Vec3(5.9, 1.62, .5);
        assertSame(miss, VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, miss, eye, 5.5));
        var border = new BlockHitResult(new Vec3(.5, 0, .5), Direction.DOWN, BlockPos.ZERO, false, true);
        assertSame(border, VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, border, eye, 5.5));
    }

    @Test
    void invalidGeometryNeverCreatesANewHit() {
        var original = hit(BlockPos.ZERO, Direction.DOWN);
        Vec3 eye = new Vec3(5.9, 1.62, .5);
        for (double reach : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertSame(original, VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, original, eye, reach));
        }
        for (Vec3 invalid : new Vec3[]{new Vec3(Double.NaN, 0, 0),
                new Vec3(0, Double.POSITIVE_INFINITY, 0), new Vec3(0, 0, Double.NEGATIVE_INFINITY)}) {
            assertSame(original, VoidWarehousePlacementHit.adjustAirHit(BlockPos.ZERO, original, invalid, 5.5));
        }
    }

    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(new Vec3(pos.getX() + .5,
                pos.getY() + (face == Direction.UP ? 1 : 0), pos.getZ() + .5), face, pos, false);
    }
}
