package me.aleksilassila.litematica.printer.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigOptionList;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetHoverInfo;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonKeybind;
import fi.dy.masa.malilib.gui.button.ConfigButtonOptionList;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.client.gui.navigation.ScreenRectangle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Native MaLiLib editors in a grouped list, with a search spanning all pages. */
final class WorkspaceConfigList extends WidgetListConfigOptions {
    private final boolean workspace;

    WorkspaceConfigList(int x, int y, int width, int height, int configWidth, ConfigUi parent, boolean workspace) {
        super(x, y, width, height, configWidth, 0, false, parent);
        this.workspace = workspace;
        if (workspace) {
            this.widgetSearchBar = new SearchBar(x + 2, y + 4, width - 14);
            this.browserEntriesOffsetY = 24;
            this.browserEntryHeight = 25;
        }
    }

    void setSearchText(String text) {
        ((SearchBar) this.widgetSearchBar).setText(text);
        this.refreshEntries();
        this.resetScrollbarPosition();
    }

    @Override
    protected void reCreateListEntryWidgets() {
        // Scroll/search discards row controls too, not just page changes.
        ((ConfigUi) this.parent).prepareListRebuild(this);
        super.reCreateListEntryWidgets();
    }

    @Override
    protected Collection<ConfigOptionWrapper> getAllEntries() {
        return this.workspace && this.hasFilter()
                ? ConfigOptionWrapper.createFor(WorkspaceConfigModel.allVisibleConfigs())
                : this.parent.getConfigs();
    }

    @Override
    protected List<String> getEntryStringsForFilter(ConfigOptionWrapper entry) {
        List<String> strings = new ArrayList<>(super.getEntryStringsForFilter(entry));
        IConfigBase config = entry.getConfig();
        if (config != null) {
            strings.addAll(PinYinSearchUtils.getPinYin(config.getConfigGuiDisplayName()));
        }
        return strings;
    }

    @Override
    protected void addFilteredContents(Collection<ConfigOptionWrapper> entries) {
        if (!this.workspace) {
            super.addFilteredContents(entries);
            return;
        }
        Set<IConfigBase> added = new LinkedHashSet<>();
        for (ConfigUi.Tab tab : ConfigUi.Tab.values()) {
            List<ConfigOptionWrapper> matches = entries.stream().filter(entry -> entry.getConfig() != null
                    && tab.getConfigs().contains(entry.getConfig())
                    && this.entryMatchesFilter(entry, this.getFilterText())
                    && added.add(entry.getConfig())).toList();
            if (!matches.isEmpty()) {
                this.listContents.add(new ConfigOptionWrapper(tab.getName()));
                this.listContents.addAll(matches);
            }
        }
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        if (this.hasFilter() && this.listContents.isEmpty()) {
            this.drawString(ctx, ConfigUi.fitText(ConfigUi.text("no_results"), this.browserEntryWidth,
                    this::getStringWidth), this.posX + 4, this.posY + 32, 0xFFADB5BD);
        }
    }

    @Override
    public int getMaxNameLengthWrapped(List<ConfigOptionWrapper> entries) {
        int resetWidth = this.getStringWidth(StringUtils.translate("malilib.gui.button.reset.caps")) + 10;
        return Math.max(16, Math.min(super.getMaxNameLengthWrapped(entries),
                this.browserEntryWidth - this.configWidth - resetWidth - 14));
    }

    @Override
    protected WidgetConfigOption createListEntryWidget(int x, int y, int index, boolean odd, ConfigOptionWrapper wrapper) {
        return new OptionRow(x, y, this.browserEntryWidth, this.browserEntryHeight,
                this.maxLabelWidth, this.configWidth, wrapper, index, this);
    }

    private static final class SearchBar extends WidgetSearchBar {
        SearchBar(int x, int y, int width) {
            super(x, y, width, 16, 0, MaLiLibIcons.SEARCH, LeftRight.LEFT);
            // Show the field without briefly stealing keyboard/IME focus on each rebuild.
            this.searchOpen = true;
            this.searchBox.setHoverTooltip(I18n.of("ui.search").getName().getString());
        }

        void setText(String text) {
            this.searchOpen = true;
            this.searchBox.setValueWrapper(text);
        }
    }

    private static final class OptionRow extends WidgetConfigOption {
        OptionRow(int x, int y, int width, int height, int labelWidth, int configWidth,
                  ConfigOptionWrapper wrapper, int index, WorkspaceConfigList list) {
            super(x, y, width, height, labelWidth, configWidth, wrapper, index, list.parent, list);
        }

        @Override
        public void addLabel(int x, int y, int width, int height, int color, String... lines) {
            boolean heading = this.wrapper != null && this.wrapper.getType() == ConfigOptionWrapper.Type.LABEL;
            int available = heading ? this.getWidth() - 8 : width;
            String[] clipped = new String[lines.length];
            for (int i = 0; i < lines.length; i++) clipped[i] = ConfigUi.fitText(lines[i], available, this::getStringWidth);
            super.addLabel(x, y, available, height, heading ? 0xFFACD8CA : color, clipped);
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
            if (this.wrapper.getType() == ConfigOptionWrapper.Type.LABEL) {
                RenderUtils.drawRect(ctx, this.getX(), this.getY() + this.getHeight() - 2,
                        this.getWidth() - 4, 1, 0xFF3B4846);
            }
            super.render(ctx, mouseX, mouseY, selected);
        }

        @Override
        protected void drawSubWidgets(GuiContext ctx, int mouseX, int mouseY) {
            this.hoveredSubWidget = null;
            for (WidgetBase widget : this.subWidgets) {
                boolean button = widget instanceof ButtonBase;
                if (button) ctx.pushScissor(new ScreenRectangle(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
                try {
                    widget.render(ctx, mouseX, mouseY, false);
                } finally {
                    if (button) ctx.popScissor();
                }
                if (widget.isMouseOver(mouseX, mouseY)) this.hoveredSubWidget = widget;
            }
        }

        @Override
        public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected) {
            IConfigBase config = this.wrapper.getConfig();
            for (WidgetBase widget : this.subWidgets) {
                if (!(widget instanceof ButtonBase button) || !button.isMouseOver(mouseX, mouseY)) continue;
                String fullValue = null;
                if (button instanceof ConfigButtonOptionList && config instanceof IConfigOptionList option) {
                    fullValue = option.getOptionListValue().getDisplayName();
                } else if (button instanceof ConfigButtonKeybind && config instanceof IHotkey hotkey) {
                    fullValue = hotkey.getKeybind().getKeysDisplayString();
                }
                if (fullValue != null && this.getStringWidth(fullValue) > button.getWidth() - 8) {
                    List<String> hover = new ArrayList<>();
                    hover.add(fullValue);
                    hover.addAll(button.getHoverStrings());
                    new WidgetHoverInfo(button.getX(), button.getY(), button.getWidth(), button.getHeight(),
                            String.join("\n", hover)).postRenderHovered(ctx, mouseX, mouseY, selected);
                    return;
                }
            }
            super.postRenderHovered(ctx, mouseX, mouseY, selected);
        }
    }
}
