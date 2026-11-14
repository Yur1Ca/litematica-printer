package me.aleksilassila.litematica.printer.utils.minecraft;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Source-water ray checks adapted from BiliXWhite/litematica-printer@6cef6d31. */
public final class WaterSurfacePlacement {
    private WaterSurfacePlacement() { }

    public static PlayerLook aim(LocalPlayer player, ClientLevel level, BlockPos target) {
        BlockPos water = target.below();
        double height = level.getBlockState(water).getFluidState().getHeight(level, water);
        Vec3 delta = new Vec3(target.getX() + .5, water.getY() + height - .001, target.getZ() + .5)
                .subtract(player.getEyePosition());
        double horizontal = Math.hypot(delta.x, delta.z);
        return new PlayerLook(horizontal < 1.0e-6 ? player.getYRot() : (float)Math.toDegrees(Math.atan2(-delta.x, delta.z)),
                (float)-Math.toDegrees(Math.atan2(delta.y, horizontal)));
    }

    public static boolean canPlace(LocalPlayer player, ClientLevel level, BlockPos target, PlayerLook look) {
        if (player == null || level == null || look == null
                || !BlockStateUtils.hasSourceWaterFluid(level.getBlockState(target.below()))
                || !level.getBlockState(target).canBeReplaced()) return false;
        double range = player.blockInteractionRange();
        if (!Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()) {
            range = Math.max(range, Configs.Core.WORK_RANGE.getIntegerValue());
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(look.pitch, look.yaw).scale(range));
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target.below());
    }
}
