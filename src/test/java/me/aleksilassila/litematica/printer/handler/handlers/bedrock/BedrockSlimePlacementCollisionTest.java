package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real support search and placement with a controlled entity-collision query. */
class BedrockSlimePlacementCollisionTest {
    private static final BlockPos PLAYER_FEET = new BlockPos(2, 65, 2);
    private static final BlockPos TARGET = new BlockPos(1, 64, 1);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @CsvSource({"1, 1, 2, 1", "2, 4, 3, 4"})
    void anOccupiedFirstSupportDoesNotHideAnotherBuildableSupport(int x, int z, int supportX, int supportZ) throws Exception {
        try (var f = new Fixture()) {
            BlockPos target = new BlockPos(x, 64, z);
            assertNotNull(BedrockEnvironment.findPlacementInteraction(f.world, PLAYER_FEET),
                    "The old first choice is anchorable but occupied by the player");
            assertFalse(f.world.isUnobstructed(Blocks.SLIME_BLOCK.defaultBlockState(), PLAYER_FEET,
                    CollisionContext.empty()));

            var placement = f.find(target);

            assertNotNull(placement, "Continue searching after rejecting the occupied support");
            assertEquals(new BlockPos(supportX, 64, supportZ), placement.getSupportPos(),
                    "The lower support remains anchorable and does not intersect the player's body");
            assertTrue(f.world.isUnobstructed(Blocks.SLIME_BLOCK.defaultBlockState(), placement.getSupportPos(),
                    CollisionContext.empty()));
            assertTrue(f.mode.hits.isEmpty(), "Planning must not place any material");
            assertEquals(0, f.access.materialSelections);
        }
    }

    @Test void anExistingSlimeSupportCanBeReusedWithoutReapplyingNewPlacementCollision() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PLAYER_FEET, Blocks.SLIME_BLOCK.defaultBlockState());
            var placement = new BedrockTorchPlacement(PLAYER_FEET, Direction.NORTH);
            assertFalse(f.world.isUnobstructed(Blocks.SLIME_BLOCK.defaultBlockState(), PLAYER_FEET,
                    CollisionContext.empty()), "The controlled collision query still reports an overlapping body");

            assertTrue(BedrockEnvironment.isSlimePlacementUsable(f.world, placement));
            assertTrue(f.placer.placeSlimeSupport(PLAYER_FEET));

            assertEquals(0, f.access.materialSelections);
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @Test void aSupportOccupiedAfterPlanningCannotSelectMaterialOrSendPlacementPackets() throws Exception {
        try (var f = new Fixture()) {
            f.occupantAt(new Vec3(2.5, 65, 3.5));
            var planned = f.find(TARGET);
            assertNotNull(planned);
            assertEquals(PLAYER_FEET, planned.getSupportPos(), "Reproduce the previously selected wall support");
            f.occupantAt(new Vec3(2.5, 65, 2.5));

            assertFalse(f.placer.placeSlimeSupport(planned.getSupportPos()),
                    "Recheck occupancy at the actual placement boundary");

            assertEquals(0, f.access.materialSelections, "A blocked support cannot begin an inventory move");
            assertTrue(f.mode.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty(), "A blocked support cannot send even a preparatory look packet");
        }
    }

    @Test void aSupportBecomesAvailableAgainWhenTheOccupantMovesAway() throws Exception {
        try (var f = new Fixture()) {
            assertFalse(f.placer.placeSlimeSupport(PLAYER_FEET));
            var blockedChoice = f.find(TARGET);
            assertNotNull(blockedChoice);
            assertNotEquals(PLAYER_FEET, blockedChoice.getSupportPos());
            f.occupantAt(new Vec3(2.5, 65, 3.5));

            var available = f.find(TARGET);
            assertNotNull(available);
            assertEquals(PLAYER_FEET, available.getSupportPos(),
                    "Entity occupancy is temporary and must not permanently blacklist the support");
            assertTrue(f.placer.placeSlimeSupport(available.getSupportPos()));

            assertEquals(1, f.access.materialSelections);
            assertEquals(1, f.mode.hits.size());
            assertFalse(f.transport.packets.isEmpty());
        }
    }

    private static class CollisionWorld extends BedrockPlacementRegressionTest.TestLevel {
        AABB occupiedBody;

        @Override public boolean isUnobstructed(BlockState state, BlockPos pos, CollisionContext context) {
            // Native block shapes plus an explicit player box; this replaces the entity-query port,
            // rather than claiming to simulate a live world's complete entity/physics system.
            var shape = state.getCollisionShape(this, pos, context).move(pos.getX(), pos.getY(), pos.getZ());
            return occupiedBody == null || shape.isEmpty()
                    || !Shapes.joinIsNotEmpty(shape, Shapes.create(occupiedBody), BooleanOp.AND);
        }
    }

    private static class CountingAccess implements BedrockInventorySession.InventoryAccess {
        int materialSelections;

        public boolean selectOffhand(Item item) { materialSelections++; return true; }
        public boolean materialRequiresInventorySwap(Item item) { return false; }
        public boolean selectMaterial(Item item) { materialSelections++; return true; }
        public boolean toolRequiresInventorySwap(BlockState state, boolean cleanup) { return false; }
        public boolean selectTool(BlockState state, boolean cleanup) { return true; }
    }

    private static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final CollisionWorld world;
        final CountingAccess access = new CountingAccess();

        Fixture() throws Exception {
            world = allocate(CollisionWorld.class);
            world.blocks = level.blocks;
            world.blocks.clear();
            for (int x = -3; x <= 7; x++) {
                for (int z = -3; z <= 7; z++) {
                    world.blocks.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
                }
            }
            world.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(new BlockPos(2, 64, 4), Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(PLAYER_FEET.below(), Blocks.BEDROCK.defaultBlockState());
            client.level = world;
            client.player.getInventory().setItem(0, new ItemStack(Items.SLIME_BLOCK, 16));
            set(placer, BedrockPlacer.class, "inventory", new BedrockInventorySession(client, access));
            synchronizeInventory();
            transport.packets.clear();
            occupantAt(new Vec3(2.5, 65, 2.5));
        }

        void occupantAt(Vec3 position) throws Exception {
            set(client.player, Entity.class, "position", position);
            client.player.setBoundingBox(new AABB(position.x - .3, position.y, position.z - .3,
                    position.x + .3, position.y + 1.8, position.z + .3));
            world.occupiedBody = client.player.getBoundingBox();
        }

        BedrockTorchPlacement find(BlockPos target) {
            return BedrockEnvironment.findPossibleSlimeTorchPlacement(world, target.above(), Direction.DOWN,
                    target, target.above(), target.above(2));
        }
    }
}
