//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.world.ChunkSchematic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.function.LongSupplier;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.FeatureLifecycle;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The first real residual USE must not wait behind unrelated cold source classification. */
class PrintReadyResidualFastPathTest {
    private static final List<BlockPos> RESIDUALS = List.of(BlockPos.ZERO,
            new BlockPos(3, 0, 0), new BlockPos(6, 0, 0));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void threeReadyResidualsDispatchBeforeColdRangeReadsRegardlessOfScopeSize() throws Exception {
        int smallReads;
        try (var f = new FastPathFixture(7, new PrinterBox(0, 0, 0, 6, 0, 0))) {
            f.dispatchFirstResidual();
            smallReads = f.dispatch.unrelatedReadsBeforeFirstUse;
        }
        try (var f = new FastPathFixture(4096, new PrinterBox(0, 0, 0, 15, 15, 15))) {
            f.dispatchFirstResidual();

            assertEquals(smallReads, f.dispatch.unrelatedReadsBeforeFirstUse,
                    "Increasing the cold source cannot add unrelated world reads before the same cached action");
            assertEquals(0, f.dispatch.unrelatedReadsBeforeFirstUse,
                    "Three known ready residuals must execute without a cold prefetch first");
            assertTrue(f.unrelatedReads() > f.dispatch.unrelatedReadsBeforeFirstUse,
                    "The actual stopIteration hook still replenishes after dispatch");
        }
    }

    @Test void threeMissingRoseBushesDispatchBeforeReadingTheAlreadyMatchingField() throws Exception {
        int smallReads;
        try (var f = new FastPathFixture(7, new PrinterBox(0, 1, 0, 6, 2, 0), true)) {
            f.dispatchFirstResidual();
            smallReads = f.dispatch.unrelatedReadsBeforeFirstUse;
        }
        try (var f = new FastPathFixture(512, new PrinterBox(0, 1, 0, 15, 2, 15), true)) {
            f.dispatchFirstResidual();

            assertEquals(smallReads, f.dispatch.unrelatedReadsBeforeFirstUse,
                    "Three missing rose bushes cannot be delayed by a larger already matching field");
            assertEquals(0, f.dispatch.unrelatedReadsBeforeFirstUse);
            assertEquals(Items.ROSE_BUSH, f.client.player.getMainHandItem().getItem());
            assertTrue(f.unrelatedReads() > 0, "The matching field remains available to post-execution scanning");
        }
    }

    @Test void aFailedReadyPrefixCannotStarveTheLastThreeTargetsAcrossExpensiveGuideSlices() throws Exception {
        int previousWork = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new FastPathFixture(4096, new PrinterBox(0, 0, 0, 15, 15, 15))) {
            Configs.Core.WORK_PER_TICK.setIntegerValue(256);
            List<BlockPos> round = new ArrayList<>();
            for (int y = 0; y < 4; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                round.add(new BlockPos(x, y, z));
            round.sort(f.runtime.scanEngine().targetComparator("print_sorted"));
            List<BlockPos> executable = round.subList(1021, 1024);
            @SuppressWarnings("unchecked")
            var chunks = (Map<Long, ChunkSchematic>) field(f.scanFixture, f.scanFixture.getClass(), "chunks");
            var section = chunks.get(0L).getSections()[4];
            f.targets.invalidateCandidates();
            f.actionNeighborhood.clear();
            for (BlockPos pos : round) {
                section.setBlockState(pos.getX(), pos.getY(), pos.getZ(), executable.contains(pos)
                        ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.TORCH.defaultBlockState());
                f.targets.deferUntil(pos, 0L);
                f.actionNeighborhood.add(pos);
                for (Direction direction : Direction.values()) f.actionNeighborhood.add(pos.relative(direction));
            }
            Object cache = field(f.scanFixture, f.scanFixture.getClass(), "cache");
            Object budget = field(cache, cache.getClass(), "budget");
            // Real guide reads consume deterministic virtual planning time, independent of JVM warmup.
            set(budget, budget.getClass(), "nanoClock", (LongSupplier) () ->
                    f.reads.values().stream().mapToLong(Integer::longValue).sum() * 10_000L);
            for (long now = 10; now < 50 && f.dispatch.hits.size() < 3; now++) {
                f.scanTick.invoke(f.scanFixture, now);
                f.tick(now);
                f.unrelatedReadsAtIterationStart = f.unrelatedReads();
                f.handler.runIteration(f.scope);
            }

            assertEquals(executable, f.dispatch.hits.stream().map(BlockHitResult::getBlockPos).toList(),
                    "Every initial ready target gets its turn before newly due failed-prefix retries begin another round");
            assertEquals(0, f.dispatch.unrelatedReadsBeforeFirstUse,
                    "The cached tail still dispatches before this tick's unrelated cold range reads");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

    @Test void aCompletedRetryRoundGivesTheWarmFreshTailAnExecutionTurn() throws Exception {
        int previousWork = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try (var f = new FastPathFixture(4096, new PrinterBox(0, 0, 0, 15, 15, 15))) {
            Configs.Core.WORK_PER_TICK.setIntegerValue(256);
            List<BlockPos> executable = List.of(new BlockPos(0, 4, 0), new BlockPos(3, 4, 0), new BlockPos(6, 4, 0));
            @SuppressWarnings("unchecked")
            var chunks = (Map<Long, ChunkSchematic>) field(f.scanFixture, f.scanFixture.getClass(), "chunks");
            var section = chunks.get(0L).getSections()[4];
            @SuppressWarnings("unchecked")
            var blocks = (Map<BlockPos, BlockState>) field(f.client.level, f.client.level.getClass(), "world");
            f.targets.invalidateCandidates();
            f.actionNeighborhood.clear();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (y < 4) {
                    section.setBlockState(x, y, z, Blocks.TORCH.defaultBlockState());
                    f.targets.deferUntil(pos, 0L);
                    f.actionNeighborhood.add(pos);
                    for (Direction direction : Direction.values()) f.actionNeighborhood.add(pos.relative(direction));
                } else if (executable.contains(pos)) {
                    section.setBlockState(x, y, z, Blocks.COBBLESTONE.defaultBlockState());
                    f.actionNeighborhood.add(pos);
                    for (Direction direction : Direction.values()) f.actionNeighborhood.add(pos.relative(direction));
                } else blocks.put(pos, section.getBlockState(x, y, z));
            }
            Object cache = field(f.scanFixture, f.scanFixture.getClass(), "cache");
            Object budget = field(cache, cache.getClass(), "budget");
            set(budget, budget.getClass(), "nanoClock", (LongSupplier) () ->
                    f.reads.values().stream().mapToLong(Integer::longValue).sum() * 10_000L);
            boolean warmed = false;
            for (long now = 10; now < 90 && f.dispatch.hits.size() < 3; now++) {
                f.scanTick.invoke(f.scanFixture, now);
                f.tick(now);
                f.unrelatedReadsAtIterationStart = f.unrelatedReads();
                f.handler.runIteration(f.scope);
                warmed |= f.targets.pendingTargetCount() > 1024;
            }

            assertTrue(warmed, "The real post-execution refill must first discover warm work outside the retry set");
            assertEquals(new HashSet<>(executable), new HashSet<>(f.dispatch.hits.stream().map(BlockHitResult::getBlockPos).toList()),
                    "Continuously due blocked retries cannot monopolize the separate warm fresh buffer");
            assertEquals(3, f.dispatch.hits.size());
            assertEquals(0, f.dispatch.unrelatedReadsBeforeFirstUse,
                    "A warm fresh action still precedes unrelated cold scanning in its dispatch tick");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(previousWork);
        }
    }

    @Test void anEarlierWarmTargetKeepsItsGlobalRankAheadOfALaterRetry() throws Exception {
        try (var f = new FastPathFixture(7, new PrinterBox(0, 0, 0, 6, 0, 0))) {
            f.targets.invalidateCandidates();
            f.targets.deferUntil(new BlockPos(6, 0, 0), 0L);
            var fresh = (me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue)
                    get(f.targets, PrintTargetScheduler.class, "sortedTargets");
            fresh.requeue(BlockPos.ZERO);

            f.dispatchFirstResidual();

            assertEquals(0, f.dispatch.unrelatedReadsBeforeFirstUse);
        }
    }

    private static final class CachedPrintHandler extends PrintHandler {
        CachedPrintHandler(PrinterRuntime runtime) { super(runtime); }
        @Override protected List<PrinterBox> getScanSourceBoxes(PrinterBox box) { return List.of(box); }
        @Override protected boolean canReachIterationPosition(BlockPos pos) { return true; }
        @Override protected boolean isInSelectionRange(BlockPos pos) { return true; }
        @Override protected boolean isSchematicBlock(BlockPos pos) { return true; }
    }

    private static final class CountedDispatch extends TestGameMode {
        FastPathFixture fixture;
        int unrelatedReadsBeforeFirstUse = -1;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand,
                                                                      BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            if (this.hits.isEmpty()) this.unrelatedReadsBeforeFirstUse =
                    fixture.unrelatedReads() - fixture.unrelatedReadsAtIterationStart;
            this.hits.add(hit);
            var packet = new ServerboundUseItemOnPacket(hand, hit, this.hits.size());
            fixture.connection.send(packet);
            // JUnit does not transform the Connection mixin; observe the emitted original USE.
            NetworkUtils.observeUseItemOn(fixture.transport, packet);
            return InteractionResult.FAIL;
        }
    }

    private static final class FastPathFixture extends Fixture {
        final boolean oldSorted = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        final boolean oldAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        final boolean oldReserve = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        final boolean oldPrecision = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        final boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final Field schematicField = SchematicWorldHandler.class.getDeclaredField("world");
        final Object oldSchematic;
        final AutoCloseable scanFixture;
        final Method scanTick;
        final Map<BlockPos, Integer> reads;
        final Set<BlockPos> actionNeighborhood = new HashSet<>();
        final Holder<Block> farmland = BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.FARMLAND);
        final List<TagKey<Block>> oldFarmlandTags = farmland.tags().toList();
        final List<BlockPos> residuals;
        final PrinterBox scope;
        final CachedPrintHandler handler;
        final PrintTargetScheduler targets;
        final CountedDispatch dispatch;
        int unrelatedReadsAtIterationStart;

        FastPathFixture(int sourceCount, PrinterBox scope) throws Exception {
            this(sourceCount, scope, false);
        }

        @SuppressWarnings("unchecked")
        FastPathFixture(int sourceCount, PrinterBox scope, boolean roses) throws Exception {
            this.scope = scope;
            this.residuals = roses ? RESIDUALS.stream().map(BlockPos::above).toList() : RESIDUALS;
            Class<?> type = Class.forName("me.aleksilassila.litematica.printer.handler.scan.ScanIntegrationFixture");
            var constructor = type.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            Object scan = constructor.newInstance(roses ? 0 : sourceCount);
            scanFixture = (AutoCloseable) scan;
            scanTick = type.getDeclaredMethod("tick", long.class);
            scanTick.setAccessible(true);
            ClientLevel world = (ClientLevel) field(scan, type, "level");
            reads = (Map<BlockPos, Integer>) field(world, world.getClass(), "reads");
            WorldSchematic schematic = (WorldSchematic) field(scan, type, "schematic");
            if (roses) {
                List<TagKey<Block>> soilTags = new ArrayList<>(this.oldFarmlandTags);
                if (!soilTags.contains(BlockTags.SUPPORTS_VEGETATION)) soilTags.add(BlockTags.SUPPORTS_VEGETATION);
                this.bindFarmlandTags(soilTags);
                var chunks = (Map<Long, ChunkSchematic>) field(scan, type, "chunks");
                var section = chunks.get(0L).getSections()[4];
                var blocks = (Map<BlockPos, BlockState>) field(world, world.getClass(), "world");
                BlockState lower = Blocks.ROSE_BUSH.defaultBlockState()
                        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                for (int z = scope.minZ; z <= scope.maxZ; z++) {
                    for (int x = scope.minX; x <= scope.maxX; x++) {
                        BlockPos pos = new BlockPos(x, 1, z);
                        section.setBlockState(x, 1, z, lower);
                        section.setBlockState(x, 2, z, upper);
                        blocks.put(pos.below(), Blocks.FARMLAND.defaultBlockState());
                        if (!this.residuals.contains(pos)) {
                            blocks.put(pos, lower);
                            blocks.put(pos.above(), upper);
                        }
                    }
                }
                assertTrue(lower.canSurvive(world, this.residuals.getFirst()),
                        "Headless vegetation tags must permit the actual rose on its farmland support");
            }
            var engineMethod = type.getDeclaredMethod("engine");
            engineMethod.setAccessible(true);
            ScanEngine engine = (ScanEngine) engineMethod.invoke(scan);
            client.level = world;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            set(client.player, Entity.class, "level", world);
            set(client.player, Entity.class, "position", new Vec3(0.5, 0, 0.5));
            set(client.player, Entity.class, "blockPosition", BlockPos.ZERO);
            client.player.getInventory().setItem(0, new ItemStack(roses ? Items.ROSE_BUSH : Items.COBBLESTONE, 64));
            InventoryUtils.setSelectedSlot(client.player.getInventory(), 0);
            dispatch = allocate(CountedDispatch.class);
            dispatch.fixture = this;
            dispatch.hits = new ArrayList<>();
            client.gameMode = dispatch;
            var referenceClient = Reference.class.getDeclaredField("MINECRAFT");
            referenceClient.setAccessible(true);
            cachedClients.put(referenceClient, referenceClient.get(null));
            unsafe.putObject(unsafe.staticFieldBase(referenceClient), unsafe.staticFieldOffset(referenceClient), client);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(true);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            schematicField.setAccessible(true);
            oldSchematic = schematicField.get(SchematicWorldHandler.INSTANCE);
            schematicField.set(SchematicWorldHandler.INSTANCE, schematic);
            FeatureLifecycle lifecycle = new FeatureLifecycle();
            var actions = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> lifecycle,
                    new ActionManager(source -> new PlacementPolicy(() -> -1, false, false)));
            InventoryProvider unavailable = new InventoryProvider() {
                @Override public String id() { return "residual-fast-path"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
                }
            };
            set(runtime, PrinterRuntime.class, "timing", RuntimeTiming.disabled());
            set(runtime, PrinterRuntime.class, "scanEngine", engine);
            set(runtime, PrinterRuntime.class, "actionBroker", actions);
            set(runtime, PrinterRuntime.class, "cooldownUtils", new CooldownUtils());
            set(runtime, PrinterRuntime.class, "missingMaterials", new MissingMaterialTracker());
            set(runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
            set(runtime, PrinterRuntime.class, "litematica", new LitematicaAdapter());
            set(runtime, PrinterRuntime.class, "materialRequests", new MaterialRequestCoordinator(List.of(unavailable)));
            handler = new CachedPrintHandler(runtime);
            targets = (PrintTargetScheduler) get(handler, PrintHandler.class, "targets");
            targets.setSortedMode(true);
            for (BlockPos pos : this.residuals) {
                targets.deferUntil(pos, 0L);
                actionNeighborhood.add(pos);
                for (Direction direction : Direction.values()) actionNeighborhood.add(pos.relative(direction));
            }
        }

        void dispatchFirstResidual() throws Exception {
            scanTick.invoke(scanFixture, 10L);
            tick(10L);
            handler.runIteration(scope);

            assertEquals(1, dispatch.hits.size(), "The actual runner must dispatch one real USE through its unchanged action limit");
            assertEquals(this.residuals.getFirst(), dispatch.hits.getFirst().getBlockPos());
            assertEquals(1, transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertTrue(runtime.hudStats().isPrintPlacementPending(this.residuals.getFirst()),
                    "Actual sent ownership must keep its original confirmation path");
        }

        int unrelatedReads() {
            return reads.entrySet().stream().filter(entry -> !actionNeighborhood.contains(entry.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
        }

        private void bindFarmlandTags(Collection<TagKey<Block>> tags) throws Exception {
            var method = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            method.setAccessible(true);
            method.invoke(this.farmland, tags);
        }

        @Override public void close() throws Exception {
            this.bindFarmlandTags(this.oldFarmlandTags);
            schematicField.set(SchematicWorldHandler.INSTANCE, oldSchematic);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(oldSorted);
            Configs.Print.PLACE_IN_AIR.setBooleanValue(oldAir);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(oldReserve);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(oldPrecision);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
            scanFixture.close();
            super.close();
        }
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
//#endif
