//#if MC >= 12004
package me.aleksilassila.litematica.printer.integration.containersync;

import java.util.List;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Validate the actual screen/packet hook targets in each supported Minecraft JAR. */
class ContainerSyncCrafterContractTest {
    @BeforeAll static void bootstrap() { ContainerSyncPlanTest.bootstrap(); }

    @Test void manualToggleIsGuardedBeforeCrafterScreenPredictsIt() throws Exception {
        String mixin = "me/aleksilassila/litematica/printer/mixin/MixinCrafterScreen";
        var hook = read(mixin).methods.stream().filter(m -> m.name.equals("printer$guardCrafterInput")).findFirst().orElseThrow();
        var inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("slotClicked"), value(inject, "method"));
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("HEAD", value(at, "value"));
        assertEquals(1, read("net/minecraft/client/gui/screens/inventory/CrafterScreen").methods.stream()
                .filter(m -> m.name.equals("slotClicked")).count());
        try (var input = getClass().getClassLoader().getResourceAsStream("litematica-printer.core.mixins.json")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("\"MixinCrafterScreen\""));
        }
        var old = RuntimeAccess.currentOrNull();
        try (var f = new Fixture()) {
            set(f.runtime, PrinterRuntime.class, "containerSync", f.controller);
            RuntimeAccess.install(f.runtime);
            var type = Class.forName(mixin.replace('/', '.'));
            var method = type.getDeclaredMethod("printer$guardCrafterInput", CallbackInfo.class);
            method.setAccessible(true);
            var handler = type.getDeclaredConstructor().newInstance();
            var active = new CallbackInfo("slotClicked", true);
            method.invoke(handler, active);
            assertTrue(active.isCancelled());
            assertFalse(f.otherInventoryOwner());
            f.phase("IDLE");
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_SWITCH.setBooleanValue(false);
            var idle = new CallbackInfo("slotClicked", true);
            method.invoke(handler, idle);
            assertFalse(idle.isCancelled(), "Ordinary manual crafter toggling must work with the printer disabled");
        } finally {
            set(null, RuntimeAccess.class, "current", old);
        }
    }

    @Test void slotStatePacketsAreObservedOnlyAfterVanillaAppliesThem() throws Exception {
        var hook = read("me/aleksilassila/litematica/printer/mixin/MixinClientPacketListener").methods.stream()
                .filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream().anyMatch(a ->
                        a.desc.endsWith("/Inject;") && List.of("handleContainerSetData").equals(value(a, "method"))))
                .findFirst().orElseThrow();
        var inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals("TAIL", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));
        assertEquals(1, read("net/minecraft/client/multiplayer/ClientPacketListener").methods.stream()
                .filter(m -> m.name.equals("handleContainerSetData")).count());
        assertTrue(hook.desc.startsWith("(Lnet/minecraft/network/protocol/game/ClientboundContainerSetDataPacket;"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void screenPrecheckDoesNotConsumeTheActualManualRecoveryClick(boolean disabledCrafterSlot) throws Exception {
        var old = RuntimeAccess.currentOrNull();
        try (var f = new Fixture()) {
            set(f.runtime, PrinterRuntime.class, "containerSync", f.controller);
            RuntimeAccess.install(f.runtime);
            var menu = new net.minecraft.world.inventory.CrafterMenu(7, ContainerSyncTypesTest.inventory(f.client.player));
            f.client.player.containerMenu = menu;
            menu.setCarried(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            if (disabledCrafterSlot) menu.setSlotState(0, false);
            f.phase("RESYNC");
            set(f.controller, ContainerSyncController.class, "resyncMenu", menu);
            set(f.controller, ContainerSyncController.class, "resyncRequested", true);
            set(f.controller, ContainerSyncController.class, "resyncNeedsAttention", true);
            var type = Class.forName("me.aleksilassila.litematica.printer.mixin.MixinCrafterScreen");
            var method = type.getDeclaredMethod("printer$guardCrafterInput", CallbackInfo.class);
            method.setAccessible(true);
            var screenInput = new CallbackInfo("slotClicked", true);
            method.invoke(type.getDeclaredConstructor().newInstance(), screenInput);
            assertFalse(screenInput.isCancelled());
            // Vanilla predicts an enable before calling super.slotClicked for this recovery destination.
            if (disabledCrafterSlot) menu.setSlotState(0, true);
            assertFalse(f.controller.guardManualInput(), "The gameMode guard must allow the same recovery click");
            assertFalse((boolean) ContainerSyncTypesTest.field(f.controller, "resyncRequested"));
            assertFalse((boolean) ContainerSyncTypesTest.field(f.controller, "resyncNeedsAttention"));
            assertTrue(f.controller.guardManualInput(), "Only one actual recovery click may be in flight");
            assertFalse(f.otherInventoryOwner());
            // Keep the lease until a newer full snapshot confirms the recovery, then release it.
            menu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
            f.controller.tick();
            f.controller.content(7, menu.getStateId() + 1, menu.slots.stream().map(s -> s.getItem()).toList(), menu.getCarried());
            assertTrue(f.otherInventoryOwner());
            assertEquals("PAUSED", ContainerSyncTypesTest.field(f.controller, "phase").toString());
        } finally {
            set(null, RuntimeAccess.class, "current", old);
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = ContainerSyncCrafterContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
}
//#endif
