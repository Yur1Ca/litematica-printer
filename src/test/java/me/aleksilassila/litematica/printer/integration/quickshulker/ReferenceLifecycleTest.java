//#if MC >= 260300
//$$ package me.aleksilassila.litematica.printer.integration.quickshulker;
//$$
//$$ import java.io.IOException;
//$$ import java.lang.reflect.Field;
//$$ import net.minecraft.core.Holder;
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//$$ import net.minecraft.world.item.Items;
//$$ import org.junit.jupiter.api.BeforeAll;
//$$ import org.junit.jupiter.api.Test;
//$$ import static org.junit.jupiter.api.Assertions.*;
//$$
//$$ class ReferenceLifecycleTest {
//$$     @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }
//$$
//$$     @Test void openingConfigurationBeforeComponentsBindDoesNotPoisonTheSession() throws Exception {
//$$         var air = BuiltInRegistries.ITEM.wrapAsHolder(Items.AIR);
//$$         Field components = Holder.Reference.class.getDeclaredField("components");
//$$         components.setAccessible(true);
//$$         Object saved = components.get(air);
//$$         try {
//$$             components.set(air, null);
//$$             assertFalse(((Holder.Reference<?>) air).areComponentsBound());
//$$             ClassLoader loader = freshReferenceLoader();
//$$             Class<?> reference = assertDoesNotThrow(() -> Class.forName(
//$$                     "me.aleksilassila.litematica.printer.Reference", true, loader));
//$$             String title = (String) reference.getField("GUI_TITLE").get(null);
//$$             assertTrue(title.contains("Litematica Printer"));
//$$             assertNull(components.get(air), "Opening settings must not bind world data itself");
//$$             components.set(air, saved);
//$$             assertEquals(title, reference.getField("GUI_TITLE").get(null),
//$$                     "The same Reference class must remain usable after joining a world");
//$$         } finally {
//$$             components.set(air, saved);
//$$         }
//$$     }
//$$
//$$     // Isolate only the real production class, so earlier tests cannot hide <clinit> failures.
//$$     private static ClassLoader freshReferenceLoader() {
//$$         return new ClassLoader(ReferenceLifecycleTest.class.getClassLoader()) {
//$$             @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
//$$                 if (!name.equals("me.aleksilassila.litematica.printer.Reference")) {
//$$                     return super.loadClass(name, resolve);
//$$                 }
//$$                 synchronized (getClassLoadingLock(name)) {
//$$                     Class<?> loaded = findLoadedClass(name);
//$$                     if (loaded == null) {
//$$                         try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
//$$                             if (input == null) throw new ClassNotFoundException(name);
//$$                             byte[] bytes = input.readAllBytes();
//$$                             loaded = defineClass(name, bytes, 0, bytes.length);
//$$                         } catch (IOException error) {
//$$                             throw new ClassNotFoundException(name, error);
//$$                         }
//$$                     }
//$$                     if (resolve) resolveClass(loaded);
//$$                     return loaded;
//$$                 }
//$$             }
//$$         };
//$$     }
//$$ }
//#endif
