package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Old RTT settings cannot return through a saved config or reset normal work settings. */
class RttConfigRemovalTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void oldRttSettingsAreIgnoredAndDisappearWhenTheConfigIsSaved() {
        List<IConfigBase> changedOptions = List.of(
                Configs.Placement.PLACE_INTERVAL,
                Configs.Break.BREAK_INTERVAL,
                Configs.Bedrock.BEDROCK_INTERVAL,
                Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK,
                Configs.Bedrock.BEDROCK_ALLOW_SIDE,
                Configs.Break.BREAK_USE_DELAYED_DESTROY
        );
        JsonObject original = new JsonObject();
        ConfigUtils.writeConfigBase(original, Reference.MOD_ID, changedOptions);
        try {
            JsonObject values = new JsonObject();
            values.addProperty("placeRttAdaptiveInterval", true);
            values.addProperty("placeRttSafetyPercent", 300);
            values.addProperty("bedrockMultiplayerAdaptive", true);
            values.addProperty("placeInterval", 9);
            values.addProperty("breakInterval", 3);
            values.addProperty("bedrockStartInterval", 7);
            values.addProperty("bedrockBlocksPerTick", 5);
            values.addProperty("bedrockAllowSide", true);
            values.addProperty("breakUseDelayedDestroy", true);
            JsonObject legacy = new JsonObject();
            legacy.add(Reference.MOD_ID, values);

            ConfigUtils.readConfigBase(legacy, Reference.MOD_ID, Configs.OPTIONS);
            JsonObject saved = new JsonObject();
            ConfigUtils.writeConfigBase(saved, Reference.MOD_ID, Configs.OPTIONS);
            JsonObject savedValues = saved.getAsJsonObject(Reference.MOD_ID);

            assertFalse(savedValues.has("placeRttAdaptiveInterval"));
            assertFalse(savedValues.has("placeRttSafetyPercent"));
            assertFalse(savedValues.has("bedrockMultiplayerAdaptive"));
            assertEquals(9, savedValues.get("placeInterval").getAsInt());
            assertEquals(3, savedValues.get("breakInterval").getAsInt());
            assertEquals(7, savedValues.get("bedrockStartInterval").getAsInt());
            assertEquals(5, savedValues.get("bedrockBlocksPerTick").getAsInt());
            assertTrue(savedValues.get("bedrockAllowSide").getAsBoolean());
            assertTrue(savedValues.get("breakUseDelayedDestroy").getAsBoolean(),
                    "Actual delayed mining confirmation is independent of RTT pacing");
        } finally {
            // Do not reload unrelated global options or fire their lifecycle callbacks.
            ConfigUtils.readConfigBase(original, Reference.MOD_ID, changedOptions);
        }
    }
}
