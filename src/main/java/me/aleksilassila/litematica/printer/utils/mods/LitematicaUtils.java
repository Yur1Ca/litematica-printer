package me.aleksilassila.litematica.printer.utils.mods;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;


@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class LitematicaUtils {
    public static boolean isPositionWithinRange(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }

    @SuppressWarnings("deprecation")
    public static Vec3 usePrecisionPlacement(BlockPos pos, BlockState stateSchematic) {
        if (Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue()) {
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
            Vec3 hitPos = Vec3.atLowerCornerOf(pos);
            if (protocol == EasyPlaceProtocol.V3) {
                //#if MC > 260100
                //$$ return fi.dy.masa.litematica.util.EasyPlaceUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
                //#else
                return fi.dy.masa.litematica.util.WorldUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
                //#endif
            } else if (protocol == EasyPlaceProtocol.V2) {
                // Carpet Accurate Block placements protocol support, plus slab support
                //#if MC > 260100
                //$$ return fi.dy.masa.litematica.util.EasyPlaceUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
                //#else
                return fi.dy.masa.litematica.util.WorldUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
                //#endif
            }
        }
        return null;
    }
    /**
     * 判断位置是否位于当前加载的投影范围内。
     *
     * @param pos 要检测的方块位置
     * @return 如果位置属于图纸结构的一部分，则返回 true，否则返回 false
     */
    public static boolean isSchematicBlock(BlockPos pos) {
        SchematicPlacementManager schematicPlacementManager = DataManager.getSchematicPlacementManager();
        List<SchematicPlacementManager.PlacementPart> allPlacementsTouchingChunk = schematicPlacementManager.getAllPlacementsTouchingChunk(pos);

        for (SchematicPlacementManager.PlacementPart placementPart : allPlacementsTouchingChunk) {
            //#if MC > 260100
            //$$ if (placementPart.getBox().contains(pos)) {
            //#else
            if (placementPart.getBox().containsPos(pos)) {
            //#endif
                return true;
            }
        }
        return false;
    }

    public static boolean isWithinSelection1ModeRange(BlockPos pos) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return false;
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            List<Box> arr = selection.getAllSubRegionBoxes();
            for (Box box : arr) {
                if (comparePos(box, pos)) {
                    return true;
                }
            }
            return false;
        } else {
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            return comparePos(box, pos);
        }
    }

    public static Predicate<BlockPos> createSelection1RangePredicate() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return pos -> false;
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            List<Box> arr = selection.getAllSubRegionBoxes();
            List<Bounds> bounds = new ArrayList<>(arr.size());
            for (Box box : arr) {
                Bounds bound = Bounds.from(box);
                if (bound != null) {
                    bounds.add(bound);
                }
            }
            return pos -> {
                for (Bounds bound : bounds) {
                    if (bound.contains(pos)) {
                        return true;
                    }
                }
                return false;
            };
        } else {
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            Bounds bounds = Bounds.from(box);
            return bounds == null ? pos -> false : bounds::contains;
        }
    }

    public static PrinterBox createSelection1BoundingBox() {
        List<PrinterBox> boxes = createSelection1Boxes();
        if (boxes.isEmpty()) return null;
        Bounds merged = null;
        for (PrinterBox box : boxes) {
            Bounds bounds = Bounds.from(box);
            merged = merged == null ? bounds : merged.merge(bounds);
        }
        return merged == null ? null : merged.toPrinterBox();
    }

    public static List<PrinterBox> createSelection1Boxes() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return List.of();
        List<PrinterBox> result = new ArrayList<>();
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            List<Box> boxes = selection.getAllSubRegionBoxes();
            for (Box box : boxes) {
                Bounds bounds = Bounds.from(box);
                if (bounds != null) {
                    result.add(bounds.toPrinterBox());
                }
            }
        } else {
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            Bounds bounds = Bounds.from(box);
            if (bounds != null) {
                result.add(bounds.toPrinterBox());
            }
        }
        return result;
    }

    public static List<PrinterBox> createSchematicPlacementBoxes() {
        List<PrinterBox> result = new ArrayList<>();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (!placement.matchesRequirement(SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED)) {
                continue;
            }
            Map<String, Box> boxes = placement.getSubRegionBoxes(
                    SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED
            );
            for (Box box : boxes.values()) {
                Bounds bounds = Bounds.from(box);
                if (bounds != null) {
                    result.add(bounds.toPrinterBox());
                }
            }
        }
        return result;
    }

    public static PrinterBox clampToRenderLayer(PrinterBox box) {
        if (box == null) {
            return null;
        }
        var clamped = DataManager.getRenderLayerRange().getClampedArea(
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ
        );
        if (clamped == null) {
            return null;
        }
        //#if MC > 260100
        //$$ int minX = clamped.minX();
        //$$ int minY = clamped.minY();
        //$$ int minZ = clamped.minZ();
        //$$ int maxX = clamped.maxX();
        //$$ int maxY = clamped.maxY();
        //$$ int maxZ = clamped.maxZ();
        //#else
        int minX = clamped.getMinValueForAxis(Direction.Axis.X);
        int minY = clamped.getMinValueForAxis(Direction.Axis.Y);
        int minZ = clamped.getMinValueForAxis(Direction.Axis.Z);
        int maxX = clamped.getMaxValueForAxis(Direction.Axis.X);
        int maxY = clamped.getMaxValueForAxis(Direction.Axis.Y);
        int maxZ = clamped.getMaxValueForAxis(Direction.Axis.Z);
        //#endif
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new PrinterBox(
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ
        );
    }

    static boolean comparePos(Box box, BlockPos pos) {
        if (box == null || box.getPos1() == null || box.getPos2() == null || pos == null) return false;
        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds from(Box box) {
            if (box == null || box.getPos1() == null || box.getPos2() == null) {
                return null;
            }
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();
            return new Bounds(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ()),
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())
            );
        }

        static Bounds from(PrinterBox box) {
            if (box == null) {
                return null;
            }
            return new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        Bounds merge(Bounds other) {
            return new Bounds(
                    Math.min(this.minX, other.minX),
                    Math.min(this.minY, other.minY),
                    Math.min(this.minZ, other.minZ),
                    Math.max(this.maxX, other.maxX),
                    Math.max(this.maxY, other.maxY),
                    Math.max(this.maxZ, other.maxZ)
            );
        }

        PrinterBox toPrinterBox() {
            return new PrinterBox(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }

        boolean contains(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
}
