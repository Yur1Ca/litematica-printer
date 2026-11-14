package me.aleksilassila.litematica.printer.printer.action;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Uses upstream's USE_ITEM semantics through the existing guarded action queue. */
public final class LilyPadAction extends Action {
    public LilyPadAction(PlayerLook look) { this.playerLook = look; this.setItem(Items.LILY_PAD); }
    @Override public boolean requiresExactLook() { return true; }
    @Override public Direction getValidSide(ClientLevel world, BlockPos pos) { return Direction.DOWN; }
    @Override public boolean queueAction(ActionPort broker, BlockPos pos, Direction side, boolean shift,
                                         LocalPlayer player, Item[] expectedItems) {
        if (!broker.queueClick(pos, Direction.DOWN, Vec3.ZERO, shift, 1, expectedItems, ActionPort.ActionSource.PRINT)) return false;
        if (broker.setWaterSurfacePlacement()) return true;
        broker.cancelQueue();
        return false;
    }
}
