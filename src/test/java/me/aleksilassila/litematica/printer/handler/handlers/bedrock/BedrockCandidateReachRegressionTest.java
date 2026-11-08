package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real candidate scanner and layout query without mutating the game world. */
class BedrockCandidateReachRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void movingIntoMachineReachRetriesARejectedTargetWithoutAWorldUpdate() throws Exception {
        int oldRange = Configs.Core.WORK_RANGE.getIntegerValue();
        var oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        var manager = DataManager.getSelectionManager();
        var data = dataManager();
        var oldMode = manager.getSelectionMode();
        var oldArea = get(data, DataManager.class, "areaSimple");
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            Configs.Core.WORK_RANGE.setIntegerValue(3);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            var selection = new AreaSelectionSimple(true);
            var box = selection.getAllSubRegionBoxes().get(0);
            box.setPos1(TARGET);
            box.setPos2(TARGET);
            set(data, DataManager.class, "areaSimple", selection);
            set(manager, SelectionManager.class, "mode", SelectionMode.SIMPLE);

            var level = f.allocate(ScanLevel.class);
            level.blocks = f.level.blocks;
            set(level, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = level;
            // Only the upper vertical layout is structurally available. Its head
            // starts beyond reach even though the target itself is in work range.
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.NORTH,
                    Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                level.blocks.put(TARGET.relative(direction), Blocks.STONE.defaultBlockState());
            }
            set(f.client.player, Entity.class, "position", new Vec3(0.8, 62.01, 0.5));
            f.engine();
            var scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> level, new ScanCache());
            scan.beginTick(level, null, 1L);
            var planner = new BedrockCandidatePlanner(scan, new LitematicaAdapter());
            var source = new PrinterBox(TARGET);
            long unchangedRevision = scan.dirtyVersion();
            assertTrue(new LitematicaAdapter().isWithinSelectionRange(TARGET));
            assertTrue(BedrockEnvironment.canInteract(TARGET));
            assertNull(BedrockMachineLayout.find(level, TARGET));
            assertTrue(collect(planner, source, level, f.client.player).isEmpty());
            assertTrue(collect(planner, source, level, f.client.player).isEmpty());
            assertTrue(scan.metricsFor("bedrock").completedPasses() > 0,
                    "Finish the initial pass even when the modeling budget yielded after rejection");
            var originalSession = scanSession(scan);
            var playerBlock = cell(f.client.player.getX(), f.client.player.getY(), f.client.player.getZ());
            var scanCenter = cell(f.client.player.getX(), f.client.player.getEyeY(), f.client.player.getZ());

            // The world, selection and target stay unchanged. A bounded retry
            // must work through the same scan session after a one-centimeter move.
            set(f.client.player, Entity.class, "position", new Vec3(0.8, 62.02, 0.5));
            level.tick = 21L;
            scan.beginTick(level, null, level.tick);
            assertEquals(unchangedRevision, scan.dirtyVersion());
            assertEquals(playerBlock, cell(f.client.player.getX(), f.client.player.getY(), f.client.player.getZ()));
            assertEquals(scanCenter, cell(f.client.player.getX(), f.client.player.getEyeY(), f.client.player.getZ()));
            assertNotNull(BedrockMachineLayout.find(level, TARGET));

            assertEquals(List.of(TARGET), collect(planner, source, level, f.client.player).stream()
                    .map(BedrockCandidatePlan::pos).toList(),
                    "A world-revision cache must not permanently retain an out-of-reach layout rejection");
            assertSame(originalSession, scanSession(scan), "The completed scan session must restart naturally");
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(oldRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(oldShape);
            set(manager, SelectionManager.class, "mode", oldMode);
            set(data, DataManager.class, "areaSimple", oldArea);
        }
    }

    private static DataManager dataManager() throws ReflectiveOperationException {
        var method = DataManager.class.getDeclaredMethod("getInstance");
        method.setAccessible(true);
        return (DataManager) method.invoke(null);
    }

    private static BlockPos cell(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private static Object scanSession(ScanEngine engine) throws Exception {
        var cache = get(engine, ScanEngine.class, "cache");
        var store = get(cache, ScanCache.class, "sessions");
        var sessions = (java.util.Map<?, ?>) get(store, store.getClass(), "sessions");
        var session = sessions.get("bedrock:BEDROCK");
        assertNotNull(session);
        return session;
    }

    @Test void movingAwayFromACachedPowerPositionRebuildsAnAvailableAlternative() throws Exception {
        int oldRange = Configs.Core.WORK_RANGE.getIntegerValue();
        var oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            Configs.Core.WORK_RANGE.setIntegerValue(3);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.NORTH,
                    Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                f.level.blocks.put(TARGET.relative(direction), Blocks.STONE.defaultBlockState());
            }
            set(f.client.player, Entity.class, "position", new Vec3(2.5, 64, 0.5));
            f.engine();
            var scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> f.level, new ScanCache());
            scan.beginTick(f.level, null, 1L);
            var planner = new BedrockCandidatePlanner(scan, new LitematicaAdapter());
            var build = BedrockCandidatePlanner.class.getDeclaredMethod("buildModeledCandidate",
                    ClientLevel.class, BlockPos.class, boolean.class);
            build.setAccessible(true);
            var cached = (BedrockCandidatePlan) build.invoke(planner, f.level, TARGET, false);
            assertNotNull(cached);
            assertEquals(TARGET.east(), cached.placement().getSupportPos());
            long unchangedRevision = scan.dirtyVersion();

            set(f.client.player, Entity.class, "position", new Vec3(-1.5, 64, 0.5));
            var available = (BedrockCandidatePlan) build.invoke(planner, f.level, TARGET, false);
            assertNotNull(available);
            assertEquals(TARGET.west(), available.placement().getSupportPos());
            assertFalse(BedrockEnvironment.canInteract(cached.placement().getSupportPos()));
            assertEquals(unchangedRevision, scan.dirtyVersion());
            var refresh = BedrockCandidatePlanner.class.getDeclaredMethod("refreshModeledCandidate",
                    ClientLevel.class, BedrockCandidatePlan.class, boolean.class);
            refresh.setAccessible(true);

            var refreshed = (BedrockCandidatePlan) refresh.invoke(planner, f.level, cached, false);

            assertNotNull(refreshed);
            assertEquals(TARGET.west(), refreshed.placement().getSupportPos(),
                    "A cached unreachable power position must not hide the currently reachable layout");
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(oldRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(oldShape);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BedrockCandidatePlan> collect(BedrockCandidatePlanner planner, PrinterBox source,
                                                     ClientLevel level, LocalPlayer player) throws Exception {
        var method = BedrockCandidatePlanner.class.getDeclaredMethod("collectCandidateShard",
                PrinterBox.class, ClientLevel.class, LocalPlayer.class, int.class);
        method.setAccessible(true);
        Object shard = method.invoke(planner, source, level, player, 32);
        var candidates = shard.getClass().getDeclaredMethod("candidates");
        candidates.setAccessible(true);
        return (List<BedrockCandidatePlan>) candidates.invoke(shard);
    }

    static class ScanLevel extends BedrockPlacementRegressionTest.TestLevel {
        long tick;
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public long getGameTime() { return tick; }
    }
}
