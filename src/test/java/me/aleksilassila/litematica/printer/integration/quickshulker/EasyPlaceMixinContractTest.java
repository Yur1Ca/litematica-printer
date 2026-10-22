package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies our injection contract against each build's real Litematica dependency. */
class EasyPlaceMixinContractTest {
    private static final String MIXIN = "printer.litematica.MixinEasyPlaceUtils";

    @Test
    void rewrittenEntryIsRegisteredOnlyWhereLitematicaProvidesIt() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("litematica-printer.litematica.mixins.json")) {
            assertNotNull(in);
            var json = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            var names = new ArrayList<String>();
            json.getAsJsonArray("mixins").forEach(value -> names.add(value.getAsString()));
            //#if MC > 12100
            assertTrue(names.contains(MIXIN), "Rewritten Easy Place must enter the manual material request chain");
            //#else
            //$$ assertFalse(names.contains(MIXIN), "Older Litematica has no rewritten EasyPlaceUtils target");
            //#endif
        }
    }

    //#if MC > 12100
    @Test
    @SuppressWarnings("unchecked")
    void hookMatchesTheRealPickerAndCapturesItsRequiredStackBeforeItRuns() throws Exception {
        var mixin = read("me/aleksilassila/litematica/printer/mixin/" + MIXIN.replace('.', '/'));
        var target = read("fi/dy/masa/litematica/util/EasyPlaceUtils");
        var handler = mixin.methods.stream().filter(m -> m.name.equals("litematicaPrinter$restock")).findFirst().orElseThrow();
        var inject = annotations(handler.visibleAnnotations, handler.invisibleAnnotations).stream()
                .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, value(inject, "cancellable"), "Pending acquisition must stop the outer placement too");
        var names = (List<String>) value(inject, "method");
        assertEquals(List.of("handleEasyPlace"), names);
        var method = target.methods.stream().filter(m -> m.name.equals(names.get(0))).findFirst().orElseThrow();
        assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
        assertEquals("()Lnet/minecraft/world/InteractionResult;", method.desc);
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("INVOKE", value(at, "value"));
        assertNull(value(at, "shift"), "The callback must run before native picking, not after an inventory mutation");
        String selector = (String) value(at, "target");
        var matching = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && selector.equals("L" + call.owner + ";" + call.name)) matching.add(call);
        }
        assertEquals(1, matching.size(), "Injection selector must match exactly one actual native picker");
        int injectionIndex = method.instructions.indexOf(matching.get(0));
        var stacks = method.localVariables.stream().filter(local ->
                local.desc.equals("Lnet/minecraft/world/item/ItemStack;")
                        && method.instructions.indexOf(local.start) <= injectionIndex
                        && injectionIndex < method.instructions.indexOf(local.end)).toList();
        assertEquals(1, stacks.size(), "Local capture must identify the required stack without ambiguity");
        int requiredSlot = stacks.get(0).index;
        boolean capturesMaterialCacheResult = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("fi/dy/masa/litematica/materials/MaterialCache")
                    && call.name.equals("getRequiredBuildItemForState")) {
                var store = call.getNext();
                while (store != null && store.getOpcode() < 0) store = store.getNext();
                capturesMaterialCacheResult = store instanceof VarInsnNode variable
                        && variable.getOpcode() == Opcodes.ASTORE && variable.var == requiredSlot;
            }
        }
        assertTrue(capturesMaterialCacheResult, "Use the actual target material, including Easy Place position overrides");
        var local = annotations(handler.visibleParameterAnnotations == null ? null : handler.visibleParameterAnnotations[1],
                handler.invisibleParameterAnnotations == null ? null : handler.invisibleParameterAnnotations[1]).stream()
                .filter(a -> a.desc.endsWith("/Local;")).findFirst().orElseThrow();
        assertEquals(0, value(local, "ordinal"));
    }

    private static ClassNode read(String name) throws Exception {
        try (var in = EasyPlaceMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(in, "Missing compatibility entry " + name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var all = new ArrayList<AnnotationNode>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
    //#endif
}
