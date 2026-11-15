package me.aleksilassila.litematica.printer.handler;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScanSchedulingConfigRemovalTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void legacyPerformanceControlsCollapseIntoOneKnobWithoutResettingActionSettings() {
        List<IConfigBase> changed = Configs.OPTIONS;
        JsonObject original = new JsonObject();
        ConfigUtils.writeConfigBase(original, Reference.MOD_ID, changed);
        try {
            JsonObject values = new JsonObject();
            values.addProperty("scanTimeBudgetMs", 10);
            values.addProperty("lazyEnterTicks", 40);
            values.addProperty("workRange", 128);
            values.addProperty("placeInterval", 7);
            values.addProperty("breakInterval", 3);
            values.addProperty("bedrockStartInterval", 9);
            JsonObject legacy = new JsonObject();
            legacy.add(Reference.MOD_ID, values);
            assertTrue(Configs.readOptions(legacy));
            JsonObject saved = new JsonObject();
            ConfigUtils.writeConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
            JsonObject actual = saved.getAsJsonObject(Reference.MOD_ID);

            assertTrue(actual.has("workPerTick"));
            assertEquals(40960, actual.get("workPerTick").getAsInt());
            assertFalse(actual.has("scanTimeBudgetMs"));
            assertFalse(actual.has("iterationTimeBudgetMs"));
            assertFalse(actual.has("scanWorkLimitPerTick"));
            assertFalse(actual.has("iterationCandidatesPerTick"));
            assertFalse(actual.has("scanCacheSizeMiB"));
            assertFalse(actual.has("lazyEnterTicks"));
            assertEquals(128, actual.get("workRange").getAsInt());
            assertEquals(7, actual.get("placeInterval").getAsInt());
            assertEquals(3, actual.get("breakInterval").getAsInt());
            assertEquals(9, actual.get("bedrockStartInterval").getAsInt());
        } finally {
            ConfigUtils.readConfigBase(original, Reference.MOD_ID, changed);
        }
    }

    @Test void onlyOnePerformanceControlIsVisibleWithSafeDefaultsAndRange() {
        assertIntegerControl("workPerTick", 2048, 256, 131072);
        for (String removed : List.of("scanTimeBudgetMs", "iterationTimeBudgetMs", "scanWorkLimitPerTick",
                "iterationCandidatesPerTick", "scanCacheSizeMiB", "lazyEnterTicks")) {
            assertTrue(Configs.OPTIONS.stream().noneMatch(option -> option.getName().equals(removed)));
        }
    }

    @Test void explicitWorkloadWinsAndLegacyCountsMigrateBeforeLegacyTime() {
        int old = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try {
            JsonObject values = new JsonObject();
            values.addProperty("scanWorkLimitPerTick", 1024);
            values.addProperty("iterationCandidatesPerTick", 16384);
            values.addProperty("scanTimeBudgetMs", 10);
            JsonObject root = new JsonObject();
            root.add(Reference.MOD_ID, values);
            assertTrue(Configs.readOptions(root));
            assertEquals(16384, Configs.Core.WORK_PER_TICK.getIntegerValue());
            values.addProperty("workPerTick", 2048);
            values.addProperty("scanWorkLimitPerTick", 131072);
            assertTrue(Configs.readOptions(root));
            assertEquals(2048, Configs.Core.WORK_PER_TICK.getIntegerValue());
            assertFalse(Configs.readOptions(root), "Migrating twice must preserve the explicit workload");
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(old);
        }
    }

    @Test void obsoleteCacheOnlyAndMalformedLegacyValuesUseTheSafeDefault() {
        int old = Configs.Core.WORK_PER_TICK.getIntegerValue();
        try {
            JsonObject values = new JsonObject();
            values.addProperty("scanCacheSizeMiB", 128);
            values.addProperty("scanWorkLimitPerTick", -1);
            values.addProperty("iterationTimeBudgetMs", "invalid");
            JsonObject root = new JsonObject();
            root.add(Reference.MOD_ID, values);
            assertTrue(Configs.readOptions(root));
            assertEquals(2048, Configs.Core.WORK_PER_TICK.getIntegerValue());
            assertEquals(1, values.size());
        } finally {
            Configs.Core.WORK_PER_TICK.setIntegerValue(old);
        }
    }

    private static void assertIntegerControl(String name, int defaultValue, int minimum, int maximum) {
        ConfigInteger control = (ConfigInteger) Configs.Core.OPTIONS.stream()
                .filter(option -> option.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing visible performance control: " + name));
        int old = control.getIntegerValue();
        try {
            control.resetToDefault();
            assertEquals(defaultValue, control.getIntegerValue());
            control.setIntegerValue(Integer.MIN_VALUE);
            assertEquals(minimum, control.getIntegerValue());
            control.setIntegerValue(Integer.MAX_VALUE);
            assertEquals(maximum, control.getIntegerValue());
        } finally {
            control.setIntegerValue(old);
        }
    }
}
