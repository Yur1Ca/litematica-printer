package me.aleksilassila.litematica.printer.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import me.aleksilassila.litematica.printer.config.ConfigMetadata;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Presentation only: every entry is the original, live configuration object. */
public final class WorkspaceConfigModel {
    private WorkspaceConfigModel() {
    }

    public record Section(String key, List<IConfigBase> configs) {
    }

    public static List<Section> sections(WorkspacePage page) {
        return sections(page, false);
    }

    public static List<Section> sections(WorkspacePage page, boolean printingDetails) {
        Sections groups = new Sections();
        switch (page) {
            case WORKBENCH -> workbench(groups, printingDetails);
            case PRINT -> {
                printingCommon(groups);
                groups.add("printing_details", Configs.Print.OPTIONS);
            }
            case MINE, FILL, COVER, FLUID, BEDROCK -> function(groups, page);
            case COMMON -> {
                groups.add("core", Configs.Core.OPTIONS);
                groups.add("placement", Configs.Placement.OPTIONS);
                groups.add("breaking", Configs.Break.OPTIONS);
            }
            case MATERIALS -> groups.add("materials", Configs.Special.OPTIONS);
            case HIGHLIGHT -> groups.add("highlight", Configs.Highlight.OPTIONS);
            case HOTKEYS -> groups.add("hotkeys", Configs.Hotkeys.OPTIONS);
        }
        return List.copyOf(groups.sections);
    }

    public static List<IConfigBase> allVisibleConfigs() {
        return Configs.OPTIONS.stream().filter(ConfigMetadata::isVisible).distinct().toList();
    }

    static boolean isPrintingActive() {
        return Configs.Core.WORK_MODE.getOptionListValue() == WorkingModeType.MULTI
                ? Configs.Core.PRINT.getBooleanValue()
                : Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.PRINTER;
    }

    private static void workbench(Sections groups, boolean printingDetails) {
        groups.add("state", Configs.Core.WORK_MODE, Configs.Core.WORK_MODE_TYPE,
                Configs.Core.PRINT, Configs.Core.MINE, Configs.Core.FILL, Configs.Core.COVER,
                Configs.Core.FLUID, Configs.Hotkeys.BEDROCK);
        groups.add("range", Configs.Core.WORK_RANGE, Configs.Core.ITERATOR_SHAPE,
                Configs.Core.CHECK_PLAYER_INTERACTION_RANGE);
        groups.add("performance", Configs.Core.WORK_PER_TICK, Configs.Core.LAG_CHECK, Configs.Core.LAG_CHECK_MAX);
        boolean multi = Configs.Core.WORK_MODE.getOptionListValue() == WorkingModeType.MULTI;
        Object mode = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        boolean print = isPrintingActive();
        boolean mine = multi ? Configs.Core.MINE.getBooleanValue() : mode == PrintModeType.MINE;
        boolean fill = multi ? Configs.Core.FILL.getBooleanValue() : mode == PrintModeType.FILL;
        boolean cover = multi ? Configs.Core.COVER.getBooleanValue() : mode == PrintModeType.COVER;
        boolean fluid = multi ? Configs.Core.FLUID.getBooleanValue() : mode == PrintModeType.FLUID;
        boolean bedrock = multi ? Configs.Hotkeys.BEDROCK.getBooleanValue() : mode == PrintModeType.BEDROCK;
        if (print) {
            printingCommon(groups);
            if (printingDetails) groups.add("printing_details", Configs.Print.OPTIONS);
        }
        if (mine) function(groups, WorkspacePage.MINE);
        if (fill) function(groups, WorkspacePage.FILL);
        if (cover) function(groups, WorkspacePage.COVER);
        if (fluid) function(groups, WorkspacePage.FLUID);
        if (bedrock) function(groups, WorkspacePage.BEDROCK);
    }

    private static void printingCommon(Sections groups) {
        groups.add("print_range", Configs.Print.PRINT_SELECTION_TYPE);
        groups.add("printing", Configs.Print.PRINT_SKIP, Configs.Print.PRINT_SKIP_LIST,
                Configs.Print.SAFELY_OBSERVER, Configs.Print.PRINT_ICE_FOR_WATER,
                Configs.Print.BREAK_WRONG_BLOCK, Configs.Print.BREAK_EXTRA_BLOCK,
                Configs.Print.REPAIR_RAIL_SHAPE, Configs.Print.PRINT_RESERVE_ITEMS,
                Configs.Print.PRINT_RESERVE_ITEM_COUNT);
        groups.add("placement", Configs.Placement.OPTIONS);
    }

    private static void function(Sections groups, WorkspacePage page) {
        switch (page) {
            case MINE -> {
                groups.add("mine_range", Configs.Mine.MINE_SELECTION_TYPE);
                groups.add("mining", Configs.Mine.OPTIONS);
                groups.add("breaking", Configs.Break.OPTIONS);
            }
            case FILL -> {
                groups.add("filling", Configs.Fill.OPTIONS);
                groups.add("placement", Configs.Placement.OPTIONS);
            }
            case COVER -> {
                groups.add("covering", Configs.Cover.OPTIONS);
                groups.add("placement", Configs.Placement.OPTIONS);
            }
            case FLUID -> {
                groups.add("fluids", Configs.Fluid.OPTIONS);
                groups.add("placement", Configs.Placement.OPTIONS);
            }
            case BEDROCK -> {
                groups.add("state", Configs.Hotkeys.BEDROCK);
                groups.add("bedrock", Configs.Bedrock.OPTIONS);
            }
            default -> throw new IllegalArgumentException("Not a complete workbench function: " + page);
        }
    }

    private static final class Sections {
        private final List<Section> sections = new ArrayList<>();
        private final Set<IConfigBase> added = new LinkedHashSet<>();

        void add(String key, IConfigBase... configs) {
            this.add(key, List.of(configs));
        }

        void add(String key, Collection<? extends IConfigBase> configs) {
            List<IConfigBase> visible = configs.stream().filter(ConfigMetadata::isVisible)
                    .filter(this.added::add).map(config -> (IConfigBase) config).toList();
            if (!visible.isEmpty()) this.sections.add(new Section(key, visible));
        }
    }
}
