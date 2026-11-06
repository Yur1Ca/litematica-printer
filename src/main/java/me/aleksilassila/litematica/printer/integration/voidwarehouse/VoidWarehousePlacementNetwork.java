package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

//#if MC >= 12111
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import neko.voidwarehouse.restock.PrinterPlaceHelloPayload;
import neko.voidwarehouse.restock.PrinterPlaceHelloResultPayload;
import neko.voidwarehouse.restock.PrinterPlacePayload;
import neko.voidwarehouse.restock.PrinterPlaceStatePayload;
import neko.voidwarehouse.restock.PrinterPlaceResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
//#endif

/** Direct warehouse placement has no inventory mutation or client block prediction. */
public final class VoidWarehousePlacementNetwork {
    private VoidWarehousePlacementNetwork() { }

    //#if MC >= 12111
    private static final VoidWarehousePlacementState<BlockPos> STATE = new VoidWarehousePlacementState<>();
    private static boolean initialized;
    private static Object connection;
    private static Object world;
    private static long tick;

    public static void init() {
        if (initialized) return;
        // Identical neko.* payload classes are shared with the integrated server's global registry.
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceHelloPayload.TYPE, PrinterPlaceHelloPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playC2S().register(PrinterPlaceHelloPayload.TYPE, PrinterPlaceHelloPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlacePayload.TYPE, PrinterPlacePayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playC2S().register(PrinterPlacePayload.TYPE, PrinterPlacePayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceStatePayload.TYPE, PrinterPlaceStatePayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playC2S().register(PrinterPlaceStatePayload.TYPE, PrinterPlaceStatePayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceHelloResultPayload.TYPE, PrinterPlaceHelloResultPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playS2C().register(PrinterPlaceHelloResultPayload.TYPE, PrinterPlaceHelloResultPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceResultPayload.TYPE, PrinterPlaceResultPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playS2C().register(PrinterPlaceResultPayload.TYPE, PrinterPlaceResultPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        ClientPlayNetworking.registerGlobalReceiver(PrinterPlaceHelloResultPayload.TYPE,
                VoidWarehousePlacementNetwork::receiveHello);
        ClientPlayNetworking.registerGlobalReceiver(PrinterPlaceResultPayload.TYPE,
                VoidWarehousePlacementNetwork::receiveResult);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        initialized = true;
    }

    public static void tick() {
        tick++;
        if (!ensureSession()) return;
        STATE.tick(tick);
        publishMaterialAvailabilityChange();
        int nonce = STATE.beginHello(tick);
        if (nonce == 0) return;
        try {
            ClientPlayNetworking.send(new PrinterPlaceHelloPayload(nonce));
        } catch (RuntimeException disconnected) {
            reset();
        }
    }

    /** True only after this connection has positively negotiated all server rules. */
    public static boolean isAvailable() {
        return ensureSession() && STATE.isEnabled();
    }

    /** The state channel advertises the server's optional Servux placement bridge. */
    public static boolean isStatePlacementSupported() {
        if (!isAvailable()) return false;
        try {
            return ClientPlayNetworking.canSend(PrinterPlaceStatePayload.TYPE);
        } catch (RuntimeException disconnected) {
            return false;
        }
    }

    /** Hold eligible inventory placement while this world's first capability answer is unknown. */
    public static boolean isNegotiating() {
        return ensureSession() && STATE.isNegotiating(tick);
    }

    /** Material eligibility is separate from temporary target, traffic and capacity limits. */
    public static boolean isMaterialAvailable(Item[] candidates) {
        return !STATE.eligibleCandidates(candidateIds(candidates), tick).isEmpty();
    }

    public static boolean canRequest(Item[] candidates, BlockPos target) {
        return isAvailable() && STATE.canRequest(candidateIds(candidates), target, tick);
    }

    /** Unsupported clicks briefly return to the usual inventory placement path; throttle only defers. */
    public static boolean isTargetAvailable(BlockPos target) {
        return STATE.isTargetAvailable(target, tick);
    }

    /** target is the position that will change; hit is the vanilla support-block click. */
    public static boolean requestPlace(Item[] candidates, BlockPos target, BlockHitResult hit, int expectedStateId) {
        if (hit == null || expectedStateId < 0 || !isAvailable() || target == null) return false;
        List<String> ids = STATE.eligibleCandidates(candidateIds(candidates), tick);
        BlockPos immutableTarget = target.immutable();
        int nonce = STATE.beginRequest(ids, immutableTarget, tick);
        if (nonce == 0) return false;
        try {
            String dimensionId = Minecraft.getInstance().level.dimension().identifier().toString();
            ClientPlayNetworking.send(new PrinterPlacePayload(nonce, ids, immutableTarget, hit, expectedStateId, dimensionId));
            return true;
        } catch (RuntimeException disconnected) {
            STATE.cancel(nonce);
            return false;
        }
    }

    /** Physical hit remains geometry; required properties are only a server-validated request. */
    public static boolean requestStatePlace(Item[] candidates, BlockPos target, BlockHitResult physicalHit,
                                           int expectedStateId, BlockState requiredState) {
        if (physicalHit == null || target == null || expectedStateId < 0 || requiredState == null
                || !isFinite(physicalHit.getLocation()) || !isStatePlacementSupported()) return false;
        StateRequestPlan plan = planStateRequest(STATE.eligibleCandidates(candidateIds(candidates), tick), requiredState);
        if (plan == null) return false;
        BlockPos immutableTarget = target.immutable();
        int nonce = STATE.beginRequest(plan.candidates(), immutableTarget, tick);
        if (nonce == 0) return false;
        Minecraft client = Minecraft.getInstance();
        Object requestConnection = connection;
        Object requestWorld = world;
        try {
            String dimensionId = client.level.dimension().identifier().toString();
            var placement = new PrinterPlacePayload(nonce, plan.candidates(), immutableTarget, physicalHit, expectedStateId, dimensionId);
            ClientPlayNetworking.send(new PrinterPlaceStatePayload(placement, plan.blockId(), plan.properties()));
            return true;
        } catch (RuntimeException failed) {
            STATE.cancel(nonce);
            Minecraft current = Minecraft.getInstance();
            if (current == null || current != client || current.getConnection() == null
                    || current.getConnection() != requestConnection || current.level != requestWorld
                    || !current.getConnection().getConnection().isConnected()) {
                reset();
                return false;
            }
            throw failed;
        }
    }

    record StateRequestPlan(List<String> candidates, String blockId, Map<String, String> properties) { }

    /** Multi-stage actions can place a source material that differs from the final blueprint block. */
    static StateRequestPlan planStateRequest(List<String> eligibleCandidates, BlockState requiredState) {
        if (eligibleCandidates == null || requiredState == null) return null;
        for (String candidate : eligibleCandidates) {
            Identifier id = candidate == null ? null : Identifier.tryParse(candidate);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item.getClass() != BlockItem.class) continue;
            var block = ((BlockItem) item).getBlock();
            BlockState placementState = block.withPropertiesOf(requiredState);
            return new StateRequestPlan(List.of(id.toString()), BuiltInRegistries.BLOCK.getKey(block).toString(),
                    stateProperties(placementState));
        }
        return null;
    }

    static Map<String, String> stateProperties(BlockState state) {
        var properties = new LinkedHashMap<String, String>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyValue(state, property));
        }
        return Map.copyOf(properties);
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static boolean isFinite(Vec3 hit) {
        return hit != null && Double.isFinite(hit.x) && Double.isFinite(hit.y) && Double.isFinite(hit.z);
    }

    public static boolean isPending(BlockPos target) {
        return ensureSession() && STATE.isPending(target);
    }

    static List<String> candidateIds(Item[] candidates) {
        if (candidates == null) return List.of();
        var ids = new LinkedHashSet<String>();
        for (Item item : candidates) {
            if (item == null || item.getClass() != BlockItem.class
                    || ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock
                    || ((BlockItem) item).getBlock() instanceof BedBlock
                    || ((BlockItem) item).getBlock() instanceof DoorBlock
                    || ((BlockItem) item).getBlock() instanceof DoublePlantBlock) continue;
            String id = VoidWarehouseMaterialIds.idOfItem(item);
            if (id != null) ids.add(id);
            if (ids.size() >= PrinterPlacePayload.MAX_CANDIDATES) break;
        }
        return List.copyOf(ids);
    }

    private static boolean ensureSession() {
        Minecraft client = Minecraft.getInstance();
        if (!initialized || client == null || client.player == null || client.level == null || client.getConnection() == null
                || client.gameMode == null || client.gameMode.getPlayerMode() != GameType.SURVIVAL
                || !Configs.Special.VOID_WAREHOUSE_DIRECT_PLACE.getBooleanValue() || !hasChannels()) {
            reset();
            return false;
        }
        if (connection != client.getConnection() || world != client.level) {
            STATE.reset();
            connection = client.getConnection();
            world = client.level;
        }
        return true;
    }

    private static boolean hasChannels() {
        try {
            return ClientPlayNetworking.canSend(PrinterPlaceHelloPayload.TYPE)
                    && ClientPlayNetworking.canSend(PrinterPlacePayload.TYPE);
        } catch (RuntimeException disconnected) {
            return false;
        }
    }

    // Fabric invokes play receivers on the client thread. Old-player contexts are never accepted.
    private static boolean isCurrentReceiver(ClientPlayNetworking.Context context) {
        Minecraft client = Minecraft.getInstance();
        return context.player() == client.player && ensureSession()
                && context.player().connection == connection && client.level == world;
    }

    private static void receiveHello(PrinterPlaceHelloResultPayload payload, ClientPlayNetworking.Context context) {
        if (isCurrentReceiver(context)) {
            STATE.receiveHello(payload.nonce(), payload.enabled(), tick);
            publishMaterialAvailabilityChange();
        }
    }

    private static void receiveResult(PrinterPlaceResultPayload payload, ClientPlayNetworking.Context context) {
        if (isCurrentReceiver(context)) STATE.receiveResult(payload.nonce(), payload.status(), tick);
    }

    private static void publishMaterialAvailabilityChange() {
        if (!STATE.consumeMaterialAvailabilityChange()) return;
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime != null) runtime.events().publish(new RuntimeEvent.MaterialAvailabilityChanged());
    }

    private static void reset() {
        STATE.reset();
        connection = null;
        world = null;
    }
    //#else
    // The server capability exists only on 1.21.11+; earlier clients keep their normal placement path.
    //$$ public static void init() { }
    //$$ public static void tick() { }
    //$$ public static boolean isAvailable() { return false; }
    //$$ public static boolean isStatePlacementSupported() { return false; }
    //$$ public static boolean isNegotiating() { return false; }
    //$$ public static boolean isMaterialAvailable(Item[] candidates) { return false; }
    //$$ public static boolean canRequest(Item[] candidates, BlockPos target) { return false; }
    //$$ public static boolean isTargetAvailable(BlockPos target) { return false; }
    //$$ public static boolean requestPlace(Item[] candidates, BlockPos target, BlockHitResult hit, int expectedStateId) { return false; }
    //$$ public static boolean requestStatePlace(Item[] candidates, BlockPos target, BlockHitResult physicalHit, int expectedStateId, BlockState requiredState) { return false; }
    //$$ public static boolean isPending(BlockPos target) { return false; }
    //#endif
}
