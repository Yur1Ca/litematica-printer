package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;

import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * Owns the indivisible packet bundle used by the piston bedrock exploit.
 *
 * <p>Once the first break packet is sent, the remaining packets must follow without
 * waiting for client or server state. Server state is observed by {@link BedrockTarget}
 * on later ticks; it is deliberately not used as a mid-bundle acknowledgement.</p>
 */
final class BedrockCriticalExecutor {
    private final BedrockPlacer placer;

    BedrockCriticalExecutor(BedrockPlacer placer) {
        this.placer = placer;
    }

    BedrockPlacer placer() {
        return this.placer;
    }

    List<BedrockTarget> submitBatch(List<BedrockTarget> candidates) {
        return this.submitBatch(candidates, Integer.MAX_VALUE, target -> false);
    }

    List<BedrockTarget> submitBatch(List<BedrockTarget> candidates, int newStarts, Predicate<BedrockTarget> started) {
        var client = Minecraft.getInstance();
        if (candidates == null || candidates.isEmpty() || !this.hasSafeInventory(client, client.level)
                || !NetworkUtils.canSendPacketBatch()) return List.of();
        if (newStarts <= 0 && candidates.stream().noneMatch(target -> target != null && started.test(target))) return List.of();
        // Do not let a later candidate overtake a material swap started by this batch.
        if (!this.placer.preparePistons()) return List.of();
        var selected = new ArrayList<BedrockTarget>();
        var packets = new ArrayList<Packet<?>>();
        var reservedToolUses = new HashMap<Integer, Integer>();
        int selectedStarts = 0;
        for (BedrockTarget target : candidates) {
            if (target == null || target.layout() == null || target.isHorizontalLayout() || target.hasTried()
                    || target.getStatus() != BedrockTarget.Status.EXTENDED || !target.canMutatePower()
                    || !BedrockEnvironment.arePositionsInteractable(target.getStaticMachinePositions())
                    || selected.stream().anyMatch(other -> conflicts(target, other))) continue;
            boolean newStart = !started.test(target);
            if (newStart && selectedStarts >= newStarts) continue;
            var torches = target.getOwnedTorchPositions();
            var placement = this.preflight(target.level(), target.getBedrockPos(), target.getPistonPos(),
                    target.layout().getExecuteFacing(), torches, selected.size() + 1);
            if (placement == null) continue;
            int toolSlot = InventoryUtils.getSelectedSlot(client.player.getInventory());
            int toolUses = reservedToolUses.getOrDefault(toolSlot, 0) + 1;
            if (!this.placer.canReserveToolUses(toolUses)) continue;
            var body = BedrockCriticalPackets.prepareBody(target.level(), client.player, target.getPistonPos(),
                    target.layout().getExecuteFacing(), torches, placement);
            if (body.isEmpty()) continue;
            selected.add(target);
            if (newStart) selectedStarts++;
            packets.addAll(body);
            reservedToolUses.put(toolSlot, toolUses);
        }
        if (selected.isEmpty()) return List.of();
        // A later candidate may have started an inventory move while earlier
        // bodies were frozen. Never let those bodies overtake its confirmation.
        var offhand = InventoryUtils.getOffhandStack(client.player);
        if (!this.hasSafeInventory(client, client.level)
                || me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get().inventorySwitchGuard().isWaiting()
                || !offhand.is(Items.PISTON) || this.placer.availablePistons() < selected.size()) return List.of();
        try {
            if (!NetworkUtils.sendPacketBatch(List.copyOf(packets))) return List.of();
            this.placer.recordRawUses(selected.size(), -1, 0);
            reservedToolUses.forEach((slot, uses) -> {
                for (int used = 0; used < uses; used++) this.placer.recordToolUse(slot);
            });
            return List.copyOf(selected);
        } finally {
            selected.forEach(target -> this.placer.cancelPistonPlacement(target.getPistonPos()));
        }
    }

    private static boolean conflicts(BedrockTarget target, BedrockTarget other) {
        if (!Collections.disjoint(target.getReservedPositions(), other.getReservedPositions())
                || target.sharesTorchPlacementWith(other)) return true;
        for (BlockPos torch : target.getOwnedTorchPositions()) {
            if (other.isTorchPoweredBy(torch)) return true;
        }
        for (BlockPos torch : other.getOwnedTorchPositions()) {
            if (target.isTorchPoweredBy(torch)) return true;
        }
        return false;
    }

    private boolean hasSafeInventory(Minecraft client, ClientLevel level) {
        return level != null && this.placer != null && !this.placer.isInventorySettling() && !this.placer.isToolSettling()
                && !this.placer.hasActiveCleanupMining()
                && client.player != null && client.level == level && client.player.inventoryMenu != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    boolean submit(
            ClientLevel level,
            BlockPos bedrockPos,
            BlockPos pistonPos,
            Direction executeFacing,
            Set<BlockPos> ownedTorchPositions
    ) {
        var placement = this.preflight(level, bedrockPos, pistonPos, executeFacing, ownedTorchPositions, 1);
        if (placement == null) return false;
        var client = Minecraft.getInstance();
        try {
            var packets = BedrockCriticalPackets.prepareBody(level, client.player, pistonPos, executeFacing,
                    ownedTorchPositions, placement);
            if (packets.isEmpty() || !NetworkUtils.sendPacketBatch(packets)) return false;
            this.placer.recordRawUses(1, -1, 0);
            this.placer.recordToolUse();
            return true;
        } finally {
            this.placer.cancelPistonPlacement(pistonPos);
        }
    }

    private BedrockPlacer.PreparedPistonPlacement preflight(ClientLevel level, BlockPos bedrockPos,
            BlockPos pistonPos, Direction executeFacing, Set<BlockPos> ownedTorchPositions, int requiredPistons) {
        var client = Minecraft.getInstance();
        if (!this.hasSafeInventory(client, level) || bedrockPos == null || pistonPos == null || executeFacing == null
                || ownedTorchPositions == null || ownedTorchPositions.isEmpty()) return null;
        if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(bedrockPos))) {
            return null;
        }

        var pistonState = level.getBlockState(pistonPos);
        if (!pistonState.is(Blocks.PISTON)
                || !pistonState.getValue(PistonBaseBlock.EXTENDED)
                || pistonState.getValue(PistonBaseBlock.FACING) != executeFacing.getOpposite()
                || !BedrockInventory.hasAtLeast(Items.PISTON, 1)) {
            return null;
        }
        if (!BedrockBreaker.prepareCriticalTool(pistonPos, pistonState, this.placer)) {
            return null;
        }
        for (BlockPos torch : ownedTorchPositions) {
            if (torch == null || !BedrockEnvironment.isRedstoneTorchAt(level, torch)
                    || !level.getBlockState(torch).getValue(RedstoneTorchBlock.LIT)
                    || !BedrockEnvironment.canInteract(torch)) return null;
        }
        // A second source can cancel the queued retract event. Do not destroy
        // another machine's power source or start an attempt that stays powered.
        for (BlockPos nearby : BedrockEnvironment.getTorchInfluencePositions(pistonPos)) {
            if (BedrockEnvironment.isRedstoneTorchAt(level, nearby) && !ownedTorchPositions.contains(nearby)) return null;
        }
        if (BedrockPowerQuery.hasResidualPower(level, pistonPos, pistonState.getValue(PistonBaseBlock.FACING),
                executeFacing, ownedTorchPositions)) return null;
        return this.placer.prepareCriticalPlacement(bedrockPos, pistonPos, executeFacing, requiredPistons);
    }
}
