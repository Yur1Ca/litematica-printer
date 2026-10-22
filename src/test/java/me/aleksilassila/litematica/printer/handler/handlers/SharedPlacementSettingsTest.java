package me.aleksilassila.litematica.printer.handler.handlers;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.config.ConfiguredPlacementSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedPlacementSettingsTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void existingLiveKeysStillWakeBothFeaturesAndRefreshFillReplacementRules() throws Exception {
        var settings = new ConfiguredPlacementSettings();
        var fill = allocate(FillHandler.class);
        var fluid = allocate(FluidHandler.class);
        set(fill, "placementSettings", settings);
        set(fluid, "placementSettings", settings);
        set(fluid, "fillBlocks", List.of());
        set(fluid, "fluidBlocks", List.of());
        boolean beforeAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        var beforeList = List.copyOf(Configs.Print.REPLACEABLE_LIST.getStrings());
        try {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
            Configs.Print.REPLACEABLE_LIST.setStrings(List.of("minecraft:stone"));
            call(fill, "updateReplaceableFilterCache");
            int fillBefore = (int) call(fill, "getFillScanConfigHash");
            int fluidBefore = (int) call(fluid, "getScanConfigHash");
            assertFalse(settings.placeInAir());
            Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
            assertTrue(settings.placeInAir());
            assertNotEquals(fillBefore, call(fill, "getFillScanConfigHash"));
            assertNotEquals(fluidBefore, call(fluid, "getScanConfigHash"));
            Configs.Print.REPLACEABLE_LIST.setStrings(List.of("minecraft:dirt", "minecraft:grass_block"));
            call(fill, "updateReplaceableFilterCache");
            Field filters = FillHandler.class.getDeclaredField("replaceableFilters");
            filters.setAccessible(true);
            assertArrayEquals(new String[]{"minecraft:dirt", "minecraft:grass_block"}, (String[]) filters.get(fill));
            Object cached = filters.get(fill);
            call(fill, "updateReplaceableFilterCache");
            assertSame(cached, filters.get(fill));
        } finally {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(beforeAir);
            Configs.Print.REPLACEABLE_LIST.setStrings(beforeList);
        }
    }
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
