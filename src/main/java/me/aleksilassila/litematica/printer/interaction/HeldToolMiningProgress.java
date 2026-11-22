package me.aleksilassila.litematica.printer.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Set;

/** Predicts only the main-hand Efficiency modifier while vanilla computes a printer break. */
public final class HeldToolMiningProgress {
    private static final Identifier EFFICIENCY = Identifier.withDefaultNamespace("enchantment.efficiency/mainhand");
    private static final ThreadLocal<HeldToolMiningProgress> ACTIVE = new ThreadLocal<>();
    private LocalPlayer player;
    private ItemEnchantments enchantments;
    private ItemAttributeModifiers itemModifiers;
    private AttributeModifier expected;
    private Projection lastProjection;
    private AttributeModifier previousEfficiency;
    private boolean checkPrevious;
    private boolean raisedEfficiency;
    private boolean checkingStop;
    private int firstStart = -1;
    private boolean confirmed;

    float progress(BlockState state, LocalPlayer player, ClientLevel level, BlockPos pos) {
        this.raisedEfficiency = false;
        return this.progress(state, player, level, pos, false);
    }

    /** START is immediate. Only an optimistic STOP needs the pre-equipment-tick lower bound. */
    float stopProgress(float heldProgress, BlockState state, LocalPlayer player, ClientLevel level, BlockPos pos, int ack) {
        if (this.firstStart >= 0 && ack >= this.firstStart) this.confirmed = true;
        if (this.confirmed || !this.raisedEfficiency && !this.checkPrevious) return heldProgress;
        return Math.min(heldProgress, this.progress(state, player, level, pos, true));
    }

    void started(int sequence) {
        if (this.firstStart < 0 && sequence > 0) this.firstStart = sequence;
    }

    private float progress(BlockState state, LocalPlayer player, ClientLevel level, BlockPos pos, boolean checkingStop) {
        var previous = ACTIVE.get();
        var previousPlayer = this.player;
        boolean previousCheck = this.checkingStop;
        ACTIVE.set(this);
        this.player = player;
        this.checkingStop = checkingStop;
        try {
            return state.getDestroyProgress(player, level, pos);
        } finally {
            this.checkingStop = previousCheck;
            this.player = previousPlayer;
            ACTIVE.set(previous);
        }
    }

    void reset() {
        this.enchantments = null;
        this.itemModifiers = null;
        this.expected = null;
        this.lastProjection = null;
        this.previousEfficiency = null;
        this.checkPrevious = false;
        this.firstStart = -1;
        this.confirmed = false;
    }

    /** Called only at vanilla's MINING_EFFICIENCY read in Player.getDestroySpeed. */
    public static double efficiency(Player player, double vanillaValue) {
        var active = ACTIVE.get();
        if (active == null || active.player != player) return vanillaValue;
        return active.equippedEfficiency(player, vanillaValue);
    }

    void observeHand(Player player) {
        var hand = player.getMainHandItem();
        var enchants = hand.getEnchantments();
        var modifiers = hand.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        // These components are immutable. Wear alone does not change the calculation.
        if (enchants != this.enchantments || modifiers != this.itemModifiers) {
            var previous = this.expected;
            this.expected = null;
            hand.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                if (EFFICIENCY.equals(modifier.id()) && attribute.is(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow())) {
                    this.expected = modifier;
                }
            });
            if (!Objects.equals(previous, this.expected)) {
                this.previousEfficiency = previous;
                this.checkPrevious = this.enchantments != null && this.expected != null;
                this.firstStart = -1;
                this.confirmed = false;
            }
            this.enchantments = enchants;
            this.itemModifiers = modifiers;
        }
    }

    private double equippedEfficiency(Player player, double vanillaValue) {
        this.observeHand(player);
        var attribute = player.getAttribute(Attributes.MINING_EFFICIENCY);
        if (attribute == null) return vanillaValue;
        var synced = attribute.getModifier(EFFICIENCY);
        if (this.checkingStop) {
            // The client can still display the first pick's +26 after pick -> shovel -> pick.
            return this.checkPrevious && !Objects.equals(synced, this.previousEfficiency)
                    ? Math.min(vanillaValue, this.project(attribute, synced, this.previousEfficiency)) : vanillaValue;
        }
        if (Objects.equals(synced, this.expected)) return vanillaValue;
        double value = this.project(attribute, synced, this.expected);
        this.raisedEfficiency |= value > vanillaValue;
        return value;
    }

    private double project(AttributeInstance attribute, AttributeModifier synced, AttributeModifier replacement) {
        var liveModifiers = attribute.getModifiers();
        double base = attribute.getBaseValue();
        if (liveModifiers.size() == (synced == null ? 0 : 1)
                && (replacement == null || replacement.operation() == AttributeModifier.Operation.ADD_VALUE)) {
            double amount = replacement == null ? 0.0D : replacement.amount();
            return attribute.getAttribute().value().sanitizeValue(base + amount);
        }
        // Several instant breaks can see the same stale server values in one dispatch.
        var previous = this.lastProjection;
        if (previous != null && previous.base == base && Objects.equals(previous.held, replacement)
                && previous.synced.equals(liveModifiers)) return previous.value;

        // Let vanilla apply all other modifiers and its range limits without mutating the player.
        var projected = new AttributeInstance(attribute.getAttribute(), ignored -> {});
        projected.replaceFrom(attribute);
        if (synced != null) projected.removeModifier(EFFICIENCY);
        if (replacement != null) projected.addTransientModifier(replacement);
        double value = projected.getValue();
        this.lastProjection = new Projection(base, liveModifiers, replacement, value);
        return value;
    }

    private record Projection(double base, Set<AttributeModifier> synced, AttributeModifier held, double value) {}
}
