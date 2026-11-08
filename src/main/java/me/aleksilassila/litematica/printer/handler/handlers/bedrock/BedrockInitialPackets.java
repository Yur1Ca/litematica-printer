package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

/** Freezes ordered initial placements; relative inventory moves synchronize separately. */
final class BedrockInitialPackets {
    private BedrockInitialPackets() { }

    record Preparation(List<BedrockPlacer.PistonRequest> accepted, boolean powered, boolean changedMainHand) {
        private static final Preparation EMPTY = new Preparation(List.of(), false, false);
    }

    static Preparation placeBatch(Minecraft client, BedrockInventorySession inventory,
            List<BedrockPlacer.PistonRequest> requests, Predicate<BlockPos> cleanupPending) {
        var player = client.player;
        var level = client.level;
        if (requests == null || requests.isEmpty() || player == null || level == null || client.gameMode == null
                || inventory.isSettling() || player.inventoryMenu == null || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty() || !NetworkUtils.canSendPacketBatch()) return Preparation.EMPTY;
        if (!inventory.preparePistons()) return Preparation.EMPTY;
        var eligible = new ArrayList<BedrockPlacer.PistonRequest>();
        var placements = new LinkedHashMap<BlockPos, BedrockPlacer.PreparedPistonPlacement>();
        boolean needsTorch = false;
        for (var request : requests) {
            if (request == null || request.pistonPos() == null || request.facing() == null
                    || request.facing().getAxis() != Direction.Axis.Y || placements.containsKey(request.pistonPos())) continue;
            var piston = preparePiston(client, request, cleanupPending);
            if (piston == null) continue;
            var torch = request.torchPlacement();
            if (torch != null) {
                if (!canPrepareTorch(client, torch, cleanupPending)) continue;
                needsTorch |= BlockUtils.isReplaceable(level.getBlockState(torch.getTorchPos()));
            }
            eligible.add(request);
            placements.put(request.pistonPos(), piston);
        }
        if (eligible.isEmpty()) return Preparation.EMPTY;
        int torchSlot = findTorchSlot(player, inventory);
        if (needsTorch && torchSlot < 0) {
            inventory.prepareMaterial(Items.REDSTONE_TORCH);
            return Preparation.EMPTY;
        }
        int availableTorches = torchSlot < 0 ? 0 : inventory.availableCount(torchSlot);
        int availablePistons = inventory.availablePistons();
        var accepted = new ArrayList<BedrockPlacer.PistonRequest>();
        var frozenPistons = new ArrayList<BedrockPlacer.PreparedPistonPlacement>();
        var power = new LinkedHashMap<BlockPos, BedrockTorchPlacement>();
        var torches = new LinkedHashMap<BlockPos, BlockHitResult>();
        for (var request : eligible) {
            if (accepted.size() >= availablePistons) break;
            var torch = request.torchPlacement();
            if (torch != null) {
                var previous = power.get(torch.getTorchPos());
                if (previous != null && (!previous.getSupportPos().equals(torch.getSupportPos())
                        || previous.getClickedFace() != torch.getClickedFace())) continue;
                if (BlockUtils.isReplaceable(level.getBlockState(torch.getTorchPos())) && !torches.containsKey(torch.getTorchPos())) {
                    if (torches.size() >= availableTorches) continue;
                    var face = torch.getClickedFace();
                    var hit = new BlockHitResult(Vec3.atCenterOf(torch.getSupportPos()).add(
                            face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5),
                            face, torch.getSupportPos(), false);
                    torches.put(torch.getTorchPos(), hit);
                }
                power.put(torch.getTorchPos(), torch);
            }
            accepted.add(request);
            frozenPistons.add(placements.get(request.pistonPos()));
        }
        if (accepted.isEmpty()) return Preparation.EMPTY;
        boolean powered = accepted.stream().anyMatch(request -> request.torchPlacement() != null);
        if (client.player != player || client.level != level || inventory.isSettling()
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
                || !InventoryUtils.getOffhandStack(player).is(Items.PISTON)
                || inventory.availablePistons() < accepted.size()) return Preparation.EMPTY;
        var packets = powered ? preparePowered(player, frozenPistons, List.copyOf(torches.values()), torchSlot)
                : prepare(player, frozenPistons);
        if (packets.isEmpty() || !NetworkUtils.sendPacketBatch(packets)) return Preparation.EMPTY;
        inventory.recordRawUses(accepted.size(), torchSlot, torches.size());
        return new Preparation(List.copyOf(accepted), powered, !torches.isEmpty());
    }

    private static int findTorchSlot(LocalPlayer player, BedrockInventorySession inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(Items.REDSTONE_TORCH) && inventory.availableCount(slot) > 0) return slot;
        }
        return -1;
    }

    private static boolean canPrepareTorch(Minecraft client, BedrockTorchPlacement torch, Predicate<BlockPos> cleanupPending) {
        if (torch.getSupportPos() == null || torch.getTorchPos() == null || torch.getClickedFace() == null) return false;
        var level = client.level;
        var support = level.getBlockState(torch.getSupportPos());
        // Slime may still be a local prediction from this tick; its original confirmed path remains separate.
        return !support.is(Blocks.SLIME_BLOCK) && !BedrockTargetBlocks.requiresSneakPlacement(support)
                && !cleanupPending.test(torch.getSupportPos()) && !cleanupPending.test(torch.getTorchPos())
                && BedrockEnvironment.arePositionsInteractable(torch.getSupportPos(), torch.getTorchPos())
                && BedrockEnvironment.isTorchPlacementUsable(level, torch);
    }

    private static BedrockPlacer.PreparedPistonPlacement preparePiston(Minecraft client, BedrockPlacer.PistonRequest request,
                                                                       Predicate<BlockPos> cleanupPending) {
        var level = client.level;
        var pos = request.pistonPos();
        var facing = request.facing();
        var anchor = pos.relative(facing.getOpposite());
        var anchorState = level.getBlockState(anchor);
        if (!BedrockTargetBlocks.isTargetBlock(anchorState) || BedrockTargetBlocks.requiresSneakPlacement(anchorState)
                || cleanupPending.test(anchor) || cleanupPending.test(pos) || cleanupPending.test(pos.relative(facing))
                || !BedrockEnvironment.hasRoomForPiston(level, pos, facing)
                || !BedrockEnvironment.arePositionsInteractable(anchor, pos, pos.relative(facing))) return null;
        var piston = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, facing);
        if (!level.isUnobstructed(piston, pos, CollisionContext.empty())) return null;
        if (BedrockEnvironment.getPlacementInteractions(level, pos, anchor).stream()
                .noneMatch(interaction -> interaction.anchorPos().equals(anchor) && interaction.clickedFace() == facing)) return null;
        var hit = new BlockHitResult(Vec3.atCenterOf(anchor).add(facing.getStepX() * 0.5,
                facing.getStepY() * 0.5, facing.getStepZ() * 0.5), facing, anchor, false);
        return new BedrockPlacer.PreparedPistonPlacement(hit, new PlayerLook(facing.getOpposite()));
    }

    static List<Packet<?>> preparePowered(LocalPlayer player, List<BedrockPlacer.PreparedPistonPlacement> placements,
                                          List<BlockHitResult> torches, int torchSlot) {
        if (player == null || torches == null) return List.of();
        if (torches.isEmpty()) return prepare(player, placements);
        int restoreSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        if (!Inventory.isHotbarSlot(torchSlot) || !Inventory.isHotbarSlot(restoreSlot)) return List.of();
        var stack = player.getInventory().getItem(torchSlot);
        if (!stack.is(Items.REDSTONE_TORCH) || stack.getCount() < torches.size()
                || torches.stream().anyMatch(hit -> hit == null || hit.getBlockPos() == null || hit.getDirection() == null)) {
            return List.of();
        }
        var initial = prepare(player, placements);
        if (initial.isEmpty()) return List.of();
        var packets = new ArrayList<Packet<?>>(initial);
        packets.add(new ServerboundSetCarriedItemPacket(torchSlot));
        for (var hit : torches) {
            var use = NetworkUtils.createPacket(sequence -> {
                //#if MC > 11802
                return new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence);
                //#else
                //$$ return new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit);
                //#endif
            });
            if (use == null) return List.of();
            packets.add(use);
        }
        packets.add(new ServerboundSetCarriedItemPacket(restoreSlot));
        return List.copyOf(packets);
    }

    static List<Packet<?>> prepare(LocalPlayer player, List<BedrockPlacer.PreparedPistonPlacement> placements) {
        if (player == null || placements == null || placements.isEmpty() || player.inventoryMenu == null
                || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return List.of();
        for (var placement : placements) {
            if (placement == null || placement.hit() == null || placement.look() == null) return List.of();
        }
        var packets = new ArrayList<Packet<?>>();
        for (var placement : placements) {
            packets.add(NetworkUtils.createLookPacketIgnoringQueuedLook(player, placement.look()));
            var use = NetworkUtils.createPacket(sequence -> {
                //#if MC > 11802
                return new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, placement.hit(), sequence);
                //#else
                //$$ return new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, placement.hit());
                //#endif
            });
            if (use == null) return List.of();
            packets.add(use);
        }
        return List.copyOf(packets);
    }
}
