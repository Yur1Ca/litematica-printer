//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.config.options.ConfigOptionList;
import me.aleksilassila.litematica.printer.feature.SharedPlacementSettings;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.CoverHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the actual three placement handlers' final retry/fresh selection. */
class OrdinaryPlacementOrderTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"fill", "cover", "fluid"})
    void retriesReenterTheirStableRankAmongFreshCandidates(String mode) throws Exception {
        try (var fixture = new MineCandidateRefreshFixture(true)) {
            Class<? extends FeatureModuleBase> type = switch (mode) {
                case "fill" -> FillHandler.class;
                case "cover" -> CoverHandler.class;
                default -> FluidHandler.class;
            };
            FeatureModuleBase handler = alloc(type);
            set(fixture.mining.player, net.minecraft.world.entity.Entity.class, "blockPosition", BlockPos.ZERO);
            PrinterBox box = new PrinterBox(0, 0, 0, 3, 0, 0);
            Set<BlockPos> retries = new LinkedHashSet<>(List.of(new BlockPos(3, 0, 0), new BlockPos(1, 0, 0)));
            set(handler, type, "retryTargets", retries);
            set(handler, type, "inFlightTargets", new LinkedHashSet<>());
            set(handler, FeatureModuleBase.class, "player", fixture.mining.player);
            set(handler, FeatureModuleBase.class, "level", fixture.level);
            set(handler, FeatureModuleBase.class, "playerInteractionBox", new AtomicReference<>(box));
            set(handler, FeatureModuleBase.class, "litematica", new LitematicaAdapter());
            Class<?> scopeType = Class.forName("me.aleksilassila.litematica.printer.handler.ModuleSelectionScope");
            var constructor = scopeType.getDeclaredConstructor(FeatureModuleBase.class, ConfigOptionList.class);
            constructor.setAccessible(true);
            set(handler, FeatureModuleBase.class, "selectionScope", constructor.newInstance(handler, null));
            SharedPlacementSettings settings = new SharedPlacementSettings() {
                @Override public boolean placeInAir() { return true; }
                @Override public List<String> replaceableList() { return List.of(); }
            };
            if (handler instanceof FillHandler) {
                set(handler, type, "placementSettings", settings);
                set(handler, type, "replaceableFilters", new String[0]);
                set(handler, type, "fillModeItemList", new Item[]{Items.STONE});
                set(handler, type, "fillScanBoxes", List.of());
            } else if (handler instanceof FluidHandler) {
                set(handler, type, "placementSettings", settings);
                set(handler, type, "fluids", Set.of(Fluids.WATER));
            }
            for (int x = 0; x < 4; x++) {
                BlockPos pos = new BlockPos(x, 0, 0);
                fixture.state(pos, (mode.equals("fluid") ? Blocks.WATER : Blocks.AIR).defaultBlockState());
                fixture.state(pos.below(), Blocks.STONE.defaultBlockState());
            }
            ScanCache cache = new ScanCache();
            Object budget = get(cache, ScanCache.class, "budget");
            set(budget, budget.getClass(), "nanoClock", (LongSupplier) () -> 0L);
            ScanEngine engine = new ScanEngine(() -> me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL,
                    () -> fixture.level, cache);
            engine.beginTick(fixture.level, null, 0);
            set(handler, FeatureModuleBase.class, "scanEngine", engine);
            var method = type.getDeclaredMethod("getIterationPositions", PrinterBox.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Iterable<BlockPos> candidates = (Iterable<BlockPos>) method.invoke(handler, box);
            List<BlockPos> selected = new ArrayList<>();
            candidates.forEach(selected::add);

            assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)), selected);
        }
    }
}
//#endif
