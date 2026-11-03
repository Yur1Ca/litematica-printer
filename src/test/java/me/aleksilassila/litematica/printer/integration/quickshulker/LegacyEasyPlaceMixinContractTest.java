package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the legacy gate bindings against each version's actual Litematica dependency. */
class LegacyEasyPlaceMixinContractTest {
    private static final String WORLD_UTILS = "fi/dy/masa/litematica/util/WorldUtils";
    private static final String MIXIN_PACKAGE = "me/aleksilassila/litematica/printer/mixin/printer/litematica/";
    private static final Set<String> GATES = Set.of("easyPlaceIsPositionCached", "easyPlaceIsTooFast",
            "easyPlaceBlockChecksCancel", "placementRestrictionInEffect");

    @Test
    void everyShadowAndInvokerMatchesTheRealNameDescriptorAndStaticTarget() throws Exception {
        var bindings = bindings();
        assertEquals(5, bindings.size(), "All four native gates and the existing pick-block timestamp must be bound");
        assertEquals(Set.of("easyPlaceIsPositionCached", "easyPlaceIsTooFast", "easyPlaceBlockChecksCancel",
                        "placementRestrictionInEffect", "setEasyPlaceLastPickBlockTime"),
                bindings.stream().map(Binding::name).collect(Collectors.toSet()));
        for (var binding : bindings) {
            var target = read(binding.owner());
            var matches = target.methods.stream().filter(method -> method.name.equals(binding.name())
                    && method.desc.equals(binding.descriptor())).toList();
            assertEquals(1, matches.size(), "Missing native binding " + binding);
            assertTrue((binding.access() & Opcodes.ACC_STATIC) != 0, "Legacy bindings must remain static: " + binding);
            assertEquals(binding.access() & Opcodes.ACC_STATIC, matches.get(0).access & Opcodes.ACC_STATIC,
                    "Binding and native method must agree on static dispatch: " + binding);
        }
    }

    @Test
    void gateBindingsUseTheSameOwnerAndDescriptorAsNativeLegacyPlacement() throws Exception {
        var nativeMethods = read(WORLD_UTILS).methods.stream()
                .filter(method -> method.name.equals("doEasyPlaceAction")).toList();
        assertEquals(1, nativeMethods.size(), "The legacy placement entry must remain unambiguous");
        var gateBindings = bindings().stream().filter(binding -> GATES.contains(binding.name())).toList();
        assertEquals(4, gateBindings.size(), "Do not silently skip a missing gate binding");
        for (var binding : gateBindings) {
            var calls = new ArrayList<MethodInsnNode>();
            for (var instruction : nativeMethods.get(0).instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(binding.name())) calls.add(call);
            }
            assertFalse(calls.isEmpty(), "Native legacy placement must still use " + binding.name());
            for (var call : calls) {
                assertEquals(binding.owner(), call.owner,
                        "Use the active native gate owner, not a deprecated WorldUtils copy: " + binding.name());
                assertEquals(binding.descriptor(), call.desc, "Use the legacy overload: " + binding.name());
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
            }
        }
    }

    private static List<Binding> bindings() throws IOException {
        var bindings = new ArrayList<Binding>();
        for (String simpleName : List.of("MixinInventoryUtils", "EasyPlaceUtilsAccessor")) {
            var mixin = read(MIXIN_PACKAGE + simpleName);
            var annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                    .filter(candidate -> candidate.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
            var targets = (List<?>) value(annotation, "value");
            assertNotNull(targets, "Mixin must declare its native target: " + simpleName);
            assertEquals(1, targets.size(), "Each legacy binding class must have one target");
            String owner = ((Type) targets.get(0)).getInternalName();
            for (var method : mixin.methods) {
                for (var methodAnnotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    boolean shadow = methodAnnotation.desc.endsWith("/Shadow;");
                    if (!shadow && !methodAnnotation.desc.endsWith("/Invoker;")) continue;
                    String name = shadow ? method.name : (String) value(methodAnnotation, "value");
                    assertNotNull(name, "Invoker must explicitly name its native method: " + method.name);
                    assertTrue(Boolean.FALSE.equals(value(methodAnnotation, "remap"))
                                    || Boolean.FALSE.equals(value(annotation, "remap")),
                            "Litematica method names must not be remapped: " + method.name);
                    bindings.add(new Binding(owner, name, method.desc, method.access));
                }
            }
        }
        return bindings;
    }

    private record Binding(String owner, String name, String descriptor, int access) {}

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = LegacyEasyPlaceMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Missing compatibility class " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
