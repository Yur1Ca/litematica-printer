package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.ToolInventorySelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Coordinates effective-tool selection, native mining budgets and Tweakeroo's additional restrictions. */
public final class ToolSwitchService {
    private final Minecraft client;
    private final TweakerooToolSwitchPort tweakeroo;
    private final InventorySwitchGuard switchGuard;
    private final MiningDurabilityGuard durabilityGuard;

    public ToolSwitchService(
            Minecraft client,
            TweakerooToolSwitchPort tweakeroo,
            InventorySwitchGuard switchGuard
    ) {
        this.client = client;
        this.tweakeroo = tweakeroo;
        this.switchGuard = switchGuard;
        this.durabilityGuard = new MiningDurabilityGuard(client);
    }

    /**
     * Prepares one damage-producing break without spending its budget. The controller reserves
     * the actual use only when it is about to start a new mining target.
     */
    public ToolPreparationResult prepareForBreak(BlockPos pos, BlockState state, boolean allowEffectiveSwitch) {
        LocalPlayer player = this.client.player;
        if (player == null || player.getAbilities().instabuild || pos == null || state == null
                || state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return player == null ? ToolPreparationResult.UNAVAILABLE : ToolPreparationResult.READY;
        }
        if (this.switchGuard.isWaiting()) {
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        if (!this.durabilityGuard.prepare()) {
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        if (this.durabilityGuard.enabled()) {
            return this.prepareProtectedBreak(player, pos, state, allowEffectiveSwitch);
        }

        int beforeSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        ItemStack before = player.getMainHandItem().copy();
        if (allowEffectiveSwitch) {
            if (this.tweakeroo.isEffectiveToolSwitchEnabled()) {
                this.tweakeroo.switchToEffectiveTool(pos);
            } else if (Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()) {
                InventoryUtils.switchToBestTool(player, state, pos);
            }
        }
        if (this.tweakeroo.isDurabilityGuardActive()
                && !this.tweakeroo.prepareCurrentTool(pos, state)) {
            return ToolPreparationResult.BLOCKED_BY_DURABILITY;
        }
        int afterSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        if (beforeSlot != afterSlot || stackFingerprintChanged(before, player.getMainHandItem())) {
            this.switchGuard.markSwitchIfNeeded(player.getMainHandItem());
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        return ToolPreparationResult.READY;
    }

    private ToolPreparationResult prepareProtectedBreak(LocalPlayer player, BlockPos pos, BlockState state,
                                                        boolean allowEffectiveSwitch) {
        int selected = InventoryUtils.getSelectedSlot(player.getInventory());
        boolean replacement = !this.canUseSlot(player, selected, pos, state);
        if (replacement && (!this.tweakeroo.isDurabilityGuardActive()
                || this.tweakeroo.isCurrentToolUsable(player.getMainHandItem()))
                && this.durabilityGuard.needsCurrentCalibration(state, pos)) {
            // Unbreaking and rejected attempts can exhaust the conservative budget while this
            // tool still has plenty of durability. Keep it selected until the receipt settles it.
            this.durabilityGuard.requestCalibration();
            return ToolPreparationResult.SWITCHED_WAITING_SYNC;
        }
        if (!replacement && (!allowEffectiveSwitch
                || !this.tweakeroo.isEffectiveToolSwitchEnabled() && !Configs.Break.BREAK_AUTO_TOOL.getBooleanValue())) {
            return ToolPreparationResult.READY;
        }
        // The external effective-tool search cannot filter this guard's shadow budgets. Keep
        // its enable switch, but choose among safe slots here so it cannot restore a worn tool.
        int candidate = ToolInventorySelector.findBestUsableToolSlot(player, state, this.client.level, pos,
                slot -> this.canUseSlot(player, slot, pos, state), replacement);
        if (candidate < 0) return replacement ? this.awaitCalibrationOrBlock() : ToolPreparationResult.READY;
        if (!this.equipProtectedSlot(player, candidate)) return this.awaitCalibrationOrBlock();
        if (!this.canUseSlot(player, InventoryUtils.getSelectedSlot(player.getInventory()), pos, state)) {
            return this.awaitCalibrationOrBlock();
        }
        this.switchGuard.markSwitchIfNeeded(player.getMainHandItem());
        return ToolPreparationResult.SWITCHED_WAITING_SYNC;
    }

    private boolean canUseSlot(LocalPlayer player, int slot, BlockPos pos, BlockState state) {
        return this.durabilityGuard.canUse(slot, state, pos)
                && (!this.tweakeroo.isDurabilityGuardActive()
                    || this.tweakeroo.isCurrentToolUsable(player.getInventory().getItem(slot)));
    }

    private ToolPreparationResult awaitCalibrationOrBlock() {
        return this.durabilityGuard.requestCalibration()
                ? ToolPreparationResult.SWITCHED_WAITING_SYNC : ToolPreparationResult.BLOCKED_BY_DURABILITY;
    }

    private boolean equipProtectedSlot(LocalPlayer player, int source) {
        var menu = player.inventoryMenu;
        if (this.client.player != player || menu == null || player.containerMenu != menu
                || !menu.getCarried().isEmpty() || this.client.gameMode == null) return false;
        var inventory = player.getInventory();
        ItemStack expected = inventory.getItem(source).copy();
        if (Inventory.isHotbarSlot(source)) {
            InventoryUtils.setHotbarSlot(source, inventory);
            return this.client.player == player && player.containerMenu == menu
                    && InventoryUtils.getSelectedSlot(inventory) == source
                    && sameStack(expected, player.getMainHandItem());
        }
        if (source < 9 || source >= 36) return false;
        int selected = InventoryUtils.getSelectedSlot(inventory);
        ItemStack previous = player.getMainHandItem().copy();
        boolean applied = false;
        this.durabilityGuard.beginSwap(source, selected);
        try {
            this.client.gameMode.handleContainerInput(menu.containerId, source, selected, ContainerInput.SWAP, player);
            applied = this.client.player == player && player.containerMenu == menu && menu.getCarried().isEmpty()
                    && InventoryUtils.getSelectedSlot(inventory) == selected
                    && sameStack(expected, player.getMainHandItem()) && sameStack(previous, inventory.getItem(source));
            return applied;
        } finally {
            this.durabilityGuard.endSwap(applied);
        }
    }

    private static boolean sameStack(ItemStack expected, ItemStack actual) {
        return expected.getCount() == actual.getCount() && ItemStack.isSameItemSameComponents(expected, actual);
    }

    /** Equip the caller's qualified tool by its exact slot, preserving shared durability accounting. */
    public boolean equipBreakToolSlot(int slot) {
        var player = this.client.player;
        if (player == null || slot < 0 || slot >= 36 || this.switchGuard.isWaiting()
                || !this.isToolUsable(player.getInventory().getItem(slot)) || !this.durabilityGuard.prepare()
                || !this.equipProtectedSlot(player, slot)) return false;
        this.switchGuard.markSwitchIfNeeded(player.getMainHandItem());
        return true;
    }

    /** Bedrock follows a pure hotbar selection with its own native equipment-tick acknowledgement. */
    public ToolPreparationResult prepareForBedrockBreak(BlockPos pos, BlockState state) {
        LocalPlayer player = this.client.player;
        if (player == null || this.switchGuard.isWaiting() || !this.tweakeroo.isDurabilityGuardActive()
                || this.tweakeroo.isCurrentToolUsable(player.getMainHandItem())) {
            return this.prepareForBreak(pos, state, false);
        }
        var menu = player.containerMenu;
        if (menu == null || menu != player.inventoryMenu || !menu.getCarried().isEmpty()) {
            return this.prepareForBreak(pos, state, false);
        }
        // Only a real guard-driven replacement pays for this comparison. Ordinary mining
        // keeps the existing fast path; a selection plus any menu mutation stays exclusive.
        var inventory = player.getInventory();
        int beforeSlot = InventoryUtils.getSelectedSlot(inventory);
        var beforeItems = new ArrayList<ItemStack>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) beforeItems.add(inventory.getItem(slot).copy());
        var result = this.prepareForBreak(pos, state, false);
        long receipt = this.switchGuard.receipt();
        if (result != ToolPreparationResult.SWITCHED_WAITING_SYNC || player != this.client.player
                || player.containerMenu != menu || !menu.getCarried().isEmpty()
                || beforeSlot == InventoryUtils.getSelectedSlot(inventory)
                || beforeItems.size() != inventory.getContainerSize()) return result;
        for (int slot = 0; slot < beforeItems.size(); slot++) {
            if (!ItemStack.matches(beforeItems.get(slot), inventory.getItem(slot))) return result;
        }
        this.switchGuard.confirmReceipt(receipt);
        return ToolPreparationResult.READY;
    }

    public boolean isDurabilityGuardActive() {
        return this.durabilityGuard.enabled() || this.tweakeroo.isDurabilityGuardActive();
    }

    public boolean isToolUsable(ItemStack stack) {
        return this.durabilityGuard.isStackUsable(stack)
                && (!this.tweakeroo.isDurabilityGuardActive() || this.tweakeroo.isCurrentToolUsable(stack));
    }

    public boolean reserveForBreak(BlockPos pos, BlockState state) { return this.durabilityGuard.reserve(state, pos); }

    public boolean canReserveRawToolUses(int uses) { return this.durabilityGuard.canReserveRawToolUses(uses); }

    public boolean reserveRawBreaks(List<Packet<?>> packets) { return this.durabilityGuard.reserveRawBreaks(packets); }

    /** A hold already owns its one reservation; continuing it must not require another START's credit. */
    public boolean canContinueReservedBreak(BlockPos pos, BlockState state) {
        return this.durabilityGuard.prepare() && this.durabilityGuard.canContinue(state, pos)
                && (!this.tweakeroo.isDurabilityGuardActive()
                    || this.tweakeroo.isCurrentToolUsable(this.client.player.getMainHandItem()));
    }

    /** An existing hold survives wear receipts, with native or optional tool protection still enforced. */
    public boolean canContinueReservedBreak(BlockPos pos, BlockState state, ItemStack startedTool) {
        if (this.client.player == null) return false;
        ItemStack held = this.client.player.getMainHandItem();
        if (held.isDamageableItem() && held.getDamageValue() < startedTool.getDamageValue()) return false;
        ItemStack compared = startedTool.copy();
        if (compared.isDamageableItem()) compared.setDamageValue(held.getDamageValue());
        return ItemStack.matches(compared, held) && this.canContinueReservedBreak(pos, state);
    }

    public void onInventoryInput(Packet<?> packet) { this.durabilityGuard.onInventoryInput(packet); }

    public void onInventorySynchronized(Object connection) { this.durabilityGuard.onInventorySynchronized(connection); }

    public void reset() { this.durabilityGuard.reset(); }

    private static boolean stackFingerprintChanged(ItemStack before, ItemStack after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isEmpty() != after.isEmpty();
        }
        return before.getItem() != after.getItem()
                || before.getDamageValue() != after.getDamageValue()
                || before.getCount() != after.getCount();
    }
}
