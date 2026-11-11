package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModuleSelectionScopeTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(4, 64, 0);
    private static final PrinterBox INTERACTION_BOX = new PrinterBox(-16, 0, -16, 16, 100, 16);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingTheActualSelectionRefreshesTheCachedScopeWithoutMoving(boolean replaceArea) throws Exception {
        try (var fixture = new SelectionFixture()) {
            List<PrinterBox> original = fixture.scope.boxes(INTERACTION_BOX);
            assertEquals(List.of(new PrinterBox(FIRST)), original);

            if (replaceArea) {
                fixture.selectSimpleArea(SECOND);
            } else {
                var box = DataManager.getSimpleArea().getAllSubRegionBoxes().get(0);
                box.setPos1(SECOND);
                box.setPos2(SECOND);
            }

            assertFalse(fixture.scope.contains(FIRST));
            assertTrue(fixture.scope.contains(SECOND));
            assertEquals(List.of(new PrinterBox(SECOND)), fixture.scope.boxes(sameInteractionBox()),
                    "A stationary module must scan the current Litematica selection");
        }
    }

    @Test
    void clearingTheActualSelectionRemovesTheCachedScope() throws Exception {
        try (var fixture = new SelectionFixture()) {
            assertEquals(List.of(new PrinterBox(FIRST)), fixture.scope.boxes(INTERACTION_BOX));
            var box = DataManager.getSimpleArea().getAllSubRegionBoxes().get(0);
            box.setPos1(null);
            box.setPos2(null);

            assertFalse(fixture.scope.contains(FIRST));
            assertEquals(List.of(), fixture.scope.boxes(sameInteractionBox()),
                    "Cleared selection corners must not leave the previous scan volume active");
        }
    }

    @Test
    void unchangedSelectionReusesTheCachedScopeForAnEqualInteractionBox() throws Exception {
        try (var fixture = new SelectionFixture()) {
            List<PrinterBox> original = fixture.scope.boxes(INTERACTION_BOX);

            assertSame(original, fixture.scope.boxes(sameInteractionBox()),
                    "An unchanged selection should retain its reusable scan result");
        }
    }

    @Test
    void changingNormalSelectionSubregionsRefreshesTheScopeEvenWithTheSameOuterBounds() throws Exception {
        try (var fixture = new SelectionFixture()) {
            var area = new AreaSelection();
            area.addSubRegionBox(new Box(FIRST, FIRST, "left"), false);
            area.addSubRegionBox(new Box(SECOND, SECOND, "right"), false);
            fixture.selectNormalArea(area);
            Set<PrinterBox> original = Set.of(new PrinterBox(FIRST), new PrinterBox(SECOND));
            assertEquals(original, new HashSet<>(fixture.scope.boxes(INTERACTION_BOX)));
            var middle = new BlockPos(2, 64, 0);

            area.addSubRegionBox(new Box(middle, middle, "middle"), false);

            assertTrue(fixture.scope.contains(middle));
            assertEquals(Set.of(new PrinterBox(FIRST), new PrinterBox(middle), new PrinterBox(SECOND)),
                    new HashSet<>(fixture.scope.boxes(sameInteractionBox())),
                    "Adding an interior subregion changes scan coverage without changing the enclosing box");

            area.removeSubRegionBox("middle");

            assertFalse(fixture.scope.contains(middle));
            assertEquals(original, new HashSet<>(fixture.scope.boxes(sameInteractionBox())),
                    "Removed subregions must leave the scan scope immediately");

            area.removeAllSubRegionBoxes();
            assertEquals(List.of(), fixture.scope.boxes(sameInteractionBox()));
        }
    }

    @Test
    void movingAndHidingAPlacementSubregionRefreshesTheSchematicScanScope() throws Exception {
        var placements = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
        var previousPlacements = List.copyOf(placements);
        boolean rendering = fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.getBooleanValue();
        try (var fixture = new SelectionFixture(true)) {
            placements.clear();
            fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.setBooleanValue(true);
            var placement = oneBlockPlacement();
            placements.add(placement);
            List<PrinterBox> original = fixture.scope.boxes(INTERACTION_BOX);
            assertEquals(List.of(new PrinterBox(FIRST)), original);
            assertSame(original, fixture.scope.boxes(sameInteractionBox()));

            var subregion = placement.getRelativeSubRegionPlacement("test");
            var setPos = SubRegionPlacement.class.getDeclaredMethod("setPos", BlockPos.class);
            setPos.setAccessible(true);
            setPos.invoke(subregion, new BlockPos(4, 0, 0));

            assertEquals(List.of(new PrinterBox(SECOND)), fixture.scope.boxes(sameInteractionBox()),
                    "Placement subregion movement must update the stationary schematic scan scope");

            placement.toggleSubRegionRenderingEnabled("test");

            assertEquals(List.of(), fixture.scope.boxes(sameInteractionBox()),
                    "Subregions hidden from rendering must leave the schematic scan scope");
        } finally {
            placements.clear();
            placements.addAll(previousPlacements);
            fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_RENDERING.setBooleanValue(rendering);
        }
    }

    @Test
    void switchingRenderLayersInvalidatesTheCachedScanScope() {
        PrinterBox interactionBox = new PrinterBox(0, 0, 0, 15, 15, 15);
        PrinterBox firstLayer = new PrinterBox(0, 3, 0, 15, 3, 15);
        PrinterBox secondLayer = new PrinterBox(0, 4, 0, 15, 4, 15);

        assertNotEquals(
                ModuleSelectionScope.cacheKey(
                        interactionBox, SelectionType.LITEMATICA_RENDER_LAYER, firstLayer, List.of(interactionBox)),
                ModuleSelectionScope.cacheKey(
                        interactionBox, SelectionType.LITEMATICA_RENDER_LAYER, secondLayer, List.of(interactionBox))
        );
    }

    private static PrinterBox sameInteractionBox() {
        return new PrinterBox(-16, 0, -16, 16, 100, 16);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static SchematicPlacement oneBlockPlacement() throws Exception {
        // The one-argument in-memory constructor takes File on older versions and Path on newer ones.
        var constructor = Arrays.stream(LitematicaSchematic.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 1).findFirst().orElseThrow();
        constructor.setAccessible(true);
        var schematic = (LitematicaSchematic) constructor.newInstance(new Object[]{null});
        ((Map<String, BlockPos>) field(LitematicaSchematic.class, "subRegionPositions").get(schematic))
                .put("test", BlockPos.ZERO);
        ((Map<String, BlockPos>) field(LitematicaSchematic.class, "subRegionSizes").get(schematic))
                .put("test", new BlockPos(1, 1, 1));
        return SchematicPlacement.createTemporary(schematic, FIRST);
    }

    private static final class SelectionFixture implements AutoCloseable {
        private final DataManager data;
        private final SelectionManager manager;
        private final SelectionMode oldMode;
        private final Object oldArea;
        private final String oldSelectionId;
        private final Map<String, AreaSelection> selections;
        private final String selectionId = "printer-selection-scope-test";
        private final AreaSelection oldNormalArea;
        final ModuleSelectionScope scope;

        SelectionFixture() throws Exception {
            this(false);
        }

        SelectionFixture(boolean schematic) throws Exception {
            var instance = DataManager.class.getDeclaredMethod("getInstance");
            instance.setAccessible(true);
            this.data = (DataManager) instance.invoke(null);
            this.manager = DataManager.getSelectionManager();
            this.oldMode = this.manager.getSelectionMode();
            this.oldArea = field(DataManager.class, "areaSimple").get(this.data);
            this.oldSelectionId = (String) field(SelectionManager.class, "currentSelectionId").get(this.manager);
            this.selections = selectionMap(this.manager);
            this.oldNormalArea = this.selections.get(this.selectionId);
            var unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
            var owner = (SelectionModule) ((sun.misc.Unsafe) unsafeField.get(null))
                    .allocateInstance(SelectionModule.class);
            owner.schematic = schematic;
            field(FeatureModuleBase.class, "litematica").set(owner, new LitematicaAdapter());
            this.scope = new ModuleSelectionScope(owner, null);
            this.selectSimpleArea(FIRST);
            field(SelectionManager.class, "mode").set(this.manager, SelectionMode.SIMPLE);
        }

        void selectSimpleArea(BlockPos pos) throws Exception {
            var area = new AreaSelectionSimple(true);
            var box = area.getAllSubRegionBoxes().get(0);
            box.setPos1(pos);
            box.setPos2(pos);
            field(DataManager.class, "areaSimple").set(this.data, area);
        }

        void selectNormalArea(AreaSelection area) throws Exception {
            this.selections.put(this.selectionId, area);
            field(SelectionManager.class, "currentSelectionId").set(this.manager, this.selectionId);
            field(SelectionManager.class, "mode").set(this.manager, SelectionMode.NORMAL);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, AreaSelection> selectionMap(SelectionManager manager) throws Exception {
            return (Map<String, AreaSelection>) field(SelectionManager.class, "selections").get(manager);
        }

        @Override
        public void close() throws Exception {
            field(DataManager.class, "areaSimple").set(this.data, this.oldArea);
            field(SelectionManager.class, "mode").set(this.manager, this.oldMode);
            field(SelectionManager.class, "currentSelectionId").set(this.manager, this.oldSelectionId);
            if (this.oldNormalArea == null) this.selections.remove(this.selectionId);
            else this.selections.put(this.selectionId, this.oldNormalArea);
        }
    }

    private static final class SelectionModule extends FeatureModuleBase {
        boolean schematic;

        private SelectionModule() {
            super(null, "selection", null, null, null, false);
            throw new AssertionError("Allocated without a Minecraft client");
        }

        @Override
        protected boolean isSchematicBlockHandler() {
            return this.schematic;
        }
    }
}
