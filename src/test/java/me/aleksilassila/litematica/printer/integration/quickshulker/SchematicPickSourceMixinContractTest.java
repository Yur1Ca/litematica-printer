package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the actual user-input call sites without relying on physical key state or caller stacks. */
class SchematicPickSourceMixinContractTest {
    private static final String WORLD_UTILS = "fi/dy/masa/litematica/util/WorldUtils";
    private static final String PICK_METHOD = "doSchematicWorldPickBlock";
    private static final String PICK_SELECTOR = "L" + WORLD_UTILS + ";" + PICK_METHOD;
    private static final Type OPERATION = Type.getObjectType("com/llamalad7/mixinextras/injector/wrapoperation/Operation");

    @ParameterizedTest
    @CsvSource({
            "fi/dy/masa/litematica/event/KeyCallbacks$KeyCallbackHotkeys, onKeyAction, 2",
            "fi/dy/masa/litematica/event/InputHandler, handleUseKey, 1"
    })
    void registeredScopeWrapperCoversEveryNativeUserPickCall(String owner, String methodName, int expectedCalls)
            throws Exception {
        var entry = read(owner).methods.stream().filter(method -> method.name.equals(methodName)).toList();
        assertEquals(1, entry.size(), "User-input entry must remain unambiguous: " + owner + "." + methodName);
        assertEquals(0, entry.get(0).access & Opcodes.ACC_STATIC, "Both input entries are instance methods");
        String expectedEntryDescriptor = methodName.equals("onKeyAction")
                ? "(Lfi/dy/masa/malilib/hotkeys/KeyAction;Lfi/dy/masa/malilib/hotkeys/IKeybind;)Z"
                : "(Lnet/minecraft/client/Minecraft;)Z";
        assertEquals(expectedEntryDescriptor, entry.get(0).desc);

        var nativePick = read(WORLD_UTILS).methods.stream().filter(method -> method.name.equals(PICK_METHOD)).toList();
        assertEquals(1, nativePick.size(), "The descriptor-free selector must identify exactly one native method");
        assertEquals("(ZLnet/minecraft/client/Minecraft;)Z", nativePick.get(0).desc);
        assertTrue((nativePick.get(0).access & Opcodes.ACC_STATIC) != 0);
        var calls = new ArrayList<MethodInsnNode>();
        for (var instruction : entry.get(0).instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(WORLD_UTILS) && call.name.equals(PICK_METHOD)) calls.add(call);
        }
        assertEquals(expectedCalls, calls.size(), "All explicit first/last pick calls must remain covered");
        for (var call : calls) {
            assertEquals(nativePick.get(0).desc, call.desc);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        }

        var wrappers = registeredWrappers(owner, methodName);
        assertEquals(1, wrappers.size(), "Register exactly one scope wrapper for " + owner + "." + methodName);
        var wrapper = wrappers.get(0);
        assertEquals(Boolean.FALSE, value(wrapper.mixin(), "remap"), "Litematica entry names are not Minecraft mappings");
        var atValue = value(wrapper.operation(), "at");
        var at = atValue instanceof List<?> list ? (AnnotationNode) list.get(0) : (AnnotationNode) atValue;
        assertNotNull(at, "Scope wrapper must select the native pick invocation");
        assertEquals("INVOKE", value(at, "value"));
        assertEquals(PICK_SELECTOR, value(at, "target"));
        assertNull(value(at, "ordinal"), "Both first and last pick calls must be wrapped, without an ordinal filter");
        assertNull(value(wrapper.operation(), "slice"), "Do not silently restrict the user-pick call sites");
        var arguments = new ArrayList<>(List.of(Type.getArgumentTypes(nativePick.get(0).desc)));
        arguments.add(OPERATION);
        assertArrayEquals(arguments.toArray(Type[]::new), Type.getArgumentTypes(wrapper.handler().desc),
                "Wrapper must receive closest, Minecraft and the original operation");
        assertEquals(Type.BOOLEAN_TYPE, Type.getReturnType(wrapper.handler().desc), "Preserve native consumption result");
    }

    private static List<Wrapper> registeredWrappers(String targetOwner, String entryName) throws IOException {
        var result = new ArrayList<Wrapper>();
        try (var input = SchematicPickSourceMixinContractTest.class.getClassLoader()
                .getResourceAsStream("litematica-printer.litematica.mixins.json")) {
            assertNotNull(input, "Missing Litematica mixin registration");
            var config = JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var registered : config.getAsJsonArray("mixins")) {
                var mixin = read(prefix + registered.getAsString().replace('.', '/'));
                var annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                        .filter(candidate -> candidate.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
                if (!targets(annotation).contains(targetOwner)) continue;
                for (var method : mixin.methods) {
                    for (var operation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        if (!operation.desc.endsWith("/WrapOperation;")) continue;
                        var entries = (List<?>) value(operation, "method");
                        assertNotNull(entries, "Wrapper must declare its input entry");
                        if (entries.contains(entryName)) result.add(new Wrapper(annotation, operation, method));
                    }
                }
            }
        }
        return result;
    }

    private static List<String> targets(AnnotationNode mixin) {
        var result = new ArrayList<String>();
        if (value(mixin, "value") instanceof List<?> types) {
            for (var type : types) result.add(((Type) type).getInternalName());
        }
        if (value(mixin, "targets") instanceof List<?> names) {
            for (var name : names) result.add(((String) name).replace('.', '/'));
        }
        return result;
    }

    private record Wrapper(AnnotationNode mixin, AnnotationNode operation, MethodNode handler) {}

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
        try (var input = SchematicPickSourceMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Missing compatibility class " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
