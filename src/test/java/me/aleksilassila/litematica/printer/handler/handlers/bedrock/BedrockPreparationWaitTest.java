//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real lifecycle, vanilla menu SWAP and ordered inventory replies; no world placement is simulated. */
class BedrockPreparationWaitTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos SLIME = TARGET.east();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void anInventoryMoveWaitsAcrossTicksWithoutRetiringOrRepeatingTheSwap() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addSlimeTarget();
            var beforeWorld = new HashMap<>(f.world.blocks);
            assertTrue(BedrockEnvironment.isSlimePlacementUsable(f.world, new BedrockTorchPlacement(SLIME, Direction.UP)));
            assertFalse(f.ownerPlacer.isInventorySettling());
            assertEquals(16, f.player().getInventory().getItem(12).getCount());

            f.advance(1);

            assertEquals(1, f.actions.materialMoves, "The real menu SWAP starts exactly one material preparation");
            assertTrue(f.player().getInventory().getItem(12).isEmpty());
            assertTrue(f.player().getInventory().getItem(5).is(Items.SLIME_BLOCK));
            assertEquals(16, f.player().getInventory().getItem(5).getCount());
            assertTrue(f.ownerPlacer.isInventorySettling(), "The successful snapshot transport owns a pending reply");
            assertEquals(1, f.transport.packets.stream().filter(ServerboundContainerClickPacket.class::isInstance).count());
            assertEquals(1, f.markers());
            assertTrue(f.capture.hits.isEmpty(), "Inventory preparation must return before any useItemOn call");
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.getStatus(),
                    "Opening an inventory fence during ensureTorchPlacement is waiting, not a failed target");
            f.assertOwned(target);
            int sent = f.transport.packets.size();

            for (int tick = 2; tick <= 5; tick++) {
                f.advance(tick);
                assertTrue(f.ownerPlacer.isInventorySettling());
                assertNotEquals(BedrockTarget.Status.FAILED, target.getStatus());
                f.assertOwned(target);
                assertEquals(1, f.actions.materialMoves, "Unacknowledged ticks cannot repeat a relative SWAP");
                assertEquals(sent, f.transport.packets.size());
                assertTrue(f.capture.hits.isEmpty());
            }

            f.acknowledge();
            assertFalse(f.ownerPlacer.isInventorySettling());
            assertThrows(PlacementCaptured.class, () -> f.advance(6),
                    "The same owner must resume through the actual game-mode placement boundary after its reply");
            f.assertOwned(target);
            assertEquals(1, f.actions.materialMoves);
            assertEquals(1, f.capture.hits.size());
            assertEquals(TARGET, f.capture.hits.getFirst().getBlockPos());
            assertEquals(Direction.EAST, f.capture.hits.getFirst().getDirection());
            assertEquals(SLIME, f.serverPlacementPosition(f.capture.hits.getFirst()));
            assertEquals(beforeWorld, f.world.blocks, "The capture must not simulate block placement or piston physics");
            assertEquals(16, f.player().getInventory().getItem(5).getCount());
        }
    }

    @Test void anUnavailablePowerLayoutWithoutAnInventoryFenceStillFails() throws Exception {
        try (var f = new Fixture()) {
            var target = f.addSlimeTarget();
            for (BlockPos pos : BedrockEnvironment.getTorchInfluencePositions(target.getPistonPos())) {
                f.world.blocks.put(pos, Blocks.STONE.defaultBlockState());
            }
            assertNull(BedrockEnvironment.findTorchPlacement(f.world, target.getPistonPos(), Direction.DOWN,
                    TARGET, target.getPistonPos(), target.getHeadPos()));
            assertNull(BedrockEnvironment.findPossibleSlimeTorchPlacement(f.world, target.getPistonPos(), Direction.DOWN,
                    TARGET, target.getPistonPos(), target.getHeadPos()));
            assertFalse(f.ownerPlacer.isInventorySettling());

            f.advance(1);

            assertEquals(BedrockTarget.Status.FAILED, target.getStatus(),
                    "A genuinely missing power layout must not become an unbounded inventory wait");
            assertTrue(f.targets.isEmpty());
            assertEquals(1, f.engine.getHudSnapshot().failedTargets());
            assertTrue(f.engine.isPositionOnRetryCooldown(TARGET));
            assertFalse(f.ownerPlacer.isInventorySettling());
            assertEquals(0, f.actions.materialMoves);
            assertTrue(f.capture.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @Test void aMissingMachineLayoutWithoutAnInventoryFenceIsImmediatelyFailed() throws Exception {
        try (var f = new Fixture()) {
            for (Direction direction : Direction.values()) {
                f.world.blocks.put(TARGET.relative(direction), Blocks.STONE.defaultBlockState());
            }
            assertNull(BedrockMachineLayout.find(f.world, TARGET));

            var target = new BedrockTarget(TARGET, f.world, new BedrockCriticalExecutor(f.ownerPlacer), f.ownerPlacer);

            assertEquals(BedrockTarget.Status.FAILED, target.getStatus());
            assertFalse(f.ownerPlacer.isInventorySettling());
            assertEquals(0, f.actions.materialMoves);
            assertTrue(f.capture.hits.isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    private static class EmptyEntityWorld extends BedrockToolSyncTest.SequencedLevel {
        @Override public List<Entity> getEntities(Entity except, AABB bounds, Predicate<? super Entity> filter) {
            // The fixture installs no entities. Keep vanilla isUnobstructed and block collision shapes intact.
            return List.of();
        }
    }

    private static class CapturingGameMode extends BedrockPlacementRegressionTest.TestGameMode {
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            hits.add(hit);
            throw new PlacementCaptured();
        }
    }

    private static class PlacementCaptured extends RuntimeException { }

    private static class Fixture extends BedrockInventorySessionTest.Fixture {
        final EmptyEntityWorld world;
        final CapturingGameMode capture;
        final BedrockEngine engine;
        final BedrockPlacer ownerPlacer;
        final BedrockTargetRegistry targets;
        final BedrockTargetExecutor executor;

        Fixture() throws Exception {
            world = allocate(EmptyEntityWorld.class);
            world.blocks = level.blocks;
            world.next = 100;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            Vec3 position = new Vec3(-1.5, 64, .5);
            set(client.player, Entity.class, "position", position);
            client.player.setBoundingBox(new AABB(-1.8, 64, .2, -1.2, 65.8, .8));
            assertFalse(client.player.getBoundingBox().intersects(new AABB(SLIME)));
            world.blocks.put(new BlockPos(-2, 63, 0), Blocks.STONE.defaultBlockState());
            set(client, Minecraft.class, "gui", allocate(Gui.class));
            capture = allocate(CapturingGameMode.class);
            capture.hits = new ArrayList<>();
            capture.predictions = new ArrayList<>();
            client.gameMode = capture;
            engine = engine();
            ownerPlacer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            targets = (BedrockTargetRegistry) get(engine, BedrockEngine.class, "targets");
            executor = (BedrockTargetExecutor) get(engine, BedrockEngine.class, "targetExecutor");
            var session = (BedrockInventorySession) session(true);
            set(ownerPlacer, BedrockPlacer.class, "inventory", session);
            validateInitial(session);
            player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            player().getInventory().setItem(1, new ItemStack(Items.REDSTONE_TORCH, 16));
            player().getInventory().setItem(12, new ItemStack(Items.SLIME_BLOCK, 16));
            transport.packets.clear();
        }

        BedrockTarget addSlimeTarget() throws Exception {
            var constructor = BedrockMachineLayout.class.getDeclaredConstructor(BlockPos.class, Direction.class);
            constructor.setAccessible(true);
            var target = new BedrockTarget(TARGET, world, constructor.newInstance(TARGET, Direction.UP),
                    new BedrockTorchPlacement(SLIME, Direction.UP), SLIME,
                    new BedrockCriticalExecutor(ownerPlacer), ownerPlacer, null);
            assertEquals(BedrockTarget.Status.UNINITIALIZED, target.getStatus());
            targets.add(target);
            return target;
        }

        void advance(long now) throws Exception {
            tick(now);
            executor.processPreparation(world, 1, false, new LinkedHashSet<>(), true);
        }

        void assertOwned(BedrockTarget target) {
            assertEquals(1, targets.size(), "An inventory wait must keep its reservation owner");
            assertSame(target, targets.iterator().next());
            assertEquals(0, engine.getHudSnapshot().failedTargets());
            assertFalse(engine.isPositionOnRetryCooldown(TARGET));
        }
    }
}
//#endif
