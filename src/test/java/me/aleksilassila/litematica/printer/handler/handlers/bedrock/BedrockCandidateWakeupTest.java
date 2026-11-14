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
            assertTrue(f.engine.canSubmitInCurrentWindow(), "Scanning need not wait for a construction-start window");

            assertSame(session, f.session("bedrock:BEDROCK"));
            assertEquals(before, f.world.headProbes, "Movement cannot replay the already modeled prefix");
            assertFalse(f.planner.hasPendingScanSource(), "The original cursor must finish its remaining work");
        }
    }

    @Test void aDirtyCachedConflictCannotHideAnIndependentCandidateForOneTick() throws Exception {
        try (var f = new Fixture()) {
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(2);
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            BlockPos stale = TARGET;
            BlockPos independent = TARGET.east();
            BlockPos active = TARGET.west();
            f.world.blocks.clear();
            f.world.blocks.put(stale, Blocks.BEDROCK.defaultBlockState());
            f.world.blocks.put(independent, Blocks.BEDROCK.defaultBlockState());
            f.world.blocks.put(active, Blocks.STONE.defaultBlockState());
            f.world.blocks.put(stale.below(), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(active.below(), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(independent.above(2), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(independent.east().below(), Blocks.STONE.defaultBlockState());
            f.handler.playerInteractionBox.set(new PrinterBox(stale, independent));
            f.scan.beginTick(f.world, null, 0);

            var cachedStale = BedrockCandidatePlan.build(f.world, stale, f.scan::dirtyVersion);
            var cachedIndependent = BedrockCandidatePlan.build(f.world, independent, f.scan::dirtyVersion);
            assertNotNull(cachedStale.layout());
            assertNotNull(cachedIndependent.layout());
            assertEquals(Direction.UP, cachedStale.layout().getPistonOffset());
            assertEquals(Direction.DOWN, cachedIndependent.layout().getPistonOffset(),
                    "The independent piston must remain outside the active machine's torch influence");
            assertEquals(cachedStale.priority(), cachedIndependent.priority());
            assertEquals(cachedStale.neighborTargetCount(), cachedIndependent.neighborTargetCount());
            var pair = new BedrockCandidateConflictIndex();
            assertTrue(pair.tryReserve(cachedStale));
            assertFalse(pair.tryReserve(cachedIndependent), "The stale cached choice really excludes this candidate");
            @SuppressWarnings("unchecked")
            var backlog = (BedrockCandidateBacklog<BedrockCandidatePlan>) get(
                    f.planner, BedrockCandidatePlanner.class, "candidateBacklog");
            assertTrue(backlog.offer(stale, cachedStale));
            assertTrue(backlog.offer(independent, cachedIndependent));

            // Another real reservation changes the cached plans' live cost before their next pass.
            // The native inventory fence preserves admission while excluding construction side effects.
            f.beginFence();
            f.tick(0);
            f.engine.beginTick(true);
            f.world.blocks.put(active, Blocks.BEDROCK.defaultBlockState());
            var activePlan = BedrockCandidatePlan.build(f.world, active, f.scan::dirtyVersion);
            assertNotNull(activePlan.layout());
            assertEquals(Direction.UP, activePlan.layout().getPistonOffset());
            f.engine.primeSubmissionPlan(active, activePlan.layout(), activePlan.placement(), activePlan.slimePos());
            assertTrue(f.engine.submit(active));
            f.scan.invalidate(active);
            assertTrue(f.scan.dirtyVersion() > cachedStale.planRevision());
            var liveStale = BedrockCandidatePlan.build(f.world, stale, f.scan::dirtyVersion);
            var liveIndependent = BedrockCandidatePlan.build(f.world, independent, f.scan::dirtyVersion);
            assertTrue(liveStale.priority() > liveIndependent.priority(),
                    "Fresh ordering must prefer the candidate which avoids the existing reservation");
            assertNotNull(f.targets.findConflict(f.target(liveStale)));
            assertNull(f.targets.findConflict(f.target(liveIndependent)),
                    "The independent candidate must pass the real structural and power conflict rules");
            assertEquals(2, f.engine.getHudSnapshot().activeCap());
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            assertTrue(f.engine.canAccept(independent));
            assertFalse(f.engine.isPositionOnRetryCooldown(independent));
            int sent = f.transport.packets.size();

            f.step(1);
            boolean admittedOnFirstPass = f.targets.containsOwner(independent);
            f.step(2);

            assertTrue(f.targets.containsOwner(independent),
                    "The unchanged world and capacity allow the real handler to admit this candidate");
            assertFalse(f.targets.containsOwner(stale));
            assertEquals(sent, f.transport.packets.size(), "This regression observes admission through the existing fence");
            assertTrue(admittedOnFirstPass,
                    "Refresh dirty priorities before the stale choice can reserve the candidate conflict index");
        }
    }

    @Test void aNewReservationWithoutABlockUpdateCannotKeepAnIndependentCandidateOutOfTheIteration() throws Exception {
        try (var f = new Fixture()) {
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(2);
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            BlockPos stale = TARGET;
            BlockPos independent = TARGET.east();
            BlockPos active = TARGET.west();
            f.world.blocks.clear();
            for (BlockPos pos : new BlockPos[]{stale, independent, active, independent.south()}) {
                f.world.blocks.put(pos, Blocks.BEDROCK.defaultBlockState());
            }
            f.world.blocks.put(stale.below(), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(active.below(), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(independent.above(2), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(independent.east().below(), Blocks.STONE.defaultBlockState());
            f.handler.playerInteractionBox.set(new PrinterBox(stale, independent));
            f.scan.beginTick(f.world, null, 0);
            var cachedStale = BedrockCandidatePlan.build(f.world, stale, f.scan::dirtyVersion);
            var cachedIndependent = BedrockCandidatePlan.build(f.world, independent, f.scan::dirtyVersion);
            assertNotNull(cachedStale.layout());
            assertNotNull(cachedIndependent.layout());
            assertEquals(Direction.UP, cachedStale.layout().getPistonOffset());
            assertEquals(Direction.DOWN, cachedIndependent.layout().getPistonOffset());
            assertEquals(cachedStale.priority(), cachedIndependent.priority());
            assertEquals(cachedStale.neighborTargetCount(), cachedIndependent.neighborTargetCount());
            var pair = new BedrockCandidateConflictIndex();
            assertTrue(pair.tryReserve(cachedStale));
            assertFalse(pair.tryReserve(cachedIndependent));
            @SuppressWarnings("unchecked")
            var backlog = (BedrockCandidateBacklog<BedrockCandidatePlan>) get(
                    f.planner, BedrockCandidatePlanner.class, "candidateBacklog");
            assertTrue(backlog.offer(stale, cachedStale));
            assertTrue(backlog.offer(independent, cachedIndependent));

            f.beginFence();
            f.tick(0);
            f.engine.beginTick(true);
            long revision = f.scan.dirtyVersion();
            var activePlan = BedrockCandidatePlan.build(f.world, active, f.scan::dirtyVersion);
            assertNotNull(activePlan.layout());
            f.engine.primeSubmissionPlan(active, activePlan.layout(), activePlan.placement(), activePlan.slimePos());
            assertTrue(f.engine.submit(active));
            var liveStale = BedrockCandidatePlan.build(f.world, stale, f.scan::dirtyVersion);
            var liveIndependent = BedrockCandidatePlan.build(f.world, independent, f.scan::dirtyVersion);
            assertTrue(liveStale.priority() > liveIndependent.priority());
            assertNotNull(f.targets.findConflict(f.target(liveStale)));
            assertNull(f.targets.findConflict(f.target(liveIndependent)));
            assertEquals(2, f.engine.getHudSnapshot().activeCap());
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            assertFalse(f.engine.canAccept(stale));
            assertTrue(f.engine.canAccept(independent));
            assertFalse(f.engine.isPositionOnRetryCooldown(independent));
            int sent = f.transport.packets.size();

            f.step(1);
            boolean admittedWithoutAWorldUpdate = f.targets.containsOwner(independent);
            var retainedStale = backlog.snapshot().stream().filter(plan -> plan.pos().equals(stale)).findFirst().orElseThrow();
            assertSame(cachedStale.layout(), retainedStale.layout(), "A reservation cost change does not rebuild the layout");
            assertSame(cachedStale.placement(), retainedStale.placement(), "The cached placement stays frozen without a world update");
            assertSame(cachedStale.structuralPositions(), retainedStale.structuralPositions());
            // Removing only the ineligible cached choice proves that the unchanged real handler
            // can use the free slot; no movement, dirty update or cooldown expiry is supplied.
            backlog.remove(stale);
            f.step(2);

            assertTrue(f.targets.containsOwner(independent));
            assertFalse(f.targets.containsOwner(stale));
            assertEquals(revision, f.scan.dirtyVersion(), "Reservation and iteration are not block updates");
            assertEquals(sent, f.transport.packets.size());
            assertTrue(admittedWithoutAWorldUpdate,
                    "A cached choice rejected by the current reservation must not hide an independent admissible target");
        }
    }

    @Test void ordinaryAdmissionUsesStableRankEvenWhenTheBacklogWasInsertedInReverse() throws Exception {
        try (var f = new Fixture()) {
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(1);
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            BlockPos later = TARGET.south();
            f.world.blocks.clear();
            f.world.blocks.put(TARGET, Blocks.BEDROCK.defaultBlockState());
            f.world.blocks.put(later, Blocks.BEDROCK.defaultBlockState());
            f.world.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            f.world.blocks.put(later.below(), Blocks.STONE.defaultBlockState());
            f.handler.playerInteractionBox.set(new PrinterBox(TARGET, later));
            f.scan.beginTick(f.world, null, 0);
            var firstPlan = BedrockCandidatePlan.build(f.world, TARGET, f.scan::dirtyVersion);
            var laterPlan = BedrockCandidatePlan.build(f.world, later, f.scan::dirtyVersion);
            assertNotNull(firstPlan.layout());
            assertNotNull(laterPlan.layout());
            assertEquals(firstPlan.priority(), laterPlan.priority());
            assertEquals(firstPlan.neighborTargetCount(), laterPlan.neighborTargetCount());
            @SuppressWarnings("unchecked")
            var backlog = (BedrockCandidateBacklog<BedrockCandidatePlan>) get(
                    f.planner, BedrockCandidatePlanner.class, "candidateBacklog");
            assertTrue(backlog.offer(later, laterPlan));
            assertTrue(backlog.offer(TARGET, firstPlan));
            f.beginFence();

            f.step(0);

            assertTrue(f.targets.containsOwner(TARGET), "The first construction rank wins equal ready plans");
            assertFalse(f.targets.containsOwner(later));
        }
    }

    @Test void pausedBedrockDiscoveryReportsFullAndEventuallyReachesTheTarget() throws Exception {
        int previousWork = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new Fixture()) {
            Configs.Core.WORK_PER_TICK.setIntegerValue(256);
            Configs.Core.WORK_RANGE.setIntegerValue(32);
            f.move(0.5D, 64.0D, 0.5D);
            // The selection is a Y=64 plane. This deliberately includes empty lower layers
            // to distinguish discovery's PAUSED state from a genuinely completed empty source.
            f.handler.playerInteractionBox.set(new PrinterBox(-16, 60, -16, 16, 64, 16));
            f.step(0);
            boolean pendingAfterFirstTick = f.planner.hasPendingScanSource();
            ScanState firstState = f.handler.getScanState();
            int initiallyAdmitted = f.engine.getHudSnapshot().totalTargets();
            for (int tick = 1; tick <= 64 && f.engine.getHudSnapshot().totalTargets() == 0; tick++) f.step(tick);
            assertEquals(1, f.engine.getHudSnapshot().totalTargets(),
                    "The cursor must keep advancing toward the valid target without movement or block updates");
            assertTrue(pendingAfterFirstTick, "This fixture must really exhaust a discovery slice");
            assertEquals(0, initiallyAdmitted, "Even the clipped selected plane has a prefix longer than this slice");
            assertEquals(ScanState.FULL, firstState,
                    "A planner must preserve PAUSED instead of reporting sleeping while its discovery cursor advances");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

    @Test void aHugeInteractionWindowScansOnlyItsSmallSelectedIntersectionBeforeAdmission() throws Exception {
        this.assertSelectedIntersectionAdmission(false);
    }

    @Test void workRange64WithExtendedPhysicalReachAndDistanceCheckStillAdmitsTheSelectedTarget() throws Exception {
        this.assertSelectedIntersectionAdmission(true);
    }

    private void assertSelectedIntersectionAdmission(boolean extendedPhysicalReach) throws Exception {
        int previousWork = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new Fixture()) {
            Configs.Core.WORK_PER_TICK.setIntegerValue(8192);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            if (extendedPhysicalReach) {
                f.extendInteractionReach();
                Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
                assertEquals(80.0D, f.client.player.blockInteractionRange());
            }
            f.move(0.5D, 64.0D, 0.5D);
            f.handler.playerInteractionBox.set(extendedPhysicalReach
                    ? new PrinterBox(-64, 1, -64, 65, 130, 65)
                    : new PrinterBox(-32, 32, -32, 32, 96, 32));
            assertTrue(new LitematicaAdapter().isWithinSelectionRange(TARGET));
            assertFalse(new LitematicaAdapter().isWithinSelectionRange(TARGET.below()));
            assertTrue(BedrockEnvironment.canInteract(TARGET));
            assertNotNull(BedrockMachineLayout.find(f.world, TARGET));

            f.step(0);

            assertEquals(1, f.engine.getHudSnapshot().totalTargets(),
                    "A small selected plane must not wait behind unrelated volume in the larger interaction window");
            assertTrue(f.targets.containsOwner(TARGET), "Discovery must reach actual controller admission");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }





    private static final class BoundedHandler extends BedrockHandler {
        BoundedHandler(PrinterRuntime runtime) { super(runtime); }
        @Override protected int getScanGuardLimit() { return 1; }
    }

    private static final class ExtendedReachPlayer extends BedrockPlacementRegressionTest.TestPlayer {
        @Override public double blockInteractionRange() { return 80.0D; }
        @Override public Abilities getAbilities() { return new Abilities(); }
        @Override public boolean onGround() { return true; }
        @Override public boolean hasEffect(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) { return false; }
        @Override public boolean isEyeInFluid(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) { return false; }
        @Override public double getAttributeValue(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
            if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.MINING_EFFICIENCY)) return 0.0D;
            return attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_BREAK_SPEED) ? 100.0D : 1.0D;
        }
        @Override public net.minecraft.world.entity.ai.attributes.AttributeInstance getAttribute(
                net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
            var value = new net.minecraft.world.entity.ai.attributes.AttributeInstance(attribute, ignored -> { });
            value.setBaseValue(this.getAttributeValue(attribute));
            return value;
        }
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
            var cache = new ScanCache();
            var budget = get(cache, ScanCache.class, "budget");
            // Cooperative slicing is controlled by BoundedHandler's guard, not JVM warm-up time.
            set(budget, budget.getClass(), "nanoClock", (java.util.function.LongSupplier) () -> 0L);
            scan = new ScanEngine(runtime::epoch, () -> client.level, cache);
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

        void extendInteractionReach() throws Exception {
            var previous = client.player;
            var extended = allocate(ExtendedReachPlayer.class);
            extended.inventory = ((BedrockPlacementRegressionTest.TestPlayer) previous).inventory;
            set(extended, Player.class, "inventory", extended.inventory);
            set(extended, Player.class, "inventoryMenu", previous.inventoryMenu);
            extended.containerMenu = previous.containerMenu;
            set(extended, Entity.class, "level", world);
            set(extended, Entity.class, "position", previous.position());
            set(extended, Entity.class, "blockPosition", new BlockPos(0, 64, 0));
            set(extended, Entity.class, "eyeHeight", 1.5F);
            set(extended, net.minecraft.client.player.LocalPlayer.class, "connection", connection);
            set(extended, net.minecraft.client.player.AbstractClientPlayer.class, "playerInfo",
                    get(previous, net.minecraft.client.player.AbstractClientPlayer.class, "playerInfo"));
            client.player = extended;
            set(handler, FeatureModuleBase.class, "player", extended);
        }

        BedrockTarget target(BedrockCandidatePlan plan) throws Exception {
            return new BedrockTarget(plan.pos(), world, plan.layout(), plan.placement(), plan.slimePos(),
                    (BedrockCriticalExecutor) get(engine, BedrockEngine.class, "criticalExecutor"), enginePlacer,
                    (BedrockNetworkSync) get(engine, BedrockEngine.class, "networkSync"));
        }

        void step(int tick) throws Exception {
            tick(tick);
            world.tick = tick;
            scan.beginTick(world, null, tick);
            var preprocess = BedrockHandler.class.getDeclaredMethod("preprocess");
            preprocess.setAccessible(true);
            preprocess.invoke(handler);
            var run = FeatureModuleBase.class.getDeclaredMethod("runIterationIfNeeded");
            run.setAccessible(true);
            try { run.invoke(handler); }
            finally { BedrockController.finishTick(true); }
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
            super.close();
        }
    }
}
//#endif
