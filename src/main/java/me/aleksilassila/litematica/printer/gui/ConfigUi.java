package me.aleksilassila.litematica.printer.gui;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetDropDownList;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.ConfigMetadata;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

public class ConfigUi extends GuiConfigsBase {
    private static Tab tab = Tab.CORE;
    private WorkspacePage page = WorkspacePage.WORKBENCH;
    private boolean fullConfigList;
    private boolean printingDetails;
    private boolean initialized;
    private boolean rebuilding;
    private int visibilitySignature;
    private WorkspaceLayout layout;

    public ConfigUi(@Nullable Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, Reference.GUI_TITLE + "   " + I18n.FREE_NOTICE.getName().getString());
        this.setParent(parent);
    }

    public ConfigUi() {
        //#if MC > 260100
        //$$ this(Minecraft.getInstance().gui.screen());
        //#else
        this(Minecraft.getInstance().screen);
        //#endif
    }

    public static void refresh() {
        //#if MC > 260100
        //$$ if (Reference.MINECRAFT.gui.screen() instanceof ConfigUi gui) {
        //#else
        if (Reference.MINECRAFT.screen instanceof ConfigUi gui) {
        //#endif
            gui.initGui();
        }
    }

    @Override
    public void initGui() {
        if (this.rebuilding) return;
        this.rebuilding = true;
        try {
            this.flushPendingSettings();
            WorkspaceLayout nextLayout = this.fullConfigList ? this.fullListLayout() : WorkspaceLayout.workspace(this.width, this.height);
            if (!nextLayout.equals(this.layout)) {
                this.layout = nextLayout;
                this.setListPosition(nextLayout.listX(), nextLayout.listY());
                this.configWidth = nextLayout.configWidth();
                this.reCreateListWidget();
            }
            // Flush before clearing: both text edits and keybind listeners belong to the old controls.
            this.clearOptions();
            super.initGui();
            if (this.fullConfigList) this.buildFullListTabs();
            else this.buildNavigation();
            this.buildPrintingDetailsControl();
            this.buildFooter();
            this.initialized = true;
            this.visibilitySignature = this.currentVisibilitySignature();
        } finally {
            this.rebuilding = false;
        }
    }

    public void reset() {
        this.flushPendingSettings();
        reCreateListWidget();
        Objects.requireNonNull(getListWidget()).resetScrollbarPosition();
        initGui();
    }

    void selectPage(WorkspacePage page) {
        this.flushPendingSettings();
        this.page = page;
        this.fullConfigList = false;
        this.reset();
    }

    void selectTab(Tab selected) {
        this.flushPendingSettings();
        ConfigUi.tab = selected;
        this.fullConfigList = true;
        this.reset();
    }

    void togglePrintingDetails() {
        if (!this.hasPrintingDetailsControl()) return;
        this.flushPendingSettings();
        this.printingDetails = !this.printingDetails;
        this.reset();
    }

    private boolean hasPrintingDetailsControl() {
        return !this.fullConfigList && this.page == WorkspacePage.WORKBENCH && WorkspaceConfigModel.isPrintingActive();
    }

    private int printingDetailsControlWidth() {
        return Math.min(132, this.layout.listWidth() / 2);
    }

    private void buildPrintingDetailsControl() {
        if (!this.hasPrintingDetailsControl()) return;
        int width = this.printingDetailsControlWidth();
        int y = (this.layout.sidebarWidth() == 0 ? 66 : 46) - 4;
        String label = text(this.printingDetails ? "less_printing" : "more_printing");
        this.addButton(new ButtonGeneric(this.width - width - 8, y, width, 18,
                fitText(label, width - 8, this::getStringWidth), label),
                (button, mouseButton) -> this.togglePrintingDetails());
    }

    private void flushPendingSettings() {
        if (!this.initialized) return;
        this.savePendingSettings(this.getListWidget());
    }

    void prepareListRebuild(WidgetListConfigOptions list) {
        this.savePendingSettings(list);
        this.clearOptions();
    }

    private void savePendingSettings(WidgetListConfigOptions list) {
        if (list != null) {
            list.applyPendingModifications();
            if (list.wereConfigsModified()) {
                this.onSettingsChanged();
                list.clearConfigsModifiedFlag();
            }
        }
    }

    @Override
    protected void buildConfigSwitcher() {
        super.buildConfigSwitcher();
        if (this.modSwitchWidget != null) this.modSwitchWidget.setPosition(this.width - 155, 24);
    }

    @Override
    protected int getBrowserWidth() {
        return this.layout == null ? super.getBrowserWidth() : this.layout.listWidth();
    }

    @Override
    protected int getBrowserHeight() {
        return this.layout == null ? super.getBrowserHeight() : this.layout.listHeight();
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int x, int y) {
        return new WorkspaceConfigList(x, y, this.getBrowserWidth(), this.getBrowserHeight(),
                this.getConfigWidth(), this, !this.fullConfigList);
    }

    private void buildNavigation() {
        if (this.layout.sidebarWidth() == 0) {
            WidgetDropDownList<WorkspacePage> picker = new WidgetDropDownList<>(8, 42, this.width - 16, 18,
                    Math.min(200, this.height - 80), 8, List.of(WorkspacePage.values()), WorkspacePage::title) {
                @Override protected void setSelectedEntry(int index) {
                    super.setSelectedEntry(index);
                    if (this.selectedEntry != null) ConfigUi.this.selectPage(this.selectedEntry);
                }
            };
            picker.setSelectedEntry(this.page);
            this.addWidget(picker);
        } else {
            int y = 46;
            for (WorkspacePage entry : WorkspacePage.values()) {
                if (entry == WorkspacePage.PRINT || entry == WorkspacePage.COMMON) y += 9;
                int buttonWidth = this.layout.sidebarWidth() - 8;
                ButtonGeneric button = new ButtonGeneric(12, y, buttonWidth, 19,
                        fitText(entry.title(), buttonWidth - 8, this::getStringWidth), entry.description());
                button.setRenderDefaultBackground(false);
                this.addButton(button, (b, mouseButton) -> {
                    if (entry != this.page) this.selectPage(entry);
                });
                y += 21;
            }
        }
    }

    private void buildFooter() {
        int y = this.layout.footerY();
        int buttonWidth = Math.min(124, (this.width - 88) / 2);
        String entry = text(this.fullConfigList ? "workspace" : "full_list");
        this.addButton(new ButtonGeneric(8, y, buttonWidth, 20,
                fitText(entry, buttonWidth - 8, this::getStringWidth), entry), (button, mouseButton) -> {
            this.flushPendingSettings();
            this.fullConfigList = !this.fullConfigList;
            if (!this.fullConfigList) this.page = WorkspacePage.WORKBENCH;
            this.reset();
        });
        this.addButton(new ButtonGeneric(this.width - 60, y, 52, 20, text("done")),
                (button, mouseButton) -> this.closeGui(true));
    }

    private WorkspaceLayout fullListLayout() {
        int x = 8;
        int y = 44;
        for (Tab entry : Tab.values()) {
            int w = this.tabWidth(entry);
            if (x + w > this.width - 8 && x > 8) { x = 8; y += 22; }
            x += w + 2;
        }
        int listY = y + 24;
        int listWidth = this.width - 16;
        return new WorkspaceLayout(0, 8, listY, listWidth, Math.max(24, this.height - 32 - listY),
                this.height - 28, Math.max(148, Math.min(204, (listWidth - 64) / 2)));
    }

    private int tabWidth(Tab entry) {
        return Math.min(this.width - 16, this.getStringWidth(entry.getName()) + 12);
    }

    private void buildFullListTabs() {
        int x = 8;
        int y = 44;
        for (Tab entry : Tab.values()) {
            int w = this.tabWidth(entry);
            if (x + w > this.width - 8 && x > 8) { x = 8; y += 22; }
            this.createButton(x, y, w, entry);
            x += w + 2;
        }
    }

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY) {
        if (this.initialized && this.visibilitySignature != this.currentVisibilitySignature()) this.initGui();
        RenderUtils.drawRect(ctx, 0, 0, this.width, this.height, 0xF0181C20);
        // MaLiLib draws buttons before drawContents; opaque sidebar fills must precede them.
        if (this.layout != null && !this.fullConfigList && this.layout.sidebarWidth() > 0) {
            RenderUtils.drawRect(ctx, 8, 42, this.layout.sidebarWidth(), this.layout.footerY() - 46, 0xFF14191C);
            int rowY = 46;
            for (WorkspacePage entry : WorkspacePage.values()) {
                if (entry == WorkspacePage.PRINT || entry == WorkspacePage.COMMON) rowY += 9;
                if (entry == this.page) {
                    RenderUtils.drawRect(ctx, 12, rowY, this.layout.sidebarWidth() - 8, 19, 0xFF304C43);
                    RenderUtils.drawRect(ctx, 12, rowY, 2, 19, 0xFFACD8CA);
                }
                rowY += 21;
            }
        }
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        this.drawString(ctx, fitText(Reference.GUI_TITLE, this.width - 16, this::getStringWidth), 8, 9, 0xFFE9EDF0);
        int noticeWidth = this.modSwitchWidget == null ? this.width - 16 : this.width - 176;
        this.drawString(ctx, fitText(I18n.FREE_NOTICE.getName().getString(), noticeWidth, this::getStringWidth),
                8, 27, 0xFFADB5BD);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        if (this.layout != null && !this.fullConfigList) {
            int y = this.layout.sidebarWidth() == 0 ? 66 : 46;
            int x = this.layout.listX() + 3;
            WidgetListConfigOptions list = this.getListWidget();
            boolean searching = list != null && list.getSearchBarWidget() != null && list.getSearchBarWidget().hasFilter();
            int headingWidth = this.layout.listWidth() - 8;
            if (this.hasPrintingDetailsControl()) headingWidth -= this.printingDetailsControlWidth() + 8;
            this.drawString(ctx, fitText(searching ? text("search_results") : this.page.title(),
                    headingWidth, this::getStringWidth), x, y, 0xFFB8E0D3);
            this.drawString(ctx, fitText(searching ? text("search_scope") : this.page.description(),
                    headingWidth, this::getStringWidth), x, y + 14, 0xFFADB5BD);
        }
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
    }

    private int currentVisibilitySignature() {
        int signature = Objects.hash(Configs.Core.WORK_MODE.getOptionListValue(), Configs.Core.WORK_MODE_TYPE.getOptionListValue(),
                Configs.Core.PRINT.getBooleanValue(), Configs.Core.MINE.getBooleanValue(), Configs.Core.FILL.getBooleanValue(),
                Configs.Core.COVER.getBooleanValue(), Configs.Core.FLUID.getBooleanValue(), Configs.Hotkeys.BEDROCK.getBooleanValue());
        for (IConfigBase config : Configs.OPTIONS) signature = signature * 31 + (ConfigMetadata.isVisible(config) ? 1 : 0);
        return signature;
    }

    static String text(String key) {
        return I18n.of("ui." + key).getName().getString();
    }

    static String fitText(String value, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(value) <= width) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && measure.applyAsInt(value.substring(0, end) + suffix) > width) {
            end = value.offsetByCodePoints(end, -1);
        }
        return measure.applyAsInt(suffix) <= width ? value.substring(0, end) + suffix : "";
    }

    private int createButton(int x, int y, int width, Tab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getName(), tab.getComment());
        button.setEnabled(ConfigUi.tab != tab);
        this.addButton(button, new ButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        ImmutableList.Builder<ConfigOptionWrapper> builder = ImmutableList.builder();
        if (!this.fullConfigList) {
            for (WorkspaceConfigModel.Section section : WorkspaceConfigModel.sections(this.page, this.printingDetails)) {
                builder.add(new ConfigOptionWrapper(I18n.of("ui.group." + section.key()).getName().getString()));
                section.configs().forEach(config -> builder.add(new ConfigOptionWrapper(config)));
            }
            return builder.build();
        }
        for (IConfigBase config : ConfigUi.tab.getConfigs()) {
            if (ConfigMetadata.isVisible(config)) {
                builder.add(new ConfigOptionWrapper(config));
            }
        }
        return builder.build();
    }

    public enum Tab {
        CORE(I18n.of("category.core")),
        SPECIAL(I18n.of("category.special")),
        PLACEMENT(I18n.of("category.placement")),
        BREAK(I18n.of("category.break")),
        HOTKEYS(I18n.of("category.hotkeys")),
        PRINT(I18n.of("category.print")),
        EXCAVATE(I18n.of("category.mine")),
        FILL(I18n.of("category.fill")),
        COVER(I18n.of("category.cover")),
        FLUID(I18n.of("category.fluid")),
        BEDROCK(I18n.of("category.bedrock")),
        HIGHLIGHT(I18n.of("category.highlight"));

        private final I18n i18n;

        Tab(I18n i18n) {
            this.i18n = i18n;
        }

        public String getName() {
            return i18n.getConfigName().getString();
        }

        public String getComment() {
            return i18n.getConfigDesc().getString();
        }

        public ImmutableList<IConfigBase> getConfigs() {
            return switch (this) {
                case CORE -> Configs.Core.OPTIONS;
                case SPECIAL -> Configs.Special.OPTIONS;
                case PLACEMENT -> Configs.Placement.OPTIONS;
                case BREAK -> Configs.Break.OPTIONS;
                case BEDROCK -> Configs.Bedrock.OPTIONS;
                case HIGHLIGHT -> Configs.Highlight.OPTIONS;
                case PRINT -> Configs.Print.OPTIONS;
                case EXCAVATE -> Configs.Mine.OPTIONS;
                case FILL -> Configs.Fill.OPTIONS;
                case COVER -> Configs.Cover.OPTIONS;
                case FLUID -> Configs.Fluid.OPTIONS;
                case HOTKEYS -> Configs.Hotkeys.OPTIONS;
            };
        }
    }

    public record ButtonListener(Tab tab, ConfigUi parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            this.parent.selectTab(this.tab);
        }
    }
}
