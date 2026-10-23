package me.aleksilassila.litematica.printer.mixin_plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class InventoryProfilesNextKeeperContractTest {
    private static final String TARGET = "org/anti_ad/mc/ipnext/event/LockedSlotKeeper";

    private static boolean supported(byte[] bytes) throws Exception {
        try {
            var type = Class.forName("me.aleksilassila.litematica.printer.mixin_plugin.InventoryProfilesNextMixinConfigPlugin");
            var method = type.getDeclaredMethod("supportsLockedSlotKeeperContract", byte[].class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, (Object) bytes);
        } catch (ClassNotFoundException | NoSuchMethodException absent) { return false; }
    }

    private static byte[] contract(String target, String omitted, String markerType, int markerAccess, int methodAccess) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
        if (!omitted.equals("screenOpening"))
            writer.visitField(markerAccess, "screenOpening", markerType, null, null).visitEnd();
        for (String method : new String[]{"onTickInGame", "init"}) {
            if (!method.equals(omitted)) writer.visitMethod(methodAccess, method, "()V", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void nativeKeeperPauseAndResumeContractIsAccepted() throws Exception {
        assertTrue(supported(contract(TARGET, "", "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC)));
    }

    @Test void absentOrChangedResumeMarkerIsRejected() throws Exception {
        for (String missing : new String[]{"screenOpening", "onTickInGame", "init"})
            assertFalse(supported(contract(TARGET, missing, "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC)));
        assertFalse(supported(contract(TARGET, "", "I", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC)));
        assertFalse(supported(contract(TARGET, "", "Z", Opcodes.ACC_PRIVATE, Opcodes.ACC_PUBLIC)));
        assertFalse(supported(contract(TARGET, "", "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, Opcodes.ACC_PUBLIC)));
    }

    @Test void nonInstanceMethodsAndUnrelatedClassesAreRejected() throws Exception {
        assertFalse(supported(contract(TARGET, "", "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
        assertFalse(supported(contract(TARGET, "", "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)));
        assertFalse(supported(contract("example/LockedSlotKeeper", "", "Z", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC)));
        assertFalse(supported(new byte[]{0, 1, 2}));
    }
}
