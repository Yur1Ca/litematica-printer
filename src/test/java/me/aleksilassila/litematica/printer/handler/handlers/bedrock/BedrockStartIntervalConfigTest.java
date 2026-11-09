package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The old batch cadence cannot silently become a per-cycle rate limit on upgrade. */
class BedrockStartIntervalConfigTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aSavedLegacyBatchIntervalLeavesTheNewStartIntervalAutomatic() {
        int previous = Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue();
        try {
            Configs.Bedrock.BEDROCK_INTERVAL.resetToDefault();
            JsonObject root = configWith("bedrockInterval", 2);

            ConfigUtils.readConfigBase(root, Reference.MOD_ID, Configs.Bedrock.OPTIONS);

            assertEquals(0, Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue(),
                    "A legacy six-per-two-tick batch value must not throttle every new cycle to two ticks");
        } finally {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(previous);
        }
    }

    @Test void anExplicitNewStartIntervalWinsWhenAnOldKeyAlsoExists() {
        int previous = Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue();
        try {
            Configs.Bedrock.BEDROCK_INTERVAL.resetToDefault();
            JsonObject root = configWith("bedrockInterval", 2);
            root.getAsJsonObject(Reference.MOD_ID).addProperty("bedrockStartInterval", 7);

            ConfigUtils.readConfigBase(root, Reference.MOD_ID, Configs.Bedrock.OPTIONS);

            assertEquals(7, Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue());
        } finally {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(previous);
        }
    }

    @Test void savingWritesOnlyTheNewIntervalKeyAndPreservesTheCycleLimit() {
        int previousInterval = Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue();
        int previousLimit = Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue();
        try {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(5);
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(7);
            JsonObject root = new JsonObject();

            ConfigUtils.writeConfigBase(root, Reference.MOD_ID, Configs.Bedrock.OPTIONS);

            JsonObject values = root.getAsJsonObject(Reference.MOD_ID);
            assertFalse(values.has("bedrockInterval"), "Saving must retire the old batch-cadence key");
            assertEquals(5, values.get("bedrockStartInterval").getAsInt());
            assertEquals(7, values.get("bedrockBlocksPerTick").getAsInt(),
                    "Changing interval semantics does not reset the existing concurrency limit");
        } finally {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(previousInterval);
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(previousLimit);
        }
    }

    private static JsonObject configWith(String key, int value) {
        JsonObject values = new JsonObject();
        values.addProperty(key, value);
        JsonObject root = new JsonObject();
        root.add(Reference.MOD_ID, values);
        return root;
    }
}
