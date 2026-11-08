package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
//#if MC >= 12101
import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Objects;

/** Waits for a native server player-tick boundary after a modern main-hand tool selection. */
final class BedrockToolSync {
    private final Minecraft client;
    private Object connection;
    private Object player;
    private Object level;
    private Object epoch;
    private Signature observed;
    private Signature confirmed;
    private Signature pending;
    private int pendingSequence = -1;

    BedrockToolSync(Minecraft client) { this.client = client; }

    private boolean bindContext() {
        var listener = this.client.getConnection();
        Object currentConnection = listener == null ? null : listener.getConnection();
        var runtime = RuntimeAccess.currentOrNull();
        Object currentEpoch = runtime == null ? null : runtime.epoch();
        if (this.connection != currentConnection || this.player != this.client.player
                || this.level != this.client.level || !Objects.equals(this.epoch, currentEpoch)) {
            this.reset();
            this.connection = currentConnection;
            this.player = this.client.player;
            this.level = this.client.level;
            this.epoch = currentEpoch;
        }
        return this.connection != null && this.player != null && this.level != null;
    }

    private Signature currentSignature() {
        if (this.client.player == null) return null;
        var inventory = this.client.player.getInventory();
        int slot = InventoryUtils.getSelectedSlot(inventory);
        if (!Inventory.isHotbarSlot(slot)) return null;
        var stack = inventory.getItem(slot);
        if (stack.isEmpty()) return null;
        var normalized = stack.copy();
        normalized.setCount(1);
        normalized.setDamageValue(0);
        return new Signature(slot, normalized);
    }

    void onMainHandSelection() {
        if (!this.bindContext()) {
            this.invalidateTool();
            this.observed = null;
            return;
        }
        var current = this.currentSignature();
        if (!same(this.observed, current)) {
            this.invalidateTool();
            this.observed = current;
        }
    }

    boolean prepare() {
        //#if MC >= 12101
        this.onMainHandSelection();
        if (this.observed == null || this.connection == null) return false;
        if (same(this.confirmed, this.observed)) return true;
        if (this.pendingSequence >= 0) return false;
        if (!NetworkUtils.canSendPacketBatch()) return false;
        var hand = this.markerHand();
        if (hand == null) return false;
        var look = NetworkUtils.getScopedLookOverride();
        var runtime = RuntimeAccess.currentOrNull();
        if (look == null && runtime != null && runtime.actionBroker() != null) look = runtime.actionBroker().getLook();
        float yaw = look == null ? this.client.player.getYRot() : look.yaw;
        float pitch = look == null ? this.client.player.getXRot() : look.pitch;
        // Air-use acknowledges the same native player-tick boundary without reading or changing
        // the server's hidden destroyPos. Never use an item that could consume, equip or activate.
        var packet = NetworkUtils.createPacket(sequence -> new ServerboundUseItemPacket(hand, sequence, yaw, pitch));
        if (!(packet instanceof ServerboundUseItemPacket marker)) return false;
        var selected = new ServerboundSetCarriedItemPacket(this.observed.slot());
        if (!NetworkUtils.sendPacketBatch(List.of(selected, marker))) return false;
        this.pending = this.observed;
        //#if MC >= 260300
        //$$ this.pendingSequence = marker.sequence();
        //#else
        this.pendingSequence = marker.getSequence();
        //#endif
        return false;
        //#else
        //$$ return true;
        //#endif
    }

    //#if MC >= 12101
    private InteractionHand markerHand() {
        var offhand = InventoryUtils.getOffhandStack(this.client.player);
        if (offhand.isEmpty() || offhand.is(Items.PISTON) && hasPassiveUse(offhand)) return InteractionHand.OFF_HAND;
        var tool = this.client.player.getMainHandItem();
        var id = BuiltInRegistries.ITEM.getKey(tool.getItem());
        String path = id.getPath();
        boolean vanillaTool = id.getNamespace().equals("minecraft") && (path.endsWith("_pickaxe")
                || path.endsWith("_axe") || path.endsWith("_shovel") || path.endsWith("_hoe") || tool.is(Items.SHEARS));
        return vanillaTool && hasPassiveUse(tool) ? InteractionHand.MAIN_HAND : null;
    }

    private static boolean hasPassiveUse(ItemStack stack) {
        //#if MC == 12101
        //$$ return !stack.has(DataComponents.FOOD);
        //#else
        return !stack.has(DataComponents.CONSUMABLE) && !stack.has(DataComponents.EQUIPPABLE)
                //#if MC >= 12105
                && !stack.has(DataComponents.BLOCKS_ATTACKS)
                //#endif
                //#if MC >= 12111
                && !stack.has(DataComponents.KINETIC_WEAPON)
                //#endif
                ;
        //#endif
    }
    //#endif

    boolean isPending() {
        //#if MC >= 12101
        this.onMainHandSelection();
        return this.pendingSequence >= 0;
        //#else
        //$$ return false;
        //#endif
    }

    void acknowledged(Object connection, int sequence) {
        //#if MC >= 12101
        this.onMainHandSelection();
        if (this.connection != connection || this.pendingSequence < 0 || sequence < this.pendingSequence
                || !same(this.pending, this.observed)) return;
        this.confirmed = this.pending;
        this.pending = null;
        this.pendingSequence = -1;
        //#endif
    }

    void invalidateTool() {
        this.confirmed = null;
        this.pending = null;
        this.pendingSequence = -1;
    }

    void reset() {
        this.invalidateTool();
        this.observed = null;
        this.connection = null;
        this.player = null;
        this.level = null;
        this.epoch = null;
    }

    private static boolean same(Signature first, Signature second) {
        if (first == null || second == null) return first == second;
        //#if MC > 12004
        return first.slot() == second.slot() && ItemStack.isSameItemSameComponents(first.stack(), second.stack());
        //#else
        //$$ return first.slot() == second.slot() && ItemStack.isSameItemSameTags(first.stack(), second.stack());
        //#endif
    }

    private record Signature(int slot, ItemStack stack) { }
}
