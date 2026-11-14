//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.function.LongSupplier;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrockCandidateRejectionCapacityTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void unavailableLayoutsHaveABoundedMemoAndEvictionDoesNotCompleteTheirTargets() throws Exception {
        var instance = DataManager.class.getDeclaredMethod("getInstance");
        instance.setAccessible(true);
        var data = (DataManager) instance.invoke(null);
        var manager = DataManager.getSelectionManager();
        Object oldArea = get(data, DataManager.class, "areaSimple");
        var oldMode = manager.getSelectionMode();
        var oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        int oldRange = Configs.Core.WORK_RANGE.getIntegerValue();
        BlockPos first = new BlockPos(0, 64, 0);
        PrinterBox box = new PrinterBox(first, new BlockPos(31, 65, 31));
        try (var f = new Fixture()) {
            // This player belongs to the scoped fixture; CUBE policy uses its cached block cell.
            set(f.client.player, net.minecraft.world.entity.Entity.class, "blockPosition", first);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
            var selection = new AreaSelectionSimple(true);
            selection.getAllSubRegionBoxes().getFirst().setPos1(first);
            selection.getAllSubRegionBoxes().getFirst().setPos2(new BlockPos(31, 65, 31));
            set(data, DataManager.class, "areaSimple", selection);
            set(manager, SelectionManager.class, "mode", SelectionMode.SIMPLE);
            var level = f.allocate(BedrockCandidateReachRegressionTest.ScanLevel.class);
            level.blocks = f.level.blocks;
            level.blocks.clear();
            set(level, Level.class, "dimension", Level.OVERWORLD);
            for (int x = -1; x <= 32; x++) for (int y = 63; y <= 66; y++) for (int z = -1; z <= 32; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                level.blocks.put(pos, (box.contains(pos) ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState());
            }
            f.client.level = level;
            f.engine();
            var cache = new ScanCache();
            Object budget = get(cache, ScanCache.class, "budget");
            set(budget, budget.getClass(), "nanoClock", (LongSupplier) () -> 0L);
            var scan = new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> level, cache);
            var planner = new BedrockCandidatePlanner(scan, new LitematicaAdapter());
            var collect = BedrockCandidatePlanner.class.getDeclaredMethod("collectCandidateShard",
                    PrinterBox.class, ClientLevel.class, LocalPlayer.class, int.class);
            collect.setAccessible(true);
            var hasMore = Class.forName(BedrockCandidatePlanner.class.getName() + "$CandidateShard")
                    .getDeclaredMethod("hasMoreSource");
            hasMore.setAccessible(true);
            var candidates = Class.forName(BedrockCandidatePlanner.class.getName() + "$CandidateShard")
                    .getDeclaredMethod("candidates");
            candidates.setAccessible(true);
            var rejected = (Long2ObjectMap<?>) get(planner, BedrockCandidatePlanner.class, "rejectedPlans");
            assertNull(BedrockMachineLayout.find(level, first), "The real local geometry is unavailable");
            boolean finished = false;
            int tick;
            for (tick = 1; tick < 4096; tick++) {
                level.tick = tick;
                scan.beginTick(level, null, tick);
                Object shard = collect.invoke(planner, box, level, f.client.player, 64);
                assertTrue(((List<?>) candidates.invoke(shard)).isEmpty());
                assertTrue(rejected.size() <= 1024, "Failed layouts cannot retain the entire selected volume");
                if (!(boolean) hasMore.invoke(shard)) { finished = true; break; }
            }
            assertTrue(finished, "The bounded memo must not block source progress");
            assertFalse(rejected.containsKey(first.asLong()), "Old negative delivery knowledge may expire or be evicted");
            long dirty = scan.dirtyVersion();

            level.blocks.put(first.above(), Blocks.AIR.defaultBlockState());
            level.blocks.put(first.above(2), Blocks.AIR.defaultBlockState());
            level.blocks.put(first.east().above(2), Blocks.AIR.defaultBlockState());
            assertNotNull(BedrockMachineLayout.find(level, first));
            scan.resetOwner("bedrock");
            level.tick = tick + 1;
            scan.beginTick(level, null, tick + 1);
            Object resumed = collect.invoke(planner, box, level, f.client.player, 1);
            @SuppressWarnings("unchecked") var recovered = (List<BedrockCandidatePlan>) candidates.invoke(resumed);
            assertEquals(List.of(first), recovered.stream().map(BedrockCandidatePlan::pos).toList());
            assertEquals(dirty, scan.dirtyVersion(), "Removing a negative memo is not world completion");
        } finally {
            set(data, DataManager.class, "areaSimple", oldArea);
            set(manager, SelectionManager.class, "mode", oldMode);
            Configs.Core.WORK_RANGE.setIntegerValue(oldRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(oldShape);
        }
    }
}
//#endif
