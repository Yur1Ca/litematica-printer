//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real handler, scan coordinator, source session and planner with an empty physical pipeline. */
class BedrockCandidateWakeupTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aCompletedEmptySourceReopensForFineReachMovementWithoutDirtyingOtherOwners() throws Exception {
        try (var f = new Fixture()) {
            f.move(0.5D, 66.01D, 0.5D);
            f.world.blocks.put(TARGET.east(2).above(), Blocks.STONE.defaultBlockState());
            f.finishInitialPass();
            assertFalse(BedrockEnvironment.canInteract(TARGET));
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            assertEquals(ScanState.LAZY, f.handler.getScanState());
            assertFalse(f.planner.hasPendingCandidates());
            assertFalse(f.planner.hasPendingScanSource());
            f.otherSource();
            Object otherSession = f.session("other:CUSTOM");
            long dirty = f.scan.dirtyVersion();

            // The eye stays in the same integer cell and the source box is unchanged.
            f.move(0.5D, 65.99D, 0.5D);
            assertTrue(BedrockEnvironment.canInteract(TARGET));
            var layout = BedrockMachineLayout.find(f.world, TARGET);
            assertNotNull(layout);
            assertEquals(Direction.UP, layout.getPistonOffset());
            for (int tick = 12; tick <= 16 && f.engine.getHudSnapshot().totalTargets() == 0; tick++) f.step(tick);

            assertEquals(1, f.engine.getHudSnapshot().totalTargets(),
                    "A real handler must resupply the completed source instead of waiting for its lazy probe");
            assertEquals(dirty, f.scan.dirtyVersion(), "Eligibility is not a world update for every feature");
            assertSame(otherSession, f.session("other:CUSTOM"), "Only bedrock's source may be refreshed");
            assertTrue(f.mode.hits.isEmpty(), "This test stops at read-only admission");
        }
    }

    @Test void anExpiredLayoutRejectionWakesTheRealHandlerAndActuallyRemodelsWhileStanding() throws Exception {
        try (var f = new Fixture()) {
            f.move(0.5D, 64.0D, 0.5D);
            f.world.blocks.put(TARGET.above(2), Blocks.STONE.defaultBlockState());
            f.finishInitialPass();
            assertEquals(ScanState.LAZY, f.handler.getScanState());
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            int before = f.world.headProbes;
            assertTrue(before > 0);
            for (int tick = 12; tick < 20; tick++) f.step(tick);
            assertEquals(before, f.world.headProbes, "A future rejection deadline must not keep modeling every tick");

            for (int tick = 20; tick <= 24 && f.world.headProbes == before; tick++) f.step(tick);

            assertTrue(f.world.headProbes > before,
                    "Expiring rejection must create a fresh bedrock source, not merely report pending work");
            assertEquals(0, f.engine.getHudSnapshot().totalTargets());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anExpiredRejectionCanSupplyAnAvailableVerticalTargetWithoutAnOuterLazyDelay() throws Exception {
        try (var f = new Fixture()) {
            f.move(0.5D, 64.0D, 0.5D);
            f.world.blocks.put(TARGET.above(2), Blocks.STONE.defaultBlockState());
            f.finishInitialPass();
            long dirty = f.scan.dirtyVersion();
            // A deadline is also a fallback for eligibility changes outside block invalidation.
            f.world.blocks.remove(TARGET.above(2));
            assertEquals(Direction.UP, BedrockMachineLayout.find(f.world, TARGET).getPistonOffset());

            for (int tick = 20; tick <= 24 && f.engine.getHudSnapshot().totalTargets() == 0; tick++) f.step(tick);

            assertEquals(1, f.engine.getHudSnapshot().totalTargets());
            assertEquals(dirty, f.scan.dirtyVersion());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void fineMovementPreservesACooperativelyPausedCursorUntilItsPassFinishes() throws Exception {
        try (var f = new Fixture(true)) {
            f.move(0.5D, 64.0D, 0.5D);
            f.world.blocks.put(TARGET.above(2), Blocks.STONE.defaultBlockState());
            f.step(0);
            assertTrue(f.planner.hasPendingScanSource());
            Object session = f.session("bedrock:BEDROCK");
            assertNotNull(session);
            int before = f.world.headProbes;

            f.move(0.51D, 64.0D, 0.5D);
            f.step(1);
            assertFalse(f.engine.canSubmitInCurrentWindow(), "Pump the real closed tick in the 6/2 cadence");
            f.step(2);
            assertTrue(f.engine.canSubmitInCurrentWindow(), "The preserved cursor must receive an open window");

            assertSame(session, f.session("bedrock:BEDROCK"));
            assertEquals(before, f.world.headProbes, "Movement cannot replay the already modeled prefix");
            assertFalse(f.planner.hasPendingScanSource(), "The original cursor must finish its remaining work");
        }
    }

    private static final class BoundedHandler extends BedrockHandler {
        BoundedHandler(PrinterRuntime runtime) { super(runtime); }
        @Override protected int getScanGuardLimit() { return 1; }
    }

    private static final class ProbeLevel extends BedrockCandidateReachRegressionTest.ScanLevel {
        int headProbes;
        ProbeLevel() { super(); }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (TARGET.above(2).equals(pos)) headProbes++;
            return super.getBlockState(pos);
        }
    }

    private static final class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final int oldLazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        final ProbeLevel world;
        final ScanEngine scan;
        final BedrockHandler handler;
        final BedrockCandidatePlanner planner;

        Fixture() throws Exception { this(false); }

        Fixture(boolean bounded) throws Exception {
            world = allocate(ProbeLevel.class);
            world.blocks = level.blocks;
            world.blocks.clear();
            world.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            for (Direction direction : new Direction[]{Direction.DOWN, Direction.NORTH,
                    Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                world.blocks.put(TARGET.relative(direction), Blocks.STONE.defaultBlockState());
            }
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            set(client.player, Player.class, "abilities", new Abilities());
            var info = allocate(net.minecraft.client.multiplayer.PlayerInfo.class);
            set(info, net.minecraft.client.multiplayer.PlayerInfo.class, "gameMode", net.minecraft.world.level.GameType.SURVIVAL);
            set(client.player, net.minecraft.client.player.AbstractClientPlayer.class, "playerInfo", info);
            Configs.Core.WORK_RANGE.setIntegerValue(3);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            Configs.Core.LAZY_ENTER_TICKS.setIntegerValue(1);
            scan = new ScanEngine(runtime::epoch, () -> client.level, new ScanCache());
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", scan);
            set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(client, runtime::currentTick));
            handler = bounded ? new BoundedHandler(runtime) : new BedrockHandler(runtime);
            handler.playerInteractionBox.set(new PrinterBox(TARGET));
            planner = (BedrockCandidatePlanner) get(handler, BedrockHandler.class, "candidatePlanner");
        }

        void move(double x, double y, double z) throws Exception {
            set(client.player, Entity.class, "position", new Vec3(x, y, z));
        }

        void step(int tick) throws Exception {
            tick(tick);
            world.tick = tick;
            scan.beginTick(world, null, tick);
            var run = FeatureModuleBase.class.getDeclaredMethod("runIterationIfNeeded");
            run.setAccessible(true);
            run.invoke(handler);
        }

        void finishInitialPass() throws Exception {
            for (int tick = 0; tick <= 10; tick++) step(tick);
        }

        void otherSource() {
            scan.rawIterable("other", new PrinterBox(TARGET), client.player, 0, pos -> true).iterator().hasNext();
        }

        Object session(String key) throws Exception {
            var cache = get(scan, ScanEngine.class, "cache");
            var store = get(cache, ScanCache.class, "sessions");
            return ((Map<?, ?>) get(store, store.getClass(), "sessions")).get(key);
        }

        @Override public void close() throws Exception {
            Configs.Core.LAZY_ENTER_TICKS.setIntegerValue(oldLazyThreshold);
            super.close();
        }
    }
}
//#endif
