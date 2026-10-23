package me.aleksilassila.litematica.printer.mixin_plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class InventoryProfilesNextContractTest {
    private static boolean supported(byte[] bytes) throws Exception {
        try {
            var type = Class.forName("me.aleksilassila.litematica.printer.mixin_plugin.InventoryProfilesNextMixinConfigPlugin");
            var method = type.getDeclaredMethod("supportsAutoRefillContract", byte[].class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, (Object) bytes);
        } catch (ClassNotFoundException absent) { return false; }
    }
    private static byte[] contract(String omitted, String setterDescriptor) {
        return contract("org/anti_ad/mc/ipnext/event/autorefill/AutoRefillHandler", omitted, setterDescriptor);
    }
    private static byte[] contract(String target, String omitted, String setterDescriptor) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
        for (String method : new String[]{"onTickInGame", "setScreenOpening", "init"}) {
            if (method.equals(omitted)) continue;
            writer.visitMethod(Opcodes.ACC_PUBLIC, method,
                    method.equals("setScreenOpening") ? setterDescriptor : "()V", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
    @Test void exactNativePauseAndResumeApiIsAccepted() throws Exception {
        assertTrue(supported(contract("", "(Z)V")));
    }
    @Test void legacyNativePauseAndResumeApiIsAccepted() throws Exception {
        assertTrue(supported(contract("org/anti_ad/mc/ipnext/event/AutoRefillHandler", "", "(Z)V")));
    }
    @Test void unrelatedClassWithSameMethodsIsRejected() throws Exception {
        assertFalse(supported(contract("example/AutoRefillHandler", "", "(Z)V")));
    }
    @Test void missingOrChangedMethodsAreRejected() throws Exception {
        for (String missing : new String[]{"onTickInGame", "setScreenOpening", "init"})
            assertFalse(supported(contract(missing, "(Z)V")));
        assertFalse(supported(contract("", "(I)V")));
    }
    @Test void malformedBytecodeIsRejectedWithoutCrashing() throws Exception {
        assertFalse(supported(new byte[]{0, 1, 2}));
    }
}
