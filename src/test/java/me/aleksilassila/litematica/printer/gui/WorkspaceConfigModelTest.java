package me.aleksilassila.litematica.printer.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import me.aleksilassila.litematica.printer.config.ConfigMetadata;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceConfigModelTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void miningWorkbenchUsesTheExistingRangeAndSharedBreakSettings() {
        var previousMode = Configs.Core.WORK_MODE.getOptionListValue();
        var previousType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.MINE);
            List<IConfigBase> configs = configs(WorkspacePage.WORKBENCH);
            assertTrue(configs.contains(Configs.Mine.MINE_SELECTION_TYPE));
            assertTrue(configs.contains(Configs.Break.BREAK_BLOCKS_PER_TICK));
            assertFalse(configs.contains(Configs.Print.PRINT_SELECTION_TYPE));
            assertFalse(configs.contains(Configs.Core.PRINT));
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(previousMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(previousType);
        }
    }

    @Test void multiModeWorkbenchKeepsEveryExistingFeatureSwitchIncludingBedrock() {
        var previousMode = Configs.Core.WORK_MODE.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.MULTI);
            List<IConfigBase> configs = configs(WorkspacePage.WORKBENCH);
            assertTrue(configs.containsAll(List.of(Configs.Core.PRINT, Configs.Core.MINE,
                    Configs.Core.FILL, Configs.Core.COVER, Configs.Core.FLUID, Configs.Hotkeys.BEDROCK)));
            assertFalse(configs.contains(Configs.Core.WORK_MODE_TYPE));
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(previousMode);
        }
    }

    @Test void functionPagesEditTheSamePlacementConfigAndNeverMutateModes() {
        var mode = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        boolean running = Configs.Core.WORK_SWITCH.getBooleanValue();
        for (WorkspacePage page : List.of(WorkspacePage.PRINT, WorkspacePage.FILL,
                WorkspacePage.COVER, WorkspacePage.FLUID)) {
            IConfigBase count = configs(page).stream()
                    .filter(c -> c == Configs.Placement.PLACE_BLOCKS_PER_TICK).findFirst().orElseThrow();
            assertSame(Configs.Placement.PLACE_BLOCKS_PER_TICK, count);
        }
        for (WorkspacePage page : WorkspacePage.values()) configs(page);
        assertSame(mode, Configs.Core.WORK_MODE_TYPE.getOptionListValue());
        assertEquals(running, Configs.Core.WORK_SWITCH.getBooleanValue());
    }

    @Test void printingDetailsAlwaysExposeEveryVisiblePrintOption() {
        assertTrue(configs(WorkspacePage.PRINT).containsAll(
                Configs.Print.OPTIONS.stream().filter(ConfigMetadata::isVisible).toList()));
        assertTrue(WorkspaceConfigModel.allVisibleConfigs().contains(Configs.Print.BREAK_WRONG_STATE_BLOCK),
                "Global search must still include every detail option");
    }

    @Test void printingWorkbenchIncludesRequestedCommonOptionsAndPerformanceControls() {
        var previousMode = Configs.Core.WORK_MODE.getOptionListValue();
        var previousType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        boolean previousLagCheck = Configs.Core.LAG_CHECK.getBooleanValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
            Configs.Core.LAG_CHECK.setBooleanValue(true);
            List<IConfigBase> configs = configs(WorkspacePage.WORKBENCH);
            assertTrue(configs.containsAll(List.of(Configs.Core.WORK_PER_TICK, Configs.Core.LAG_CHECK,
                    Configs.Core.LAG_CHECK_MAX, Configs.Core.CHECK_PLAYER_INTERACTION_RANGE,
                    Configs.Print.PRINT_SKIP, Configs.Print.PRINT_SKIP_LIST, Configs.Print.SAFELY_OBSERVER,
                    Configs.Print.PRINT_ICE_FOR_WATER)));
            assertFalse(configs.contains(Configs.Print.BREAK_WRONG_STATE_BLOCK),
                    "Printing's workbench stays curated; its detail page contains the remainder");
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(previousMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(previousType);
            Configs.Core.LAG_CHECK.setBooleanValue(previousLagCheck);
        }
    }

    @Test void nonPrintingModesExposeTheirFullFunctionPageOnTheWorkbench() {
        var previousMode = Configs.Core.WORK_MODE.getOptionListValue();
        var previousType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            var modes = List.of(PrintModeType.MINE, PrintModeType.FILL, PrintModeType.COVER,
                    PrintModeType.FLUID, PrintModeType.BEDROCK);
            var pages = List.of(WorkspacePage.MINE, WorkspacePage.FILL, WorkspacePage.COVER,
                    WorkspacePage.FLUID, WorkspacePage.BEDROCK);
            for (int i = 0; i < modes.size(); i++) {
                Configs.Core.WORK_MODE_TYPE.setOptionListValue(modes.get(i));
                assertTrue(configs(WorkspacePage.WORKBENCH).containsAll(configs(pages.get(i))),
                        "Workbench must expose complete settings for " + modes.get(i));
            }
            assertTrue(configs(WorkspacePage.MINE).contains(Configs.Break.BREAK_USE_DELAYED_DESTROY));
            assertTrue(configs(WorkspacePage.MINE).contains(Configs.Break.BREAK_PROGRESS_THRESHOLD));
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(previousMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(previousType);
        }
    }

    @Test void miningExposesGlobalRestrictionsThatCanBlockMining() {
        var oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        var oldType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        var oldLimiter = Configs.Break.BREAK_LIMITER.getOptionListValue();
        var oldLimit = Configs.Break.BREAK_LIMIT.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.MINE);
            Configs.Break.BREAK_LIMITER.setOptionListValue(ExcavateListMode.CUSTOM);
            Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.BLACKLIST);
            for (WorkspacePage page : List.of(WorkspacePage.WORKBENCH, WorkspacePage.MINE)) {
                assertTrue(configs(page).containsAll(List.of(Configs.Break.BREAK_LIMITER,
                        Configs.Break.BREAK_LIMIT, Configs.Break.BREAK_BLACKLIST)));
            }
            Configs.Break.BREAK_LIMIT.setOptionListValue(UsageRestriction.ListType.WHITELIST);
            for (WorkspacePage page : List.of(WorkspacePage.WORKBENCH, WorkspacePage.MINE)) {
                assertTrue(configs(page).contains(Configs.Break.BREAK_WHITELIST));
                assertFalse(configs(page).contains(Configs.Break.BREAK_BLACKLIST));
            }
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(oldType);
            Configs.Break.BREAK_LIMITER.setOptionListValue(oldLimiter);
            Configs.Break.BREAK_LIMIT.setOptionListValue(oldLimit);
        }
    }

    @Test void onlyWorkbenchPrintingHasOptionalExtraDetails() {
        var oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        var oldType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
            assertFalse(configs(WorkspacePage.WORKBENCH).contains(Configs.Print.BREAK_WRONG_STATE_BLOCK));
            var expanded = WorkspaceConfigModel.sections(WorkspacePage.WORKBENCH, true).stream()
                    .flatMap(section -> section.configs().stream()).toList();
            assertTrue(expanded.contains(Configs.Print.BREAK_WRONG_STATE_BLOCK));
            assertEquals(WorkspaceConfigModel.sections(WorkspacePage.PRINT, false),
                    WorkspaceConfigModel.sections(WorkspacePage.PRINT, true));
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(oldType);
        }
    }

    @Test void everyVisibleExistingConfigIsReachableAndSearchHasNoDuplicates() {
        Set<IConfigBase> reachable = new LinkedHashSet<>();
        Arrays.stream(WorkspacePage.values()).forEach(page -> reachable.addAll(configs(page)));
        List<IConfigBase> visible = Configs.OPTIONS.stream().filter(ConfigMetadata::isVisible).toList();
        assertTrue(reachable.containsAll(visible),
                () -> "Missing configs: " + visible.stream().filter(c -> !reachable.contains(c)).map(IConfigBase::getName).toList());
        List<IConfigBase> search = WorkspaceConfigModel.allVisibleConfigs();
        assertEquals(new LinkedHashSet<>(visible), new LinkedHashSet<>(search));
        assertEquals(new LinkedHashSet<>(search).size(), search.size());
    }

    private static List<IConfigBase> configs(WorkspacePage page) {
        return WorkspaceConfigModel.sections(page).stream()
                .flatMap(section -> section.configs().stream()).toList();
    }
}
