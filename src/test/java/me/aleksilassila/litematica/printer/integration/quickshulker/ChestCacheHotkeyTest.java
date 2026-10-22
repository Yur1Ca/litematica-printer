//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import fi.dy.masa.malilib.config.ConfigUtils;
import com.google.gson.JsonObject;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.HotkeysCallback;
import me.aleksilassila.litematica.printer.config.InputHandler;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import me.aleksilassila.litematica.printer.integration.inventory.ChestTrackerAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Drives the registered MaLiLib keybinds, including their real printer callbacks. */
class ChestCacheHotkeyTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class HeadlessConfigUi extends ConfigUi {
        HeadlessConfigUi() { super(null); }
        @Override protected void buildConfigSwitcher() { }
        void settingsChanged() { super.onSettingsChanged(); }
        @Override public List<ConfigOptionWrapper> getConfigs() {
            return List.of(new ConfigOptionWrapper(Configs.Hotkeys.CACHE_SELECTION_CONTAINERS),
                    new ConfigOptionWrapper(Configs.Hotkeys.CLEAR_CONTAINER_CACHE));
        }
    }

    @Test void configScreenKeepsTheNewlyCreatedHotkeyListeners() throws Exception {
        Object oldClient = get(null, Minecraft.class, "instance");
        var client = allocate(Minecraft.class);
        set(client, Minecraft.class, "font", new Font(null) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public int width(net.minecraft.network.chat.FormattedText text) { return width(text.getString()); }
        });
        set(null, Minecraft.class, "instance", client);
        try {
            var ui = new HeadlessConfigUi();
            ui.width = 1000;
            ui.height = 800;
            ui.initGui();
            var listeners = (List<?>) get(ui, GuiConfigsBase.class, "hotkeyChangeListeners");
            assertEquals(2, listeners.size(), "Saving newly bound cache keys must refresh the active key map");
            ui.initGui();
            assertEquals(2, listeners.size(), "Repeated initialization must not accumulate old listeners");
            ui.reset();
            assertEquals(2, listeners.size(), "Tab refresh must keep the replacement controls registered");
        } finally {
            set(null, Minecraft.class, "instance", oldClient);
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void boundCacheKeysAreRegisteredAndProduceFeedbackEvenWithNoContainers(boolean clear, boolean work) throws Exception {
        exercise(clear, work, false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void defaultKeySettingsRejectAnExtraHeldMovementKey(boolean clear) throws Exception {
        exercise(clear, false, true);
    }

    @ParameterizedTest @CsvSource({"false,''", "true,''", "false,F6", "true,F6"})
    void newlyAssignedCacheKeysWorkImmediatelyAfterSavingTheConfigScreen(boolean clear, String previousBinding) throws Exception {
        exercise(clear, false, false, true, previousBinding);
    }

    private void exercise(boolean clear, boolean work, boolean extraKey) throws Exception {
        exercise(clear, work, extraKey, false, "");
    }

    private void exercise(boolean clear, boolean work, boolean extraKey, boolean guiRebind, String previousBinding) throws Exception {
        Object oldClient = get(null, Minecraft.class, "instance");
        Object oldRuntime = get(null, RuntimeAccess.class, "current");
        Object oldInputManager = get(null, InputEventHandler.class, "INSTANCE");
        var client = allocate(Minecraft.class);
        client.player = allocate(LocalPlayer.class);
        client.level = allocate(ClientLevel.class);
        set(client.level, Level.class, "dimension", Level.OVERWORLD);
        set(client, Minecraft.class, "gui", allocate(Gui.class));
        set(null, Minecraft.class, "instance", client);
        var runtime = allocate(PrinterRuntime.class);
        var adapter = new ChestTrackerAdapter(null);
        var cache = get(adapter, ChestTrackerAdapter.class, "selectedContainers");
        set(cache, cache.getClass(), "loaded", true); // Do not read or modify a real cache file.
        set(runtime, PrinterRuntime.class, "chestTrackerAdapter", adapter);
        RuntimeAccess.install(runtime);
        Object oldCallbackClient = get(null, HotkeysCallback.class, "client");
        setCallbackClient(client);
        boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        var option = clear ? Configs.Hotkeys.CLEAR_CONTAINER_CACHE : Configs.Hotkeys.CACHE_SELECTION_CONTAINERS;
        var keybind = option.getKeybind();
        String oldKeys = keybind.getStringValue();
        var oldSettings = keybind.getSettings();
        Object oldTriggered = get(null, KeybindMulti.class, "triggeredCount");
        var oldStates = new LinkedHashMap<Object, Map<String, Object>>();
        for (var hotkey : Configs.HOTKEYS) {
            var state = new LinkedHashMap<String, Object>();
            for (String field : List.of("pressed", "pressedLast", "heldTime", "dirty")) {
                state.put(field, get(hotkey.getKeybind(), KeybindMulti.class, field));
            }
            oldStates.put(hotkey.getKeybind(), state);
        }
        @SuppressWarnings("unchecked")
        var pressed = (List<Integer>) get(null, KeybindMulti.class, "PRESSED_KEYS");
        var oldPressed = List.copyOf(pressed);
        try {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            var json = new JsonObject();
            var hotkey = new JsonObject();
            hotkey.addProperty("keys", clear ? "Z,B" : "Z,V");
            var group = new JsonObject();
            group.add(option.getName(), hotkey);
            json.add("litematica-printer", group);
            ConfigUtils.readConfigBase(json, "litematica-printer", List.of(option));
            var saved = new JsonObject();
            ConfigUtils.writeConfigBase(saved, "litematica-printer", List.of(option));
            assertEquals(hotkey, saved.getAsJsonObject("litematica-printer").get(option.getName()));
            var constructor = InputEventHandler.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            var manager = constructor.newInstance();
            setStaticFinal(InputEventHandler.class, "INSTANCE", manager);
            if (guiRebind) option.setValueFromString(previousBinding);
            manager.registerKeybindProvider(InputHandler.getInstance());
            manager.updateUsedKeys();
            if (guiRebind) {
                set(client, Minecraft.class, "font", new Font(null) {
                    @Override public int width(String text) { return text.length() * 6; }
                    @Override public int width(net.minecraft.network.chat.FormattedText text) { return width(text.getString()); }
                });
                var ui = new HeadlessConfigUi();
                ui.width = 1000;
                ui.height = 800;
                ui.initGui();
                ConfigUtils.readConfigBase(json, "litematica-printer", List.of(option));
                ui.settingsChanged();
            }
            pressed.clear();
            set(null, KeybindMulti.class, "triggeredCount", 0);
            if (guiRebind && !previousBinding.isEmpty()) {
                assertFalse(manager.onKeyInput(new KeyEvent(295, 0, 0), 1, client), "The old F6 binding must stop triggering");
                manager.onKeyInput(new KeyEvent(295, 0, 0), 0, client);
            }
            if (extraKey) manager.onKeyInput(new KeyEvent(87, 0, 0), 1, client);
            assertFalse(manager.onKeyInput(new KeyEvent(90, 0, 0), 1, client));
            assertEquals(!extraKey, manager.onKeyInput(new KeyEvent(clear ? 66 : 86, 0, 0), 1, client));
            Component message = (Component) get(client.gui, Gui.class, "overlayMessageString");
            if (extraKey) {
                assertNull(message, "Default MaLiLib settings ignore combinations with extra held keys");
            } else {
                assertNotNull(message, "An empty selection/cache must still produce feedback");
                assertTrue(message.getString().contains(clear ? "已清除 0" : "已加入 0"));
            }
        } finally {
            pressed.clear();
            pressed.addAll(oldPressed);
            option.setValueFromString(oldKeys);
            keybind.setSettings(oldSettings);
            for (var entry : oldStates.entrySet()) {
                for (var field : entry.getValue().entrySet()) set(entry.getKey(), KeybindMulti.class, field.getKey(), field.getValue());
            }
            set(null, KeybindMulti.class, "triggeredCount", oldTriggered);
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            setCallbackClient(oldCallbackClient);
            setStaticFinal(InputEventHandler.class, "INSTANCE", oldInputManager);
            set(null, Minecraft.class, "instance", oldClient);
            set(null, RuntimeAccess.class, "current", oldRuntime);
        }
    }

    @SuppressWarnings("removal")
    private static void setCallbackClient(Object client) throws Exception {
        setStaticFinal(HotkeysCallback.class, "client", client);
    }

    @SuppressWarnings("removal")
    private static void setStaticFinal(Class<?> type, String name, Object value) throws Exception {
        var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) access.get(null);
        var field = type.getDeclaredField(name);
        unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), value);
    }
}
//#endif
