package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
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
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Direct warehouse placement has no inventory mutation or client block prediction. */
public final class VoidWarehousePlacementNetwork {
    private VoidWarehousePlacementNetwork() { }

    private static final VoidWarehousePlacementState<BlockPos> STATE = new VoidWarehousePlacementState<>();
    private static final VoidWarehousePlacementDiagnostics DIAGNOSTICS = new VoidWarehousePlacementDiagnostics();
    private static boolean initialized;
    private static Object connection;
    private static Object world;
    private static long tick;
    private static final Logger LOGGER = LoggerFactory.getLogger("litematica-printer/warehouse-place");
    private static final Map<String, Long> TRACE_TIMES = new LinkedHashMap<>();
    private static String lastCapabilities;

    public static void init() {
        if (initialized) return;
        // Identical neko.* payload classes are shared with the integrated server's global registry.
        try {
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceHelloPayload.TYPE, PrinterPlaceHelloPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlacePayload.TYPE, PrinterPlacePayload.CODEC);
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceStatePayload.TYPE, PrinterPlaceStatePayload.CODEC);
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceHelloResultPayload.TYPE, PrinterPlaceHelloResultPayload.CODEC);
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) { }
        try {
            PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceResultPayload.TYPE, PrinterPlaceResultPayload.CODEC);
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
        logSummary();
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

    /** Temporary target rejection pauses direct placement without authorizing inventory restocking. */
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
            DIAGNOSTICS.recordSent(tick);
            trace("request", ids, immutableTarget, "mode=ordinary clicked=" + hit.getBlockPos().toShortString()
                    + " face=" + hit.getDirection());
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
            BlockHitResult reachableHit = VoidWarehousePlacementHit.adjustAirHit(immutableTarget, physicalHit,
                    client.player.getEyePosition(), client.player.blockInteractionRange() + 1.0);
            var placement = new PrinterPlacePayload(nonce, plan.candidates(), immutableTarget, reachableHit, expectedStateId, dimensionId);
            ClientPlayNetworking.send(new PrinterPlaceStatePayload(placement, plan.blockId(), plan.properties()));
            DIAGNOSTICS.recordSent(tick);
            trace("request", plan.candidates(), immutableTarget, "mode=state block=" + plan.blockId()
                    + " properties=" + plan.properties() + " clicked=" + reachableHit.getBlockPos().toShortString()
                    + " face=" + reachableHit.getDirection() + " hit=" + reachableHit.getLocation()
                    + " adjusted=" + (reachableHit != physicalHit));
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

    /** Logs the actual legacy route, including unsupported items, without changing that route. */
    public static void recordInventoryFallback(Item[] candidates, BlockPos target, String actionType,
                                              boolean eligible, boolean precisionRequired) {
        if (!Configs.Special.VOID_WAREHOUSE_DIRECT_PLACE.getBooleanValue()) return;
        List<String> ids = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (candidates != null) for (Item item : candidates) {
            if (item == null) continue;
            ids.add(String.valueOf(VoidWarehouseMaterialIds.idOfItem(item)));
            types.add(item.getClass().getSimpleName());
        }
        boolean channels = hasChannels();
        boolean stateChannel = hasStateChannel();
        String reason = !eligible ? "unsupported-action-or-item"
                : !channels ? "direct-channel-missing"
                : !STATE.isEnabled() ? "rule-disabled-or-negotiation-timeout"
                : precisionRequired && !stateChannel ? "servux-state-channel-missing" : "inventory-route";
        trace("inventory:" + reason, ids, target, "action=" + actionType + " itemTypes=" + types
                + " enabled=" + STATE.isEnabled() + " directChannel=" + channels + " stateChannel=" + stateChannel);
    }

    private static boolean hasStateChannel() {
        try { return ClientPlayNetworking.canSend(PrinterPlaceStatePayload.TYPE); }
        catch (RuntimeException disconnected) { return false; }
    }

    private static void trace(String event, List<String> items, BlockPos target, String detail) {
        String key = event + ':' + items;
        Long previous = TRACE_TIMES.get(key);
        if (previous != null && tick - previous < 200) return;
        if (previous == null && TRACE_TIMES.size() >= 128) TRACE_TIMES.remove(TRACE_TIMES.keySet().iterator().next());
        TRACE_TIMES.put(key, tick);
        LOGGER.info("[WarehouseDirectPlace] {} items={} target={} {}", event, items, target, detail);
    }

    private static String statusName(int status) {
        return switch (status) {
            case PrinterPlaceResultPayload.OK -> "OK";
            case PrinterPlaceResultPayload.RULE_OFF -> "RULE_OFF";
            case PrinterPlaceResultPayload.EMPTY -> "EMPTY";
            case PrinterPlaceResultPayload.REJECTED -> "REJECTED";
            case PrinterPlaceResultPayload.THROTTLED -> "THROTTLED";
            case PrinterPlaceResultPayload.BAD_REQUEST -> "BAD_REQUEST";
            case PrinterPlaceResultPayload.STALE -> "STALE";
            default -> "UNKNOWN(" + status + ')';
        };
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
            DIAGNOSTICS.reset(tick);
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
            String capabilities = "enabled=" + STATE.isEnabled() + " stateChannel=" + hasStateChannel();
            if (!capabilities.equals(lastCapabilities)) {
                LOGGER.info("[WarehouseDirectPlace] capabilities {}", capabilities);
                lastCapabilities = capabilities;
            }
            publishMaterialAvailabilityChange();
        }
    }

    private static void receiveResult(PrinterPlaceResultPayload payload, ClientPlayNetworking.Context context) {
        if (isCurrentReceiver(context)) {
            handleResult(payload, Minecraft.getInstance().level);
        }
    }

    static void handleResult(PrinterPlaceResultPayload payload, ClientLevel level) {
        STATE.tick(tick);
        var request = STATE.pendingRequest(payload.nonce());
        if (request != null) {
            DIAGNOSTICS.recordResult(payload.status(), request.sentAt(), tick);
            trace("result:" + statusName(payload.status()), request.candidates(), request.target(),
                    "nonce=" + payload.nonce());
        }
        STATE.receiveResult(payload.nonce(), payload.status(), tick);
        if (request != null && payload.status() == PrinterPlaceResultPayload.OK) {
            BlockState placed = level.getBlockState(request.target());
            if (!placed.isAir()) {
                var sound = placed.getSoundType();
                level.playLocalSound(request.target(), sound.getPlaceSound(), SoundSource.BLOCKS,
                        (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
            }
        }
    }

    private static void logSummary() {
        var summary = DIAGNOSTICS.poll(tick, STATE.pendingCount());
        if (summary == null) return;
        LOGGER.info("[WarehouseDirectPlace] summary ticks={} sent={} OK={} REJECTED={} STALE={} THROTTLED={} EMPTY={} other={} pending={} averageResponseTicks={} maxResponseTicks={}",
                summary.ticks(), summary.sent(), summary.ok(), summary.rejected(), summary.stale(),
                summary.throttled(), summary.empty(), summary.other(), summary.pending(),
                summary.averageResponseTicks(), summary.maxResponseTicks());
    }

    private static void publishMaterialAvailabilityChange() {
        if (!STATE.consumeMaterialAvailabilityChange()) return;
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime != null) runtime.events().publish(new RuntimeEvent.MaterialAvailabilityChanged());
    }

    private static void reset() {
        if (connection != null || world != null) {
            TRACE_TIMES.clear();
            lastCapabilities = null;
        }
        STATE.reset();
        DIAGNOSTICS.reset(tick);
        connection = null;
        world = null;
    }
}
