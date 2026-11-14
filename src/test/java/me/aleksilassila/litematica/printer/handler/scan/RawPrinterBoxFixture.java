package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.printer.PrinterBox;

import java.lang.reflect.Field;

/** Exact geometry for pure cursor tests, without the constructor's current-world height clamp. */
final class RawPrinterBoxFixture {
    private static final sun.misc.Unsafe UNSAFE = unsafe();
    private static final Field MIN_X = coordinateField("minX");
    private static final Field MIN_Y = coordinateField("minY");
    private static final Field MIN_Z = coordinateField("minZ");
    private static final Field MAX_X = coordinateField("maxX");
    private static final Field MAX_Y = coordinateField("maxY");
    private static final Field MAX_Z = coordinateField("maxZ");

    private RawPrinterBoxFixture() { }

    static PrinterBox rawBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        try {
            PrinterBox box = (PrinterBox) UNSAFE.allocateInstance(PrinterBox.class);
            MIN_X.setInt(box, minX);
            MIN_Y.setInt(box, minY);
            MIN_Z.setInt(box, minZ);
            MAX_X.setInt(box, maxX);
            MAX_Y.setInt(box, maxY);
            MAX_Z.setInt(box, maxZ);
            return box;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot allocate raw cursor geometry", failure);
        }
    }

    private static sun.misc.Unsafe unsafe() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Field coordinateField(String name) {
        try {
            Field field = PrinterBox.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
