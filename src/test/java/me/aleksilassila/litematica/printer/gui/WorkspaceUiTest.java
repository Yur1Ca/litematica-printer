//#if MC == 260100
package me.aleksilassila.litematica.printer.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ConfigButtonOptionList;
import fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper;
import fi.dy.masa.malilib.render.GuiContext;
import me.aleksilassila.litematica.printer.config.ConfigMetadata;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.locale.Language;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceUiTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static class HeadlessUi extends ConfigUi {
        int saves;
        HeadlessUi() { super(null); }
        @Override protected void buildConfigSwitcher() { }
        @Override protected void onSettingsChanged() { this.saves++; }
        WidgetListConfigOptions list() { return this.getListWidget(); }
    }

    static class RenderOnlyUi extends HeadlessUi {
        int buttonCountDuringDraw;
        @Override public List<ConfigOptionWrapper> getConfigs() { return List.of(); }
        @Override protected WidgetListConfigOptions createListWidget(int x, int y) {
            return new WidgetListConfigOptions(x, y, this.getBrowserWidth(), this.getBrowserHeight(),
                    this.getConfigWidth(), 0, false, this) {
                @Override public void drawContents(GuiContext context, int mouseX, int mouseY, float delta) { }
            };
        }

        void drawFrame(RecordingContext context) {
            // GuiBase.extractRenderState's order, without its Fabric access-widened context conversion.
            this.drawScreenBackground(context, -1, -1);
            this.drawTitle(context, -1, -1, 0);
            this.drawWidgets(context, -1, -1);
            this.drawButtons(context, -1, -1, 0);
            this.drawContents(context, -1, -1, 0);
        }

        @Override protected void drawButtons(GuiContext context, int mouseX, int mouseY, float delta) {
            try {
                @SuppressWarnings("unchecked")
                var buttons = (List<fi.dy.masa.malilib.gui.button.ButtonBase>) field(this, "buttons");
                this.buttonCountDuringDraw = buttons.size();
                for (var button : buttons) ((ButtonGeneric) button).setRenderDefaultBackground(false);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            super.drawButtons(context, mouseX, mouseY, delta);
        }
    }

    @Test void changedModesRefreshContextualControlsBeforeTheButtonRenderPhase() throws Exception {
        var oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        var oldType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(me.aleksilassila.litematica.printer.enums.WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(me.aleksilassila.litematica.printer.enums.PrintModeType.PRINTER);
            withClient(unused -> {
                RenderOnlyUi ui = new RenderOnlyUi();
                ui.width = 1000;
                ui.height = 800;
                ui.initGui();
                Configs.Core.WORK_MODE_TYPE.setOptionListValue(me.aleksilassila.litematica.printer.enums.PrintModeType.MINE);
                ui.drawFrame(RecordingContext.create());
                assertEquals(WorkspacePage.values().length + 2, ui.buttonCountDuringDraw,
                        "The printing-only control must disappear before extracting buttons for a mining frame");
            });
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(oldType);
        }
    }

    @ParameterizedTest @CsvSource({"400,240", "550,350", "1000,800"})
    void sidebarBackgroundNeverPaintsOverNavigationInTheNativeScreenRenderOrder(int width, int height) throws Exception {
        withClient(unused -> {
            RenderOnlyUi ui = new RenderOnlyUi();
            ui.width = width;
            ui.height = height;
            ui.initGui();
            ui.selectPage(WorkspacePage.HOTKEYS);
            @SuppressWarnings("unchecked")
            List<fi.dy.masa.malilib.gui.button.ButtonBase> buttons =
                    (List<fi.dy.masa.malilib.gui.button.ButtonBase>) field(ui, "buttons");
            for (int i = 0; i < buttons.size(); i++) {
                ButtonGeneric button = (ButtonGeneric) buttons.get(i);
                button.setRenderDefaultBackground(false);
                if (i < WorkspacePage.values().length) button.setDisplayString("nav" + i);
            }
            RecordingContext context = RecordingContext.create();
            ui.drawFrame(context);
            var selected = buttons.get(10);
            assertTrue(context.paints.stream().anyMatch(paint -> new ScreenRectangle(selected.getX(),
                    selected.getY(), 2, selected.getHeight()).equals(paint.fill())),
                    "The selection marker must track the compact button's position and height");
            for (int i = 0; i < WorkspacePage.values().length; i++) {
                String label = "nav" + i;
                int textIndex = -1;
                for (int j = 0; j < context.paints.size(); j++) {
                    if (label.equals(context.paints.get(j).text())) textIndex = j;
                }
                assertTrue(textIndex >= 0, "Every navigation button must draw its label");
                var button = buttons.get(i);
                int x = button.getX() + button.getWidth() / 2;
                int y = button.getY() + button.getHeight() / 2;
                for (int j = textIndex + 1; j < context.paints.size(); j++) {
                    ScreenRectangle fill = context.paints.get(j).fill();
                    if (fill != null) {
                        assertFalse(x >= fill.left() && x < fill.right() && y >= fill.top() && y < fill.bottom(),
                                "A later background fill covered navigation label " + label);
                    }
                }
            }
        });
    }

    @Test void navigationNeverStartsOrChangesTheActiveModeAndFullListKeepsEveryOption() throws Exception {
        withClient(ui -> {
            Object mode = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
            boolean running = Configs.Core.WORK_SWITCH.getBooleanValue();
            for (WorkspacePage page : WorkspacePage.values()) ui.selectPage(page);
            Set<IConfigBase> complete = new LinkedHashSet<>();
            for (ConfigUi.Tab tab : ConfigUi.Tab.values()) {
                ui.selectTab(tab);
                ui.getConfigs().stream().map(GuiConfigsBase.ConfigOptionWrapper::getConfig)
                        .filter(java.util.Objects::nonNull).forEach(complete::add);
            }
            assertEquals(new LinkedHashSet<>(Configs.OPTIONS.stream().filter(ConfigMetadata::isVisible).toList()), complete);
            assertSame(mode, Configs.Core.WORK_MODE_TYPE.getOptionListValue());
            assertEquals(running, Configs.Core.WORK_SWITCH.getBooleanValue());
        });
    }

    @ParameterizedTest @CsvSource({"550,350", "480,270", "427,240", "400,240"})
    void shortWindowsKeepEverySidebarPageAboveTheFooter(int width, int height) throws Exception {
        withClient(ui -> {
            ui.width = width;
            ui.height = height;
            ui.initGui();
            @SuppressWarnings("unchecked")
            var buttons = (List<fi.dy.masa.malilib.gui.button.ButtonBase>) field(ui, "buttons");
            var navigation = buttons.stream().filter(button -> button.getX() == 12).toList();
            assertEquals(11, navigation.size(), "Shrinking a normal window must retain every sidebar page");
            int previousBottom = 42;
            for (var button : navigation) {
                assertTrue(button.getY() >= previousBottom, "Sidebar hitboxes must not overlap");
                assertTrue(button.getHeight() >= 14, "Compact navigation must leave room for readable text");
                assertTrue(button.getY() + button.getHeight() <= height - 32,
                        "Every page must remain accessible above the footer");
                assertTrue(button.getX() + button.getWidth() < ui.list().getSearchBarWidget().getX());
                previousBottom = button.getY() + button.getHeight();
            }
            assertFalse(((List<?>) field(ui, "widgets")).stream().anyMatch(
                    fi.dy.masa.malilib.gui.widgets.WidgetDropDownList.class::isInstance),
                    "Low height alone must not replace the sidebar with a page dropdown");
        });
    }

    @Test void detailsStayExpandedWithoutAdvancedOrHeaderWorkSwitchButtons() throws Exception {
        withClient(ui -> {
            ui.selectPage(WorkspacePage.PRINT);
            List<IConfigBase> configs = ui.getConfigs().stream().map(GuiConfigsBase.ConfigOptionWrapper::getConfig)
                    .filter(java.util.Objects::nonNull).toList();
            assertTrue(configs.containsAll(Configs.Print.OPTIONS.stream().filter(ConfigMetadata::isVisible).toList()));
            @SuppressWarnings("unchecked")
            var buttons = (List<fi.dy.masa.malilib.gui.button.ButtonBase>) field(ui, "buttons");
            assertTrue(buttons.stream().allMatch(button -> button.getY() >= 42),
                    "The header must have no work-switch button");
            assertEquals(2, buttons.stream().filter(button -> button.getY() == ui.height - 28).count(),
                    "Only complete settings and Done belong in the footer");
            ui.selectPage(WorkspacePage.COMMON);
            assertTrue(ui.getConfigs().stream().anyMatch(wrapper -> wrapper.getConfig() == Configs.Core.WORK_SWITCH),
                    "Removing duplicate chrome must not remove the underlying work switch");
        });
    }

    @Test void changingPagesAndRefreshingRetainPendingNumericEdits() throws Exception {
        int old = Configs.Core.WORK_RANGE.getIntegerValue();
        try {
            withClient(ui -> {
                edit(ui, Configs.Core.WORK_RANGE, "17");
                ui.selectPage(WorkspacePage.MINE);
                assertEquals(17, Configs.Core.WORK_RANGE.getIntegerValue());
                assertTrue(ui.saves > 0);
                ui.selectPage(WorkspacePage.WORKBENCH);
                edit(ui, Configs.Core.WORK_RANGE, "19");
                ui.initGui();
                assertEquals(19, Configs.Core.WORK_RANGE.getIntegerValue());
            });
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(old);
        }
    }

    @Test void rebuildingVisibleRowsDoesNotAccumulateDetachedHotkeyListeners() throws Exception {
        withClient(ui -> {
            ui.selectPage(WorkspacePage.HOTKEYS);
            int initial = ((List<?>) field(ui, "hotkeyChangeListeners")).size();
            assertTrue(initial > 0);
            for (int i = 0; i < 10; i++) ui.list().refreshEntries();
            assertEquals(initial, ((List<?>) field(ui, "hotkeyChangeListeners")).size(),
                    "Scrolling/searching must replace listeners for discarded controls");
        });
    }

    @Test void workbenchPrintingExpansionKeepsPendingEditsAndNeverHidesDetailPageOptions() throws Exception {
        var oldMode = Configs.Core.WORK_MODE.getOptionListValue();
        var oldType = Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        int oldRange = Configs.Core.WORK_RANGE.getIntegerValue();
        try {
            Configs.Core.WORK_MODE.setOptionListValue(me.aleksilassila.litematica.printer.enums.WorkingModeType.SINGLE);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(me.aleksilassila.litematica.printer.enums.PrintModeType.PRINTER);
            withClient(ui -> {
                assertFalse(ui.getConfigs().stream().anyMatch(w -> w.getConfig() == Configs.Print.BREAK_WRONG_STATE_BLOCK));
                edit(ui, Configs.Core.WORK_RANGE, "29");
                ui.togglePrintingDetails();
                assertEquals(29, Configs.Core.WORK_RANGE.getIntegerValue());
                assertTrue(ui.getConfigs().stream().anyMatch(w -> w.getConfig() == Configs.Print.BREAK_WRONG_STATE_BLOCK));
                ui.togglePrintingDetails();
                assertFalse(ui.getConfigs().stream().anyMatch(w -> w.getConfig() == Configs.Print.BREAK_WRONG_STATE_BLOCK));
                ui.selectPage(WorkspacePage.PRINT);
                assertTrue(ui.getConfigs().stream().anyMatch(w -> w.getConfig() == Configs.Print.BREAK_WRONG_STATE_BLOCK));
            });
        } finally {
            Configs.Core.WORK_MODE.setOptionListValue(oldMode);
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(oldType);
            Configs.Core.WORK_RANGE.setIntegerValue(oldRange);
        }
    }

    @Test void listRebuildAndResizeRetainPendingNumericValues() throws Exception {
        int previous = Configs.Core.WORK_RANGE.getIntegerValue();
        try {
            withClient(ui -> {
                edit(ui, Configs.Core.WORK_RANGE, "23");
                ui.list().refreshEntries();
                assertEquals(23, Configs.Core.WORK_RANGE.getIntegerValue());
                edit(ui, Configs.Core.WORK_RANGE, "25");
                ui.width = 640;
                ui.height = 360;
                ui.initGui();
                assertEquals(25, Configs.Core.WORK_RANGE.getIntegerValue());
                ui.width = 320;
                ui.height = 240;
                ui.initGui();
                assertEquals(25, Configs.Core.WORK_RANGE.getIntegerValue());
                assertTrue(((List<?>) field(ui, "widgets")).stream().anyMatch(
                        fi.dy.masa.malilib.gui.widgets.WidgetDropDownList.class::isInstance),
                        "A narrow screen must retain its native page picker");
                @SuppressWarnings("unchecked")
                var buttons = (List<fi.dy.masa.malilib.gui.button.ButtonBase>) field(ui, "buttons");
                assertTrue(buttons.stream().allMatch(button -> button.getX() >= 0
                        && button.getX() + button.getWidth() <= ui.width
                        && button.getY() + button.getHeight() <= ui.height));
            });
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(previous);
        }
    }

    @Test void globalSearchFindsAdvancedSettingsOutsideTheSelectedPageWithoutDuplicateRows() throws Exception {
        withClient(ui -> {
            ui.selectPage(WorkspacePage.MINE);
            WorkspaceConfigList list = (WorkspaceConfigList) ui.list();
            list.setSearchText("printBreakWrongState");
            List<IConfigBase> matches = list.getCurrentEntries().stream()
                    .map(GuiConfigsBase.ConfigOptionWrapper::getConfig).filter(java.util.Objects::nonNull).toList();
            assertEquals(List.of(Configs.Print.BREAK_WRONG_STATE_BLOCK), matches);
            list.setSearchText("placeCooldown");
            assertEquals(1, list.getCurrentEntries().stream()
                    .filter(w -> w.getConfig() == Configs.Placement.PLACE_COOLDOWN).count());
            list.setSearchText("");
            assertTrue(list.getCurrentEntries().stream().anyMatch(w -> w.getConfig() == Configs.Mine.MINE_SELECTION_TYPE));
        });
    }

    @Test void fullListRefreshesPrerequisiteVisibilityBeforeRendering() throws Exception {
        var previous = Configs.Cover.COVER_BLOCK_MODE.getOptionListValue();
        try {
            Configs.Cover.COVER_BLOCK_MODE.setOptionListValue(
                    me.aleksilassila.litematica.printer.enums.FillBlockModeType.BLOCKLIST);
            withClient(ui -> {
                ui.selectTab(ConfigUi.Tab.COVER);
                assertTrue(ui.list().getCurrentEntries().stream().anyMatch(
                        w -> w.getConfig() == Configs.Cover.COVER_BLOCK_LIST));
                Configs.Cover.COVER_BLOCK_MODE.setOptionListValue(
                        me.aleksilassila.litematica.printer.enums.FillBlockModeType.HANDHELD);
                ui.drawScreenBackground(RecordingContext.create(), -1, -1);
                assertFalse(ui.list().getCurrentEntries().stream().anyMatch(
                        w -> w.getConfig() == Configs.Cover.COVER_BLOCK_LIST),
                        "Complete settings must also refresh fields whose prerequisites changed");
            });
        } finally {
            Configs.Cover.COVER_BLOCK_MODE.setOptionListValue(previous);
        }
    }

    @ParameterizedTest @CsvSource({"288", "304"})
    void spanishResetButtonsStayInsideTheRowAndAwayFromTheScrollbar(int listWidth) throws Exception {
        withResetLabel("REINICIAR", ui -> {
            for (IConfigBase config : List.of(Configs.Print.PRINT_SELECTION_TYPE, Configs.Core.WORK_SWITCH)) {
                ui.selectPage(config == Configs.Core.WORK_SWITCH ? WorkspacePage.COMMON : WorkspacePage.PRINT);
                WorkspaceConfigList list = new WorkspaceConfigList(104, 78, listWidth, 130, 148, ui, true);
                list.initGui();
                WidgetConfigOption row = row(list, config);
                for (WidgetBase widget : children(row)) {
                    if (widget instanceof fi.dy.masa.malilib.gui.button.ButtonBase) {
                        assertTrue(widget.getWidth() > 0 && widget.getX() >= row.getX()
                                        && widget.getX() + widget.getWidth() <= row.getX() + row.getWidth(),
                                "Native controls and translated reset buttons must remain within the row hitbox");
                    }
                }
            }
        });
    }

    @Test void longNativeButtonTextIsClippedToTheButtonAndRestoresTheParentClip() throws Exception {
        withResetLabel("RESET", ui -> {
            ui.selectPage(WorkspacePage.PRINT);
            WorkspaceConfigList list = new WorkspaceConfigList(8, 94, 304, 114, 148, ui, true);
            list.initGui();
            WidgetConfigOption row = row(list, Configs.Print.PRINT_SELECTION_TYPE);
            ConfigButtonOptionList option = children(row).stream().filter(ConfigButtonOptionList.class::isInstance)
                    .map(ConfigButtonOptionList.class::cast).findFirst().orElseThrow();
            String fullValue = "Projection Selection (Above Player)";
            option.setDisplayString(fullValue);
            for (WidgetBase widget : children(row)) {
                if (widget instanceof ButtonGeneric button) button.setRenderDefaultBackground(false);
            }
            RecordingContext context = RecordingContext.create();
            ScreenRectangle parentClip = new ScreenRectangle(0, 0, 320, 240);
            context.pushScissor(parentClip);
            var renderWidgets = fi.dy.masa.malilib.gui.widgets.WidgetContainer.class
                    .getDeclaredMethod("drawSubWidgets", GuiContext.class, int.class, int.class);
            renderWidgets.setAccessible(true);
            renderWidgets.invoke(row, context, 0, 0);
            Draw text = context.draws.stream().filter(draw -> draw.text().equals(fullValue)).findFirst().orElseThrow();
            assertEquals(new ScreenRectangle(option.getX(), option.getY(), option.getWidth(), option.getHeight()), text.clip(),
                    "Native button text must not paint over its label, reset button or keybind settings");
            assertEquals(parentClip, context.peekLastScissor());
        });
    }

    private record Draw(String text, ScreenRectangle clip) { }
    private record Paint(String text, ScreenRectangle fill) { }

    private static class RecordingContext extends GuiContext {
        List<Draw> draws;
        List<Paint> paints;
        ArrayDeque<ScreenRectangle> clips;
        org.joml.Matrix3x2fStack matrix;
        private RecordingContext() { super(null, new GuiRenderState(), 0, 0); }
        static RecordingContext create() throws Exception {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var context = (RecordingContext) ((Unsafe) unsafeField.get(null)).allocateInstance(RecordingContext.class);
            context.draws = new ArrayList<>();
            context.paints = new ArrayList<>();
            context.clips = new ArrayDeque<>();
            context.matrix = new org.joml.Matrix3x2fStack(8);
            return context;
        }
        @Override public void pushScissor(ScreenRectangle rectangle) { this.clips.push(rectangle); }
        @Override public ScreenRectangle popScissor() { return this.clips.pop(); }
        @Override public ScreenRectangle peekLastScissor() { return this.clips.peek(); }
        @Override public void nextStratum() { }
        @Override public org.joml.Matrix3x2fStack pose() { return this.matrix; }
        @Override public void addSimpleElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState element) {
            this.paints.add(new Paint(null, element.bounds()));
        }
        @Override public void fill(int left, int top, int right, int bottom, int color) {
            this.paints.add(new Paint(null, new ScreenRectangle(left, top, right - left, bottom - top)));
        }
        @Override public void drawString(Font font, String text, int x, int y, int color) {
            this.draws.add(new Draw(text, this.peekLastScissor()));
            this.paints.add(new Paint(text, null));
        }
        @Override public void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
            this.draws.add(new Draw(text, this.peekLastScissor()));
            this.paints.add(new Paint(text, null));
        }
        @Override public void text(Font font, String text, int x, int y, int color) {
            this.draws.add(new Draw(text, this.peekLastScissor()));
            this.paints.add(new Paint(text, null));
        }
        @Override public void text(Font font, String text, int x, int y, int color, boolean shadow) {
            this.draws.add(new Draw(text, this.peekLastScissor()));
            this.paints.add(new Paint(text, null));
        }
    }

    private static void withResetLabel(String reset, UiAction action) throws Exception {
        var method = net.minecraft.client.resources.language.I18n.class.getDeclaredMethod("setLanguage", Language.class);
        method.setAccessible(true);
        Language previous = Language.getInstance();
        Language language = new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return key.equals("malilib.gui.button.reset.caps") ? reset : previous.getOrDefault(key, fallback);
            }
            @Override public boolean has(String key) { return key.equals("malilib.gui.button.reset.caps") || previous.has(key); }
            @Override public boolean isDefaultRightToLeft() { return previous.isDefaultRightToLeft(); }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return previous.getVisualOrder(text); }
        };
        Field languageField = net.minecraft.client.resources.language.I18n.class.getDeclaredField("language");
        languageField.setAccessible(true);
        Object previousClientLanguage = languageField.get(null);
        method.invoke(null, language);
        try { withClient(action); }
        finally { method.invoke(null, previousClientLanguage); }
    }

    @SuppressWarnings("unchecked")
    private static List<WidgetBase> children(WidgetConfigOption row) throws Exception {
        return (List<WidgetBase>) field(row, "subWidgets");
    }

    private static WidgetConfigOption row(HeadlessUi ui, IConfigBase config) throws Exception {
        return row(ui.list(), config);
    }

    private static WidgetConfigOption row(WidgetListConfigOptions list, IConfigBase config) throws Exception {
        @SuppressWarnings("unchecked")
        List<WidgetConfigOption> rows = (List<WidgetConfigOption>) field(list, "listWidgets");
        return rows.stream().filter(w -> w.getEntry().getConfig() == config).findFirst().orElseThrow();
    }

    private static void edit(HeadlessUi ui, IConfigBase config, String value) throws Exception {
        WidgetConfigOption row = row(ui, config);
        @SuppressWarnings("unchecked")
        TextFieldWrapper<GuiTextFieldGeneric> text = (TextFieldWrapper<GuiTextFieldGeneric>) field(row, "textField");
        text.textField().setValueWrapper(value);
    }

    private static Object field(Object object, String name) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void withClient(UiAction action) throws Exception {
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Minecraft client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var window = (com.mojang.blaze3d.platform.Window) unsafe.allocateInstance(com.mojang.blaze3d.platform.Window.class);
        for (String name : List.of("guiScaledWidth", "guiScaledHeight")) {
            Field dimension = com.mojang.blaze3d.platform.Window.class.getDeclaredField(name);
            dimension.setAccessible(true);
            dimension.setInt(window, name.equals("guiScaledWidth") ? 1000 : 800);
        }
        Field windowField = Minecraft.class.getDeclaredField("window");
        windowField.setAccessible(true);
        windowField.set(client, window);
        Field textInputField = Minecraft.class.getDeclaredField("textInputManager");
        textInputField.setAccessible(true);
        textInputField.set(client, unsafe.allocateInstance(HeadlessTextInputManager.class));
        Field font = Minecraft.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(client, new Font(null) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public int width(FormattedText text) { return width(text.getString()); }
            @Override public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
            }
        });
        instance.set(null, client);
        try {
            HeadlessUi ui = new HeadlessUi();
            ui.width = 1000;
            ui.height = 800;
            ui.initGui();
            action.run(ui);
        } finally {
            instance.set(null, previous);
        }
    }

    @FunctionalInterface private interface UiAction {
        void run(HeadlessUi ui) throws Exception;
    }

    private static final class HeadlessTextInputManager extends com.mojang.blaze3d.platform.TextInputManager {
        private HeadlessTextInputManager() { super(null); }
        @Override public void onTextInputFocusChange(boolean focused) { }
    }
}
//#endif
