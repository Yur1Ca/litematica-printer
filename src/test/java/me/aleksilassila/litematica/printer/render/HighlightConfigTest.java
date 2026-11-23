package me.aleksilassila.litematica.printer.render;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightStyleType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HighlightConfigTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void originalColorsAndStyleRemainConfigurable() {
        var before = new JsonObject();
        ConfigUtils.writeConfigBase(before, Reference.MOD_ID, Configs.OPTIONS);
        try {
            assertEquals(0xFF00FFFF, Configs.Highlight.NEXT.getDefaultIntegerValue());
            assertEquals(0x80FF0000, Configs.Highlight.BREAK.getDefaultIntegerValue());
            assertEquals(HighlightStyleType.BOTH, Configs.Highlight.STYLE.getDefaultOptionListValue());

            var saved = new JsonObject();
            var values = new JsonObject();
            values.addProperty("highlightColorNext", "#AA123456");
            values.addProperty("highlightColorBreak", "#88765432");
            values.addProperty("highlightStyle", HighlightStyleType.OUTLINE.getStringValue());
            saved.add(Reference.MOD_ID, values);
            saved.addProperty("configSchemaVersion", 3);
            Configs.readOptions(saved);
            assertEquals(0xAA123456, Configs.Highlight.NEXT.getIntegerValue());
            assertEquals(0x88765432, Configs.Highlight.BREAK.getIntegerValue());
            assertEquals(HighlightStyleType.OUTLINE, Configs.Highlight.STYLE.getOptionListValue());
        } finally {
            ConfigUtils.readConfigBase(before, Reference.MOD_ID, Configs.OPTIONS);
        }
    }

    @ParameterizedTest
    @CsvSource({"3,false,true,false", "3,true,true,true", "3,true,false,false", "3,true,,true",
            "3,,,false", "4,false,true,true", "4,true,false,false"})
    void upgradePreservesVisibleLayersAndNewProfilesCanSavePreviewAlone(
            int schema, Boolean operations, Boolean preview, boolean expectedPreview) {
        var original = new JsonObject();
        ConfigUtils.writeConfigBase(original, Reference.MOD_ID, Configs.OPTIONS);
        try {
            var values = new JsonObject();
            if (operations != null) values.addProperty("highlightEnabled", operations);
            if (preview != null) values.addProperty("highlightNextEnabled", preview);
            var root = new JsonObject();
            root.addProperty("configSchemaVersion", schema);
            root.add(Reference.MOD_ID, values);
            assertFalse(Configs.Highlight.NEXT_ENABLED.getDefaultBooleanValue());
            Configs.Highlight.ENABLED.setBooleanValue(false);
            Configs.Highlight.NEXT_ENABLED.setBooleanValue(false);
            Configs.readOptions(root);
            assertEquals(Boolean.TRUE.equals(operations), Configs.Highlight.ENABLED.getBooleanValue());
            assertEquals(expectedPreview, Configs.Highlight.NEXT_ENABLED.getBooleanValue());
        } finally {
            ConfigUtils.readConfigBase(original, Reference.MOD_ID, Configs.OPTIONS);
        }
    }
}
