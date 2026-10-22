//#if MC == 260100
package me.aleksilassila.litematica.printer.runtime;

import fi.dy.masa.malilib.config.options.ConfigBase;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.InitHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkSwitchBaselineTest {
    @BeforeAll static void bootstrap() { ConnectionDisconnectTest.bootstrap(); }

    @Test void actualTotalSwitchCallbackResetsTheScopeAndFlushesDiagnostics() throws Exception {
        try (var f = new ConnectionDisconnectTest.Fixture()) {
            var saved = snapshotCallbacks();
            try {
                // Register the actual callbacks, without config file or renderer initialization.
                var initialize = InitHandler.class.getDeclaredMethod("initConfigCallback");
                initialize.setAccessible(true);
                initialize.invoke(new InitHandler());
                var lines = new ArrayList<String>();
                var timing = new RuntimeTiming(true, () -> 1_000_000);
                f.scope.register(new RuntimeDiagnostics(timing, lines::add, 100));
                timing.finish("print", RuntimeTiming.Stage.MODULE_TICK, 0);

                Configs.Core.WORK_SWITCH.setBooleanValue(false);

                assertEquals(1, f.resets.get(), "Total switch off retains the existing global scope reset");
                assertEquals(RuntimeEpoch.INITIAL.next(), f.runtime.epoch());
                assertEquals(1, lines.size());
                assertTrue(lines.get(0).contains("epoch=0 ticks=0 reason=epoch_end"));
                assertTrue(lines.get(0).contains("print/MODULE_TICK"));
                assertTrue(timing.snapshot().samples().isEmpty());

                Configs.Core.WORK_SWITCH.setBooleanValue(true);
                assertEquals(1, f.resets.get(), "Enabling is not a scope reset");
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                assertEquals(2, f.resets.get());
                assertEquals(RuntimeEpoch.INITIAL.next().next(), f.runtime.epoch());
                assertEquals(2, lines.size());
                assertFalse(lines.get(1).contains("print/MODULE_TICK"));
            } finally {
                // Restore callbacks before Fixture restores config values and the previous runtime.
                for (var callback : saved) callback.field().set(callback.target(), callback.value());
            }
        }
    }

    private static List<SavedCallback> snapshotCallbacks() throws Exception {
        var saved = new ArrayList<SavedCallback>();
        var configCallback = ConfigBase.class.getDeclaredField("callback");
        configCallback.setAccessible(true);
        for (var group : Configs.class.getDeclaredClasses()) {
            for (var field : group.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && ConfigBase.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object config = field.get(null);
                    saved.add(new SavedCallback(config, configCallback, configCallback.get(config)));
                }
            }
        }
        var keyCallback = KeybindMulti.class.getDeclaredField("callback");
        keyCallback.setAccessible(true);
        Object key = Configs.Hotkeys.CLOSE_ALL_MODE.getKeybind();
        saved.add(new SavedCallback(key, keyCallback, keyCallback.get(key)));
        return saved;
    }

    private record SavedCallback(Object target, Field field, Object value) { }
}
//#endif
