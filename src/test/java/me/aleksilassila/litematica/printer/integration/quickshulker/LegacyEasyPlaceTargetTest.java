//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper.HitType;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the legacy HEAD callback with controlled ray hits and real native block checks. */
public class LegacyEasyPlaceTargetTest {
    private static final String MIXIN = "me.aleksilassila.litematica.printer.mixin.printer.litematica.MixinInventoryUtils";
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static final BlockPos OTHER = TARGET.north();
    private static final BlockHitResult HIT = new BlockHitResult(Vec3.atCenterOf(TARGET), Direction.UP, TARGET, false);
    private static Fixture active;

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static final class Fixture implements AutoCloseable {
        final MaterialSelectionFixture game = prepareGame();
        final TestLevel real = allocate(TestLevel.class);
        final Schematic schematic = allocate(Schematic.class);
        final boolean oldFirst = Configs.Generic.EASY_PLACE_FIRST.getBooleanValue();
        final List<Item> requests = new ArrayList<>();
        final List<Item> takeItOutRequests = new ArrayList<>();
        RayTraceWrapper selected = new RayTraceWrapper(HitType.SCHEMATIC_BLOCK, HIT);
        boolean cached;
        boolean tooFast;
        boolean restricted;
        boolean requestConsumed = true;
        boolean useRealRequests;
        int nativePicks;
        double tracedRange;
        String traceMethod;
        final Class<?> mixin;
        final Map<BlockState, ItemStack> cache;
        final Map<BlockState, ItemStack> previousCache;

        private static MaterialSelectionFixture prepareGame() throws Exception {
            var game = new MaterialSelectionFixture();
            set(game.client, Minecraft.class, "gameDirectory", new java.io.File("build"));
            return game;
        }

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            active = this;
            real.states = new HashMap<>();
            schematic.states = new HashMap<>();
            schematic.states.put(TARGET, Blocks.STONE.defaultBlockState());
            game.client.level = real;
            set(game.player, Entity.class, "level", real);
            Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(true);
            cache = (Map<BlockState, ItemStack>) get(MaterialCache.getInstance(), MaterialCache.class, "buildItemsForStates");
            previousCache = new java.util.IdentityHashMap<>(cache);
            mixin = new HookLoader().loadClass(MIXIN);
        }

        boolean rightClick() throws Exception {
            return rightClickResult().isCancelled();
        }

        CallbackInfoReturnable<InteractionResult> rightClickResult() throws Exception {
            schematic.states.values().forEach(MaterialCache.getInstance()::getRequiredBuildItemForState);
            var method = mixin.getDeclaredMethod("easyPlace", Minecraft.class, CallbackInfoReturnable.class);
            method.setAccessible(true);
            var callback = new CallbackInfoReturnable<InteractionResult>("doEasyPlaceAction", true);
            method.invoke(null, game.client, callback);
            return callback;
        }

        void middleClick() throws Exception {
            userPick(true, false);
        }

        boolean userPick(boolean closest, boolean useKey) throws Exception {
            return wrapUserPick(useKey, args -> {
                assertEquals(closest, args[0]);
                assertSame(game.client, args[1]);
                try {
                    var callback = programmaticPick();
                    return callback.isCancelled() && callback.getReturnValue();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, closest);
        }

        boolean wrapUserPick(boolean useKey, Operation<Boolean> original, boolean closest) throws Exception {
            var type = Class.forName("me.aleksilassila.litematica.printer.mixin.printer.litematica."
                    + (useKey ? "MixinSchematicPickUseKey" : "MixinSchematicPickHotkeys"));
            var method = type.getDeclaredMethod("litematicaPrinter$userPick", boolean.class, Minecraft.class, Operation.class);
            method.setAccessible(true);
            return (boolean) method.invoke(type.getDeclaredConstructor().newInstance(), closest, game.client, original);
        }

        CallbackInfoReturnable<Boolean> programmaticPick() throws Exception {
            schematic.states.values().forEach(MaterialCache.getInstance()::getRequiredBuildItemForState);
            var method = mixin.getDeclaredMethod("schematicWorldPickBlock", boolean.class, Minecraft.class,
                    CallbackInfoReturnable.class);
            method.setAccessible(true);
            var callback = new CallbackInfoReturnable<Boolean>("doSchematicWorldPickBlock", true);
            method.invoke(null, true, game.client, callback);
            if (!callback.isCancelled()) nativePicks++;
            return callback;
        }

        @Override public void close() throws Exception {
            cache.clear();
            cache.putAll(previousCache);
            Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(oldFirst);
            active = null;
            game.close();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"completed", "occupied", "wrong_half_slab", "vanilla_hit", "miss"})
    void rightClickDoesNotRequestForAnIneligiblePlacement(String reason) throws Exception {
        try (var f = new Fixture()) {
            switch (reason) {
                case "completed" -> f.real.states.put(TARGET, Blocks.STONE.defaultBlockState());
                case "occupied" -> f.real.states.put(TARGET, Blocks.DIRT.defaultBlockState());
                case "wrong_half_slab" -> {
                    f.real.states.put(TARGET, Blocks.OAK_SLAB.defaultBlockState());
                    f.schematic.states.put(TARGET, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
                }
                case "vanilla_hit" -> f.selected = new RayTraceWrapper(HitType.VANILLA_BLOCK, HIT);
                case "miss" -> f.selected = null;
            }
            var result = f.rightClickResult();
            assertTrue(result.isCancelled(), "Stop foreign HEAD callbacks from restarting this rejected placement as a pick");
            assertSame(reason.equals("vanilla_hit") || reason.equals("miss") ? InteractionResult.PASS : InteractionResult.FAIL,
                    result.getReturnValue());
            assertTrue(f.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void usesNativeTargetChoiceAndReach(boolean first) throws Exception {
        try (var f = new Fixture()) {
            Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(first);
            f.schematic.states.put(OTHER, Blocks.REDSTONE_TORCH.defaultBlockState());
            f.selected = new RayTraceWrapper(HitType.SCHEMATIC_BLOCK,
                    new BlockHitResult(Vec3.atCenterOf(OTHER), Direction.UP, OTHER, false));
            assertTrue(f.rightClick());
            assertEquals(List.of(Items.REDSTONE_TORCH), f.requests, "Request the native target, not a separate schematic-only ray");
            assertEquals(first ? "generic" : "furthest", f.traceMethod);
            assertEquals(4.5D, f.tracedRange);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cached", "too_fast"})
    void rejectedRepeatedPlacementDoesNotStartAnotherRestock(String reason) throws Exception {
        try (var f = new Fixture()) {
            f.cached = reason.equals("cached");
            f.tooFast = reason.equals("too_fast");
            var result = f.rightClickResult();
            assertTrue(result.isCancelled());
            assertSame(InteractionResult.FAIL, result.getReturnValue());
            assertTrue(f.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"air", "replaceable", "matching_half_slab"})
    void eligibleMissingMaterialStillRequestsWithAnEmptyHand(String target) throws Exception {
        try (var f = new Fixture()) {
            if (target.equals("replaceable")) f.real.states.put(TARGET, Blocks.SHORT_GRASS.defaultBlockState());
            if (target.equals("matching_half_slab")) {
                f.real.states.put(TARGET, Blocks.STONE_SLAB.defaultBlockState());
                f.schematic.states.put(TARGET, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
            }
            assertTrue(f.game.player.getMainHandItem().isEmpty());
            assertTrue(f.rightClick());
            assertEquals(List.of(target.equals("matching_half_slab") ? Items.STONE_SLAB : Items.STONE), f.requests);
        }
    }

    @Test void explicitMiddleClickCanStillPickAnAlreadyBuiltBlock() throws Exception {
        try (var f = new Fixture()) {
            f.real.states.put(TARGET, Blocks.STONE.defaultBlockState());
            f.middleClick();
            assertEquals(List.of(Items.STONE), f.requests);
        }
    }

    @Test void foreignMissingItemHookCannotRestartAnObstructedRightClickAsMiddleClick() throws Exception {
        try (var f = new Fixture()) {
            f.selected = new RayTraceWrapper(HitType.VANILLA_BLOCK, HIT);
            var result = f.rightClickResult();
            // Take It Out's later HEAD callback calls doSchematicWorldPickBlock(true, mc).
            if (!result.isCancelled()) f.programmaticPick();
            assertTrue(f.requests.isEmpty(), "Right-clicking a real block must not request the schematic material behind it");
            assertSame(InteractionResult.PASS, result.getReturnValue());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"vanilla", "furthest_miss", "first_miss"})
    void preservesNativePlacementRestrictionOnOrdinaryClicks(String hit) throws Exception {
        try (var f = new Fixture()) {
            f.restricted = true;
            Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(!hit.equals("furthest_miss"));
            f.selected = hit.equals("vanilla") ? new RayTraceWrapper(HitType.VANILLA_BLOCK, HIT) : null;
            var result = f.rightClickResult();
            assertSame(hit.equals("first_miss") ? InteractionResult.PASS : InteractionResult.FAIL, result.getReturnValue());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void projectionWithoutABuildItemKeepsNativeSuccessWithoutRequesting() throws Exception {
        try (var f = new Fixture()) {
            f.schematic.states.put(TARGET, Blocks.AIR.defaultBlockState());
            assertSame(InteractionResult.SUCCESS, f.rightClickResult().getReturnValue());
            assertTrue(f.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cached", "too_fast"})
    void nativeGuardsStillPrecedeTheEmptyBuildItemResult(String reason) throws Exception {
        try (var f = new Fixture()) {
            f.schematic.states.put(TARGET, Blocks.AIR.defaultBlockState());
            f.cached = reason.equals("cached");
            f.tooFast = reason.equals("too_fast");
            assertSame(InteractionResult.FAIL, f.rightClickResult().getReturnValue());
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void eligibleTargetContinuesNativePlacementWhenRestockDoesNotConsumeTheClick() throws Exception {
        try (var f = new Fixture()) {
            f.requestConsumed = false;
            assertFalse(f.rightClick());
            assertEquals(List.of(Items.STONE), f.requests);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignAutomaticPickDoesNotBecomeAPrinterMaterialRequest(boolean easyPlaceEnabled) throws Exception {
        try (var f = new Fixture()) {
            boolean previous = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
            try {
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(easyPlaceEnabled);
                // TIO's own mouse callback invokes this shared method without a user pick hotkey.
                assertTrue(f.programmaticPick().isCancelled(), "TIO's own request stays under inventory ownership");
                assertTrue(f.requests.isEmpty(), "A foreign automatic pick must not fall through to the warehouse or other Printer providers");
                assertEquals(List.of(Items.STONE), f.takeItOutRequests);
                assertEquals(0, f.nativePicks, "Do not retry outside the guarded TIO-only request");
            } finally {
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(previous);
            }
        }
    }

    @Test void foreignMissingMaterialRetainsTheTakeItOutSynchronizationBoundary() throws Exception {
        try (var f = new Fixture()) {
            f.programmaticPick();
            assertEquals(List.of(Items.STONE), f.takeItOutRequests,
                    "TIO's request must retain inventory ownership instead of being sent outside the broker");
            assertTrue(f.requests.isEmpty(), "The TIO-only request cannot enter the shared fallback chain");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignRightClickAndExplicitInputsDoNotShareAuthorization(boolean easyPlaceEnabled) throws Exception {
        try (var f = new Fixture()) {
            boolean previous = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
            try {
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(easyPlaceEnabled);
                for (String entry : List.of("pick_first", "pick_last", "pick_last_on_use", "easy_place")) {
                    f.requests.clear();
                    assertTrue(f.programmaticPick().isCancelled());
                    assertTrue(f.requests.isEmpty());
                    if (entry.equals("easy_place")) {
                        // The real use handler calls Easy Place only while its mode is enabled.
                        if (easyPlaceEnabled) assertTrue(f.rightClick());
                    } else {
                        assertTrue(f.userPick(entry.equals("pick_first"), entry.equals("pick_last_on_use")));
                    }
                    assertEquals(entry.equals("easy_place") && !easyPlaceEnabled ? List.of() : List.of(Items.STONE), f.requests);
                    int submitted = f.requests.size();
                    assertTrue(f.programmaticPick().isCancelled());
                    assertEquals(submitted, f.requests.size(), "The following ordinary right-click must not inherit authorization");
                }
            } finally {
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(previous);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anExceptionInEitherInputWrapperCannotAuthorizeTheNextForeignPick(boolean useKey) throws Exception {
        try (var f = new Fixture()) {
            var failure = new IllegalStateException("native pick failed");
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> f.wrapUserPick(useKey, args -> { throw failure; }, false));
            assertSame(failure, thrown.getCause());
            assertTrue(f.programmaticPick().isCancelled());
            assertTrue(f.requests.isEmpty());
            f.middleClick();
            assertEquals(List.of(Items.STONE), f.requests);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unavailableExplicitPickPreservesNativeResultAndDoesNotAuthorizeLaterClicks(boolean useKey) throws Exception {
        try (var f = new Fixture()) {
            f.requestConsumed = false;
            assertFalse(f.userPick(false, useKey));
            assertEquals(List.of(Items.STONE), f.requests);
            assertEquals(1, f.nativePicks);
            assertTrue(f.programmaticPick().isCancelled());
            assertEquals(List.of(Items.STONE), f.requests);
            assertEquals(1, f.nativePicks);
        }
    }

    public static final class Traces {
        public static BlockHitResult traceToSchematicWorld(Entity entity, double range, boolean fluids, boolean rangeOnly) {
            active.tracedRange = range;
            active.traceMethod = "schematic_only";
            return HIT;
        }
        public static RayTraceWrapper getGenericTrace(Level world, Entity entity, double range, boolean respectRange,
                                                       boolean targetFluids, boolean visibleOnly) {
            active.tracedRange = range;
            active.traceMethod = "generic";
            return active.selected;
        }
        public static RayTraceWrapper getFurthestSchematicWorldTraceBeforeVanilla(Level world, Entity entity, double range) {
            active.tracedRange = range;
            active.traceMethod = "furthest";
            return active.selected;
        }
        public static HitResult getRayTraceFromEntity(Level world, Entity entity, boolean fluids, double range) { return HIT; }
    }

    public static final class Context {
        public static WorldSchematic getSchematicWorld() { return active.schematic; }
        public static double getValidBlockRange(Minecraft client) { return 4.5D; }
        public static boolean placementRestrictionInEffect(Minecraft client) { return active.restricted; }
        public static boolean easyPlaceIsPositionCached(BlockPos pos) { return active.cached; }
        public static boolean easyPlaceIsTooFast() { return active.tooFast; }
        public static boolean inventoryOperationPending() {
            return active.useRealRequests
                    && me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge.inventoryOperationPending();
        }
        public static boolean handleTakeItOutPickBlock(LocalPlayer player, ItemStack stack) {
            if (active.useRealRequests) {
                return me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge.handleTakeItOutPickBlock(player, stack);
            }
            active.takeItOutRequests.add(stack.getItem());
            return true;
        }
        public static boolean handleEasyPlacePickBlock(LocalPlayer player, ItemStack stack) {
            if (active.useRealRequests) {
                return me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge.handleEasyPlacePickBlock(player, stack);
            }
            active.requests.add(stack.getItem());
            return active.requestConsumed;
        }
        public static boolean easyPlaceBlockChecksCancel(BlockState schematic, BlockState real, Player player,
                                                         HitResult hit, ItemStack required) throws Exception {
            var method = WorldUtils.class.getDeclaredMethod("easyPlaceBlockChecksCancel", BlockState.class,
                    BlockState.class, Player.class, HitResult.class, ItemStack.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, schematic, real, player, hit, required);
        }
    }

    static class Schematic extends WorldSchematic {
        Map<BlockPos, BlockState> states;
        Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }
    static class TestLevel extends ClientLevel {
        Map<BlockPos, BlockState> states;
        TestLevel() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }

    /** Keep callback code intact; replace camera input, Mixin bindings and the request boundary. */
    static final class HookLoader extends ClassLoader {
        HookLoader() { super(LegacyEasyPlaceTargetTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(MIXIN)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        var node = new ClassNode();
                        new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (!(instruction instanceof MethodInsnNode call)) continue;
                            if (call.owner.equals("fi/dy/masa/litematica/util/RayTraceUtils")) {
                                call.owner = Type.getInternalName(Traces.class);
                            } else if (call.owner.equals("fi/dy/masa/litematica/world/SchematicWorldHandler")
                                    || call.owner.equals("me/aleksilassila/litematica/printer/utils/mods/QuickShulkerBridge")
                                    || call.name.equals("getValidBlockRange") || call.name.equals("easyPlaceIsPositionCached")
                                    || call.name.equals("easyPlaceIsTooFast") || call.name.equals("easyPlaceBlockChecksCancel")
                                    || call.name.equals("placementRestrictionInEffect")) {
                                call.owner = Type.getInternalName(Context.class);
                            } else if (call.owner.endsWith("/EasyPlaceUtilsAccessor") && call.name.startsWith("call")) {
                                call.owner = Type.getInternalName(Context.class);
                                call.name = Character.toLowerCase(call.name.charAt(4)) + call.name.substring(5);
                                call.itf = false;
                            }
                        }
                        var writer = new ClassWriter(0);
                        node.accept(writer);
                        byte[] bytes = writer.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
//#endif
